from __future__ import annotations

import os
import platform
import subprocess
from pathlib import Path


def open_app(name_or_path: str) -> str:
    system = platform.system().lower()
    if system == "windows":
        os.startfile(name_or_path)  # type: ignore[attr-defined]
    elif system == "darwin":
        subprocess.Popen(["open", name_or_path])
    else:
        subprocess.Popen(["xdg-open", name_or_path])
    return f"Opened {name_or_path}"


def desktop_dir() -> Path:
    return Path.home() / "Desktop"
