from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import os
import sys
import threading
import time
from typing import Any

# 入口模块用绝对导入：PyInstaller 把它当 __main__ 跑，那时没有父包，相对导入会直接失败。
# 其余模块之间照旧用相对导入——它们是被 voice.xxx 导进来的，父包是明确的。
from voice.audio_io import input_devices, output_devices
from voice.client import RealtimeClient
from voice.config import AudioSettings, RealtimeProvider
from voice.errors import VoiceError
from voice.paths import log_dir
from voice.recorder import SessionRecorder

logger = logging.getLogger("voice")

READY_PREFIX = "VOICE_READY "

# 状态推送间隔。编排层每 100ms 跳一次心跳，读的就是这里推上来的最新值；
# 推得比它慢会让连续两跳看到同一份状态，打断判定与波形都会顿。
_STATUS_INTERVAL = 0.05


class _Out:
    """stdout 是事件的专用通道。

    父进程按行读，所以每条事件必须是一整行 JSON 且立刻 flush——非终端下 stdout 是块缓冲的，
    不 flush 的话握手行会一直卡在缓冲里，Java 侧只会等到超时。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()

    def emit(self, event: str, **data: Any) -> None:
        payload = json.dumps({"event": event, **data}, ensure_ascii=False)
        with self._lock:
            sys.stdout.write(payload + "\n")
            sys.stdout.flush()

    def raw(self, line: str) -> None:
        with self._lock:
            sys.stdout.write(line + "\n")
            sys.stdout.flush()


class _Bridge:
    """把 RealtimeSink 的回调翻成行协议事件。

    只做转发，一个决策都不做——「问什么」全在 Java 侧，这里只负责怎么发声。
    """

    def __init__(self, out: _Out, session: _Session) -> None:
        self._out = out
        self._session = session

    def on_state(self, connected: bool, reason: str) -> None:
        self._out.emit("state", connected=connected, reason=reason)

    def on_candidate_speech(self, speaking: bool) -> None:
        self._out.emit("candidate_speech", speaking=speaking)

    def on_candidate_text(
        self, text: str, *, final: bool, started_at_ms: int = 0, duration_ms: int = 0
    ) -> None:
        self._out.emit(
            "candidate_text",
            text=text,
            final=final,
            started_at_ms=started_at_ms,
            duration_ms=duration_ms,
        )

    def on_interviewer_text(
        self, text: str, *, final: bool, started_at_ms: int = 0, duration_ms: int = 0
    ) -> None:
        self._out.emit(
            "interviewer_text",
            text=text,
            final=final,
            started_at_ms=started_at_ms,
            duration_ms=duration_ms,
        )

    def on_candidate_audio(self, pcm: bytes, elapsed_ms: int) -> None:
        # PCM 每 40ms 一块，跨进程传纯属浪费，录音留在这一侧落盘
        recorder = self._session.recorder
        if recorder is not None:
            recorder.write_candidate(elapsed_ms, pcm)

    def on_interviewer_audio(self, pcm: bytes, elapsed_ms: int) -> None:
        recorder = self._session.recorder
        if recorder is not None:
            recorder.write_interviewer(elapsed_ms, pcm)

    def on_response(self, active: bool) -> None:
        self._out.emit("response", active=active)

    def on_voice_open(self, cue: object, lead_ms: int) -> None:
        # cue 是编排层给的不透明标记，原样送回，语义只有那边懂
        self._out.emit("voice_open", cue=cue, lead_ms=lead_ms)

    def on_barge_in(self) -> None:
        self._out.emit("barge_in")

    def on_unauthorized_response(self) -> None:
        logger.info("已拦截一次未授权发言")

    def on_error(self, message: str, *, fatal: bool) -> None:
        self._out.emit("error", message=message, fatal=fatal)


class _Session:
    """一场面试的语音侧状态。"""

    def __init__(self) -> None:
        self.client: RealtimeClient | None = None
        self.recorder: SessionRecorder | None = None
        self.started_at = 0.0

    def clock(self) -> int:
        if self.started_at <= 0.0:
            return 0
        return int((time.monotonic() - self.started_at) * 1000)


class VoiceServer:
    """行协议壳。stdin 收命令，stdout 回事件。"""

    def __init__(self, out: _Out) -> None:
        self._out = out
        self._session = _Session()
        self._commands: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
        self._status_task: asyncio.Task[None] | None = None

    # ---------- 主循环 ----------

    async def run(self) -> None:
        loop = asyncio.get_running_loop()
        reader = threading.Thread(target=self._read_stdin, args=(loop,), daemon=True)
        reader.start()
        self._status_task = asyncio.create_task(self._status_loop(), name="voice-status")
        self._out.raw(READY_PREFIX + json.dumps({"pid": os.getpid()}, ensure_ascii=False))

        while True:
            command = await self._commands.get()
            if command is None:
                break
            try:
                if await self._handle(command):
                    break
            except VoiceError as exc:
                logger.warning("命令执行失败 %s: %s", command.get("cmd"), exc)
                self._out.emit("error", message=str(exc), fatal=False)
            except Exception as exc:
                logger.exception("命令执行异常 %s", command.get("cmd"))
                self._out.emit("error", message=f"语音侧异常: {exc}", fatal=True)

        # stdin 关了就是父进程走了，把还开着的会话收干净再退。不再回报路径：没人在听了
        await self._end_session("进程退出", announce=False)
        if self._status_task is not None:
            self._status_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._status_task
            self._status_task = None

    def _read_stdin(self, loop: asyncio.AbstractEventLoop) -> None:
        """stdin 用阻塞读。

        Windows 上 asyncio 的 Proactor 事件循环没法把管道 stdin 接进 StreamReader，
        单起一个线程反而更省事。父进程退出会关掉 stdin，读到 EOF 就是收摊信号。
        """
        for line in sys.stdin:
            text = line.strip()
            if not text:
                continue
            try:
                command = json.loads(text)
            except json.JSONDecodeError:
                logger.warning("忽略不合法的命令行: %s", text[:120])
                continue
            loop.call_soon_threadsafe(self._commands.put_nowait, command)
        loop.call_soon_threadsafe(self._commands.put_nowait, None)

    async def _handle(self, command: dict[str, Any]) -> bool:
        """返回 True 表示该退出了。"""
        kind = str(command.get("cmd") or "")
        if kind == "connect":
            await self._connect(command)
            return False
        if kind == "close":
            await self._end_session(str(command.get("reason") or ""), announce=True)
            return False

        client = self._session.client
        if client is None:
            raise VoiceError(f"还没建立语音会话，忽略命令 {kind}")

        if kind == "directive":
            await client.send_directive(
                str(command.get("text") or ""), cue=command.get("cue")
            )
        elif kind == "reanchor":
            await client.reanchor(str(command.get("instructions") or ""))
        elif kind == "barge_in":
            await client.barge_in(str(command.get("text") or ""))
        elif kind == "cancel":
            await client.cancel_current_response()
        elif kind == "mute":
            client.capture.set_muted(bool(command.get("muted")))
        else:
            logger.warning("未知命令: %s", kind)
        return False

    # ---------- 会话 ----------

    async def _connect(self, command: dict[str, Any]) -> None:
        if self._session.client is not None:
            raise VoiceError("语音会话已建立")
        provider = RealtimeProvider.parse(command.get("provider") or {})
        audio = AudioSettings.parse(command.get("audio") or {})
        session_id = int(command.get("session_id") or 0)

        self._session.started_at = time.monotonic()
        if bool(command.get("save_audio", True)):
            self._session.recorder = SessionRecorder(
                session_id,
                candidate_rate=provider.input_sample_rate,
                interviewer_rate=provider.output_sample_rate,
            )
        bridge = _Bridge(self._out, self._session)
        client = RealtimeClient(
            provider=provider,
            model=provider.model,
            voice=provider.voice,
            api_key=provider.api_key,
            temperature=provider.temperature,
            audio=audio,
            sink=bridge,
            clock=self._session.clock,
            loop=asyncio.get_running_loop(),
        )
        await client.connect(str(command.get("instructions") or ""))
        self._session.client = client
        logger.info("语音会话就绪 session=%s model=%s", session_id, provider.model)

    async def _end_session(self, reason: str, *, announce: bool) -> None:
        """结束当前会话，进程留着等下一场。

        收到 close 时 audio_saved 无论如何都要发：Java 侧的收尾在等这条事件才知道录音落在哪。
        没有录音、甚至没建起会话，也要发一条空路径放它走，否则那边会一直等到超时。
        """
        client, self._session.client = self._session.client, None
        recorder, self._session.recorder = self._session.recorder, None
        if client is not None:
            with contextlib.suppress(Exception):
                await client.close(reason)
        path = ""
        if recorder is not None:
            # 合并双轨要读写几十兆，别占着事件循环
            merged = await asyncio.to_thread(recorder.finalize)
            path = str(merged) if merged else ""
        self._session.started_at = 0.0
        if announce:
            self._out.emit("audio_saved", path=path)

    async def _status_loop(self) -> None:
        while True:
            await asyncio.sleep(_STATUS_INTERVAL)
            client = self._session.client
            if client is None:
                continue
            self._out.emit(
                "status",
                connected=client.connected,
                responding=client.is_responding,
                pending_ms=client.player.pending_ms,
                candidate_level=round(client.capture.level, 4),
                voice_level=round(client.player.level, 4),
                auto_gain=round(client.capture.auto_gain_factor, 3),
            )


def _setup_logging() -> None:
    """日志只走 stderr 与文件。写进 stdout 会把行协议冲掉。"""
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stderr)]
    with contextlib.suppress(OSError):
        handlers.append(logging.FileHandler(log_dir() / "voice.log", encoding="utf-8"))
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        handlers=handlers,
    )


def _dump_devices() -> None:
    """设备可能被别的程序独占，枚举失败时什么都不打印，由界面回落到系统默认。"""
    try:
        payload = {
            "inputs": [{"name": d.name, "index": d.index} for d in input_devices()],
            "outputs": [{"name": d.name, "index": d.index} for d in output_devices()],
        }
    except Exception:
        logger.exception("枚举音频设备失败")
        return
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> None:
    parser = argparse.ArgumentParser(prog="interviewer-voice")
    parser.add_argument(
        "--list-devices",
        action="store_true",
        help="打印音频设备清单后退出。设置页在没有面试时也要能列设备",
    )
    args = parser.parse_args()

    _setup_logging()
    if args.list_devices:
        _dump_devices()
        return

    out = _Out()
    with contextlib.suppress(KeyboardInterrupt):
        asyncio.run(VoiceServer(out).run())


if __name__ == "__main__":
    main()
