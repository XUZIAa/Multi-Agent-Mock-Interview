from __future__ import annotations

from dataclasses import dataclass
from typing import Any

# 与 Java 侧 AudioSettings / RealtimeProvider 的取值范围一致。越界只钳制不报错：
# 这些数是运行时学习值和用户滑块，历史值超界不该让整场面试起不来。
_CLAMPS: dict[str, tuple[float, float]] = {
    "input_gain": (0.2, 4.0),
    "vad_threshold": (0.05, 0.95),
    "silence_duration_ms": (200, 2000),
    "prefix_padding_ms": (0, 1000),
    "learned_gain": (0.5, 8.0),
    "playback_buffer_ms": (60, 900),
    "temperature": (0.1, 1.5),
}


@dataclass(frozen=True, slots=True)
class AudioSettings:
    """音频参数。由 Java 侧在建链时整份下发，sidecar 不持有配置文件。"""

    input_device: str = ""
    output_device: str = ""
    input_gain: float = 1.0
    vad_threshold: float = 0.28
    silence_duration_ms: int = 620
    prefix_padding_ms: int = 300
    semantic_vad: bool = True
    auto_gain: bool = True
    learned_gain: float = 1.0
    playback_buffer_ms: int = 240

    @classmethod
    def parse(cls, raw: dict[str, Any]) -> AudioSettings:
        return cls(
            input_device=str(raw.get("input_device") or ""),
            output_device=str(raw.get("output_device") or ""),
            input_gain=_number(raw, "input_gain", 1.0),
            vad_threshold=_number(raw, "vad_threshold", 0.28),
            silence_duration_ms=int(_number(raw, "silence_duration_ms", 620)),
            prefix_padding_ms=int(_number(raw, "prefix_padding_ms", 300)),
            semantic_vad=bool(raw.get("semantic_vad", True)),
            auto_gain=bool(raw.get("auto_gain", True)),
            learned_gain=_number(raw, "learned_gain", 1.0),
            playback_buffer_ms=int(_number(raw, "playback_buffer_ms", 240)),
        )


@dataclass(frozen=True, slots=True)
class RealtimeProvider:
    """实时语音接入点。目录留在 Java 侧（设置页要渲染它），这里只接收选中的那一份。"""

    key: str
    ws_url: str
    model: str
    voice: str
    temperature: float
    input_sample_rate: int
    output_sample_rate: int
    audio_format: str
    supports_semantic_vad: bool
    api_key: str

    @classmethod
    def parse(cls, raw: dict[str, Any]) -> RealtimeProvider:
        return cls(
            key=str(raw.get("key") or ""),
            ws_url=str(raw.get("ws_url") or ""),
            model=str(raw.get("model") or ""),
            voice=str(raw.get("voice") or ""),
            temperature=_number(raw, "temperature", 0.85),
            input_sample_rate=int(_number(raw, "input_sample_rate", 16000)),
            output_sample_rate=int(_number(raw, "output_sample_rate", 24000)),
            audio_format=str(raw.get("audio_format") or "pcm16"),
            supports_semantic_vad=bool(raw.get("supports_semantic_vad", False)),
            api_key=str(raw.get("api_key") or ""),
        )


def _number(raw: dict[str, Any], key: str, default: float) -> float:
    try:
        value = float(raw[key])
    except (KeyError, TypeError, ValueError):
        return default
    low, high = _CLAMPS.get(key, (float("-inf"), float("inf")))
    return min(high, max(low, value))
