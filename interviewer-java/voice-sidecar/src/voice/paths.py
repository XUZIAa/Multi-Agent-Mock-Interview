from __future__ import annotations

import os
import sys
from pathlib import Path

_ENV_DATA_ROOT = "INTERVIEWER_DATA_ROOT"
_APP_DIR_NAME = "Interviewer"


def data_root() -> Path:
    """数据目录。

    正常由父进程通过环境变量指定——录音路径要回传给 Java 侧写入数据库，两边各算一遍
    会在用户改过数据目录后指向两个地方。单独运行 sidecar 调试时按平台规则自行推导，
    规则与 Java 侧的 AppPaths 保持一致。
    """
    raw = os.environ.get(_ENV_DATA_ROOT, "").strip()
    root = Path(raw) if raw else _platform_root()
    root.mkdir(parents=True, exist_ok=True)
    return root


def _platform_root() -> Path:
    if sys.platform == "win32":
        local = os.environ.get("LOCALAPPDATA", "").strip()
        base = Path(local) if local else Path.home() / "AppData" / "Local"
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        xdg = os.environ.get("XDG_DATA_HOME", "").strip()
        base = Path(xdg) if xdg else Path.home() / ".local" / "share"
    return base / _APP_DIR_NAME


def audio_dir() -> Path:
    path = data_root() / "audio"
    path.mkdir(parents=True, exist_ok=True)
    return path


def log_dir() -> Path:
    path = data_root() / "logs"
    path.mkdir(parents=True, exist_ok=True)
    return path
