from __future__ import annotations

import os
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any

import requests


ESCALATION_MINUTES = [15, 60, 180, 1440]


def parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone()


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


class ReminderScheduler:
    def __init__(self, config: dict[str, Any], storage: Any):
        self.config = config
        self.storage = storage
        self.stop_event = threading.Event()
        self.thread: threading.Thread | None = None
        self.telegram_token = os.getenv("TELEGRAM_BOT_TOKEN")
        self.telegram_chat_id = os.getenv("TELEGRAM_CHAT_ID")

    def start(self) -> None:
        if self.thread and self.thread.is_alive():
            return
        self.thread = threading.Thread(target=self.run, name="ReminderScheduler", daemon=True)
        self.thread.start()

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        scan_seconds = int(self.config.get("scheduler", {}).get("scan_seconds", 60))
        while not self.stop_event.wait(scan_seconds):
            try:
                self.scan_once()
            except Exception as exc:
                print(f"Scheduler error: {exc}")

    def scan_once(self) -> None:
        data = self.storage.todos()
        changed = False
        current = datetime.now().astimezone()
        for todo in data.get("todos", []):
            if todo.get("deleted"):
                continue
            if todo.get("status") == "done":
                continue
            target = parse_iso(todo.get("snooze_until")) or parse_iso(todo.get("due"))
            if not target:
                continue
            remind_at = target - timedelta(minutes=int(todo.get("remind_before_min") or 0))
            last_reminded = parse_iso(todo.get("last_reminded"))
            step = int(todo.get("escalation_step") or 0)
            next_allowed = remind_at if not last_reminded else last_reminded + timedelta(minutes=ESCALATION_MINUTES[min(step, len(ESCALATION_MINUTES) - 1)])
            if current >= next_allowed:
                self.notify(todo)
                todo["last_reminded"] = now_iso()
                todo["escalation_step"] = min(step + 1, len(ESCALATION_MINUTES) - 1)
                changed = True
        if changed:
            self.storage.save_todos(data)

    def notify(self, todo: dict[str, Any]) -> None:
        message = f"Reminder: {todo.get('title')}\nProject: {todo.get('project') or 'Unassigned'}\nDue: {todo.get('due')}"
        self.desktop_notify("Personal Agent Reminder", message)
        self.telegram_send(message)

    def desktop_notify(self, title: str, message: str) -> None:
        try:
            from plyer import notification

            notification.notify(title=title, message=message, timeout=12)
        except Exception:
            print(f"{title}: {message}")

    def telegram_send(self, message: str) -> bool:
        if not self.telegram_token or not self.telegram_chat_id:
            return False
        url = f"https://api.telegram.org/bot{self.telegram_token}/sendMessage"
        response = requests.post(url, json={"chat_id": self.telegram_chat_id, "text": message}, timeout=15)
        return response.ok
