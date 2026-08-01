from __future__ import annotations

import mimetypes
import os
import re
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

import requests


class TelegramBot:
    def __init__(
        self,
        storage: Any,
        agent_reply: Callable[[str], str] | None = None,
        llm: Any = None,
        drive: Any = None,
        uploads_dir: Path | None = None,
    ):
        self.storage = storage
        self.agent_reply = agent_reply
        self.llm = llm
        self.drive = drive
        self.uploads_dir = uploads_dir
        self.token = os.getenv("TELEGRAM_BOT_TOKEN")
        self.chat_id = os.getenv("TELEGRAM_CHAT_ID")
        self.offset = 0

    def run_forever(self) -> None:
        if not self.token:
            raise RuntimeError("TELEGRAM_BOT_TOKEN is missing.")
        while True:
            try:
                for update in self.get_updates():
                    self.handle_update(update)
            except requests.RequestException as exc:
                print(f"Telegram polling error: {exc}")
                time.sleep(5)
                continue
            time.sleep(2)

    def get_updates(self) -> list[dict[str, Any]]:
        url = f"https://api.telegram.org/bot{self.token}/getUpdates"
        response = requests.get(url, params={"offset": self.offset, "timeout": 20}, timeout=30)
        response.raise_for_status()
        updates = response.json().get("result", [])
        if updates:
            self.offset = updates[-1]["update_id"] + 1
        return updates

    def handle_update(self, update: dict[str, Any]) -> None:
        message = update.get("message") or {}
        chat_id = str(message.get("chat", {}).get("id", ""))
        if not chat_id:
            return
        try:
            contact_data = message.get("contact")
            if contact_data:
                self._handle_contact(chat_id, contact_data)
                return
            file_id, suggested_name = self._extract_attachment(message)
            if file_id:
                self._handle_attachment(chat_id, file_id, suggested_name)
                return
            text = (message.get("text") or "").strip()
            if text:
                if self._looks_like_export_contacts_request(text):
                    self._handle_export_contacts(chat_id)
                    return
                if self._looks_like_contact_message(text):
                    self._handle_text_contact(chat_id, text)
                    return
                self._dispatch(chat_id, text)
        except Exception as exc:
            self.send(chat_id, f"Error: {exc}")

    def _handle_contact(self, chat_id: str, contact_data: dict[str, Any]) -> None:
        contact = self.storage.add_contact(
            name=" ".join(part for part in [contact_data.get("first_name"), contact_data.get("last_name")] if part).strip()
            or "Unknown",
            phone_number=contact_data.get("phone_number"),
            first_name=contact_data.get("first_name"),
            last_name=contact_data.get("last_name"),
            telegram_user_id=contact_data.get("user_id"),
        )
        self._send_contact_vcf(chat_id, contact)

    _PHONE_RE = re.compile(r"\+?\d[\d\s-]{4,}\d")

    def _handle_text_contact(self, chat_id: str, text: str) -> None:
        name = None
        phone = None
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            if stripped.lower().startswith("name:"):
                name = stripped.split(":", 1)[1].strip()
                continue
            if stripped.lower().startswith(("phone:", "number:")):
                phone = stripped.split(":", 1)[1].strip()
                continue
            match = self._PHONE_RE.search(stripped)
            if not match:
                continue
            if not phone:
                phone = match.group(0).strip()
            if not name:
                prefix = stripped[: match.start()].strip(" -:,")
                if prefix:
                    name = prefix
        if not phone:
            match = self._PHONE_RE.search(text)
            phone = match.group(0).strip() if match else None
        if not name:
            name = "Contact"
        contact = self.storage.add_contact(name=name, phone_number=phone)
        self._send_contact_vcf(chat_id, contact)

    def _send_contact_vcf(self, chat_id: str, contact: dict[str, Any]) -> None:
        try:
            vcf_path = self.storage.save_contact_vcf(contact)
            with vcf_path.open("rb") as handle:
                requests.post(
                    f"https://api.telegram.org/bot{self.token}/sendDocument",
                    data={"chat_id": chat_id, "caption": f"Saved contact: {contact['name']}"},
                    files={"document": (vcf_path.name, handle, "text/vcard")},
                    timeout=30,
                )
        except Exception as exc:
            self.send(chat_id, f"Saved contact but could not send the .vcf file: {exc}")

    @staticmethod
    def _looks_like_contact_message(text: str) -> bool:
        lower = text.lower()
        return any(marker in lower for marker in ("contact", "phone:", "number:", "name:", "vcf")) and re.search(r"\b(?:\+?\d[\d\s-]{4,})\b", text) is not None

    @staticmethod
    def _looks_like_export_contacts_request(text: str) -> bool:
        lower = text.lower()
        if "contact" not in lower:
            return False
        if "export" in lower:
            return True
        return ("all" in lower or "every" in lower) and ("vcf" in lower or "file" in lower)

    def _handle_export_contacts(self, chat_id: str) -> None:
        contacts = self.storage.contacts().get("contacts", [])
        if not contacts:
            self.send(chat_id, "You don't have any saved contacts yet.")
            return
        content = self.storage.all_contacts_vcf()
        try:
            requests.post(
                f"https://api.telegram.org/bot{self.token}/sendDocument",
                data={"chat_id": chat_id, "caption": f"All {len(contacts)} contacts"},
                files={"document": ("contacts.vcf", content.encode("utf-8"), "text/vcard")},
                timeout=30,
            )
        except Exception as exc:
            self.send(chat_id, f"Could not send the combined contacts file: {exc}")

    @staticmethod
    def _extract_attachment(message: dict[str, Any]) -> tuple[str | None, str | None]:
        """Return (file_id, suggested_filename) for the first attachment found, if any."""
        if "document" in message:
            doc = message["document"]
            return doc["file_id"], doc.get("file_name")
        if "photo" in message and message["photo"]:
            largest = max(message["photo"], key=lambda p: p.get("file_size", 0))
            return largest["file_id"], None
        if "video" in message:
            video = message["video"]
            return video["file_id"], video.get("file_name")
        if "audio" in message:
            audio = message["audio"]
            return audio["file_id"], audio.get("file_name")
        if "voice" in message:
            return message["voice"]["file_id"], None
        return None, None

    def _handle_attachment(self, chat_id: str, file_id: str, suggested_name: str | None) -> None:
        info = requests.get(
            f"https://api.telegram.org/bot{self.token}/getFile",
            params={"file_id": file_id},
            timeout=30,
        )
        info.raise_for_status()
        file_path = info.json()["result"]["file_path"]

        content = requests.get(f"https://api.telegram.org/file/bot{self.token}/{file_path}", timeout=60)
        content.raise_for_status()
        data = content.content

        filename = suggested_name or Path(file_path).name
        if "." not in filename:
            ext = mimetypes.guess_extension(content.headers.get("content-type", "")) or ""
            filename = filename + ext

        from drive_sync import classify_category
        category = classify_category(filename)

        saved_where = []
        if self.uploads_dir:
            dest_dir = self.uploads_dir / category
            dest_dir.mkdir(parents=True, exist_ok=True)
            (dest_dir / filename).write_bytes(data)
            saved_where.append(f"local:{category}/{filename}")

        if self.drive:
            try:
                self.drive.upload_attachment(data, filename)
                saved_where.append(f"Drive:{category}/{filename}")
            except Exception as exc:
                saved_where.append(f"Drive upload failed ({exc})")

        if not saved_where:
            self.send(chat_id, f"Received {filename} but no storage is configured to save it.")
        else:
            self.send(chat_id, f"Saved {filename} -> {category}\n" + "\n".join(saved_where))

    def _dispatch(self, chat_id: str, text: str) -> None:
        if text == "/start":
            self.send(chat_id, f"Connected. Add this to .env as TELEGRAM_CHAT_ID={chat_id}")
            return
        self.chat_id = self.chat_id or chat_id
        lower = text.lower()
        if lower.startswith("/provider"):
            self._handle_provider_command(chat_id, text[len("/provider"):].strip())
        elif lower.startswith("done"):
            todo = self.storage.complete_todo(text[4:].strip())
            self.send(chat_id, f"Done: {todo['title']}" if todo else "I could not find that open to-do.")
        elif lower.startswith("snooze"):
            until = (datetime.now(timezone.utc).astimezone() + timedelta(hours=2)).isoformat(timespec="seconds")
            todo = self.storage.snooze_todo("", until)
            self.send(chat_id, f"Snoozed: {todo['title']}" if todo else "I could not find a to-do to snooze.")
        elif lower in ("list", "todos", "list todos", "show todos", "my todos"):
            self._send_todo_list(chat_id)
        elif lower.startswith("todo "):
            due = self._rough_due(text)
            todo = self.storage.add_todo(text[5:].strip(), due=due)
            self.send(chat_id, f"Added to-do: {todo['title']}")
        elif lower.startswith("log "):
            entry = self.storage.add_work_entry(text[4:].strip())
            self.send(chat_id, f"Logged: {entry['title']}")
        elif lower.startswith("remember "):
            note = self.storage.remember(text[9:].strip(), [])
            self.send(chat_id, f"Remembered: {note['text']}")
        elif lower.startswith("recall "):
            notes = self.storage.recall(text[7:].strip())
            self.send(chat_id, "\n\n".join(n["text"] for n in notes) or "No matching memory found.")
        elif self.agent_reply:
            self.send(chat_id, self.agent_reply(text))
        else:
            self.send(chat_id, "Agent reply is not attached in this process.")

    def _send_todo_list(self, chat_id: str) -> None:
        todos = self.storage.todos().get("todos", [])
        open_todos = [t for t in todos if t.get("status") != "done" and not t.get("deleted")]
        if not open_todos:
            self.send(chat_id, "No open to-dos.")
            return
        open_todos.sort(key=lambda t: t.get("snooze_until") or t.get("due") or "")
        lines = [f"- {t['title']} (due {t.get('due', '?')})" for t in open_todos]
        self.send(chat_id, "Open to-dos:\n" + "\n".join(lines))

    def _handle_provider_command(self, chat_id: str, arg: str) -> None:
        if not self.llm:
            self.send(chat_id, "Provider switching isn't available in this session.")
            return
        if not arg:
            self.send(chat_id, f"Current: {self.llm.get_status()}\nUse /provider <name> or /provider auto (openrouter, anthropic, openai, google, grok, nvidia).")
            return
        if arg.lower() == "auto":
            self.llm.set_manual_provider(None)
            self.send(chat_id, f"Switched to auto-fallback. {self.llm.get_status()}")
            return
        try:
            self.llm.set_manual_provider(arg.lower())
            self.send(chat_id, f"Switched. {self.llm.get_status()}")
        except Exception as exc:
            self.send(chat_id, f"Could not switch to '{arg}': {exc}")

    def send(self, chat_id: str, text: str) -> None:
        if not self.token:
            return
        requests.post(
            f"https://api.telegram.org/bot{self.token}/sendMessage",
            json={"chat_id": chat_id, "text": text},
            timeout=15,
        )

    @staticmethod
    def _rough_due(text: str) -> str:
        current = datetime.now(timezone.utc).astimezone()
        if "tomorrow" in text.lower():
            current += timedelta(days=1)
        match = re.search(r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)?", text, re.I)
        if match:
            hour = int(match.group(1))
            minute = int(match.group(2) or 0)
            suffix = (match.group(3) or "").lower()
            if suffix == "pm" and hour < 12:
                hour += 12
            if suffix == "am" and hour == 12:
                hour = 0
            current = current.replace(hour=hour, minute=minute, second=0, microsecond=0)
        return current.isoformat(timespec="seconds")
