from __future__ import annotations


class VoiceError(Exception):
    """sidecar 内部错误的根。跨进程后只剩一条 error 事件，类名用于区分致命与否。"""


class AudioDeviceError(VoiceError):
    """麦克风或扬声器打不开。换设备、关掉占用它的程序才能恢复。"""


class RealtimeError(VoiceError):
    """实时语音服务的建链或协议错误。"""


class RealtimeClosedError(RealtimeError):
    """连接已经不可用。上行循环据此安静退出，不当成异常上报。"""
