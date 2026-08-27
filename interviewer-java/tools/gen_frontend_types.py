"""生成前端类型：接口 schema 与事件载荷。

手写接口定义迟早和后端错位，尤其 InterviewState 有几十个字段。这里直接从后端取真相：
真跑一次后端进程，拉它的 OpenAPI 文档，再交给 openapi-typescript 转换。

事件载荷也在同一份文档里（后端把它们注册进 components，并在 x-interviewer-events 里
记下事件名到 schema 名的映射），所以两类类型共用一个真相源，不会各自漂移。
"""

from __future__ import annotations

import json
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "backend" / "target" / "interviewer-backend.jar"
DATA_ROOT = ROOT / "backend" / "target" / "codegen-data"

OUT_DIR = ROOT / "desktop" / "src" / "lib"
SPEC_PATH = OUT_DIR / "api-spec.json"
SCHEMA_TS = OUT_DIR / "api-schema.d.ts"
EVENTS_TS = OUT_DIR / "event-types.ts"

HANDSHAKE_PREFIX = "INTERVIEWER_RPC "
EVENTS_EXTENSION = "x-interviewer-events"
STARTUP_TIMEOUT = 120.0


def tighten_response_models(spec: dict) -> int:
    """把响应模型的字段一律标为必填。

    带默认值的字段在 JSON Schema 里不是 required，生成的 TS 因此全是可选，读一个
    settings.realtime 都要判空。但后端序列化响应时会输出全部字段，可选是失真的。
    请求体不动——那里的可选是真的可选。
    """
    schemas = spec.get("components", {}).get("schemas", {})
    fixed = 0
    for name, schema in schemas.items():
        if name.endswith("Body"):
            continue
        props = schema.get("properties")
        if not props:
            continue
        if set(schema.get("required", [])) == set(props):
            continue
        schema["required"] = list(props)
        fixed += 1
    return fixed


def fetch_spec() -> dict:
    """启动后端、读握手行、拉文档、收摊。

    不用离线解析源码：文档是 springdoc 在运行时按真实的控制器与 Jackson 配置生成的，
    静态推导只会得到「看起来对」的结果。顺带验证了后端进程真的能起来。
    """
    if not JAR.exists():
        raise SystemExit(f"后端 jar 不存在：{JAR}\n先在 backend/ 下跑一次 mvn -DskipTests package")
    DATA_ROOT.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(
        [
            "java",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            f"-Dinterviewer.dataRoot={DATA_ROOT}",
            "-jar",
            str(JAR),
            "--port",
            "0",
        ],
        cwd=ROOT,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    # 后端的日志走 stderr，不排空会把管道写满，JVM 直接卡住
    tail: list[str] = []
    threading.Thread(target=_drain, args=(proc.stderr, tail), daemon=True).start()

    endpoint: dict | None = None
    timer = threading.Timer(STARTUP_TIMEOUT, proc.kill)
    timer.start()
    try:
        for line in proc.stdout:
            if line.startswith(HANDSHAKE_PREFIX):
                endpoint = json.loads(line[len(HANDSHAKE_PREFIX):])
                break
    finally:
        timer.cancel()

    if endpoint is None:
        proc.kill()
        raise SystemExit("后端没有报出连接信息。最后输出：\n" + "\n".join(tail[-30:]))

    base = f"http://{endpoint['host']}:{endpoint['port']}"
    try:
        request = urllib.request.Request(
            f"{base}/v3/api-docs",
            headers={"X-Interviewer-Token": endpoint["token"]},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            spec = json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise SystemExit(f"拉取接口文档失败: {exc}\n最后输出：\n" + "\n".join(tail[-30:])) from exc
    finally:
        _shutdown(proc)
    return spec


def _drain(stream, tail: list[str]) -> None:
    for line in stream:
        tail.append(line.rstrip())


def _shutdown(proc: subprocess.Popen) -> None:
    """关掉 stdin 就是让后端收摊的信号，它自己会退。"""
    try:
        if proc.stdin is not None:
            proc.stdin.close()
        proc.wait(timeout=20)
    except Exception:
        proc.kill()


def gen_events(spec: dict) -> int:
    """事件类型只做别名，不重新推导。

    载荷 schema 已经在 api-schema.d.ts 里由 openapi-typescript 生成过一遍，这里再推一遍
    就是第二份真相，两份迟早不一致。
    """
    mapping: dict[str, str] = spec.get(EVENTS_EXTENSION) or {}
    if not mapping:
        raise SystemExit(f"接口文档里没有 {EVENTS_EXTENSION}，后端的事件映射没生成")

    lines = [
        "// 由 tools/gen_frontend_types.py 生成，请勿手改。",
        "// 事件名与载荷来自后端 OpenAPI 文档的 " + EVENTS_EXTENSION + " 扩展字段。",
        "",
        'import type { components } from "./api-schema";',
        "",
        'type Payloads = components["schemas"];',
        "",
    ]
    # 逐个导出具名别名：界面里有按事件类型声明状态的地方，只有 EventMap 不够用
    for schema in sorted(set(mapping.values())):
        lines.append(f'export type {schema} = Payloads["{schema}"];')
    lines.append("")
    lines.append("/** 事件名到载荷的映射，供 onEvent 做类型推断。 */")
    lines.append("export interface EventMap {")
    for event, schema in sorted(mapping.items()):
        lines.append(f"  {event}: {schema};")
    lines.append("}")
    lines.append("")
    lines.append("export type EventName = keyof EventMap;")
    lines.append("")
    EVENTS_TS.write_text("\n".join(lines), encoding="utf-8")
    return len(mapping)


def gen_schema() -> None:
    """交给 openapi-typescript 转换。

    工作目录固定在 desktop 并用相对路径：项目路径含中文，绝对路径传给 npx 会在编码
    转换上出错。
    """
    npx = "npx.cmd" if sys.platform == "win32" else "npx"
    rel_spec = SPEC_PATH.relative_to(ROOT / "desktop").as_posix()
    rel_out = SCHEMA_TS.relative_to(ROOT / "desktop").as_posix()
    subprocess.run(
        [npx, "openapi-typescript", rel_spec, "-o", rel_out],
        cwd=ROOT / "desktop",
        check=True,
    )


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    spec = fetch_spec()
    fixed = tighten_response_models(spec)
    SPEC_PATH.write_text(json.dumps(spec, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"接口路径 {len(spec.get('paths', {}))} 条，收紧 {fixed} 个响应模型 -> {SPEC_PATH.name}")
    count = gen_events(spec)
    print(f"事件类型 {count} 个 -> {EVENTS_TS.name}")
    gen_schema()
    print(f"接口类型 -> {SCHEMA_TS.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
