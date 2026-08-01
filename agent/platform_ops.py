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


def open_url(url: str) -> str:
    """Open a URL in the default browser (PLAN_V2 Task 6.2) - same OS-level launch
    mechanism as open_app, just named separately since a bare URL isn't an app name."""
    if "://" not in url:
        url = f"https://{url}"
    system = platform.system().lower()
    if system == "windows":
        os.startfile(url)  # type: ignore[attr-defined]
    elif system == "darwin":
        subprocess.Popen(["open", url])
    else:
        subprocess.Popen(["xdg-open", url])
    return f"Opened {url}"


def desktop_dir() -> Path:
    return Path.home() / "Desktop"
