from __future__ import annotations

import json
import os
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def with_sync_fields(item: dict[str, Any]) -> dict[str, Any]:
    """Ensure sync metadata exists. Non-destructive; safe on legacy records."""
    item.setdefault("updated", item.get("created") or item.get("ts") or now_iso())
    item.setdefault("deleted", False)
    return item


def _updated_dt(value: str) -> datetime:
    """Parse an `updated` ISO timestamp (any UTC offset, or 'Z') into a UTC-aware
    datetime so chronological comparisons are correct regardless of which
    timezone the writer's clock was in (client and server clocks can differ)."""
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def _read_json(path: Path, default: dict[str, Any]) -> dict[str, Any]:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        _write_json(path, default)
        return default.copy()
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


@dataclass
class JsonStorage:
    base_dir: Path
    config: dict[str, Any]
    drive: Any = None

    def _path(self, key: str) -> Path:
        configured = self.config[key]
        p = Path(os.path.expanduser(configured))
        if not p.is_absolute():
            p = self.base_dir / p
        return p.resolve()

    def _sync_to_drive(self, path: Path) -> None:
        if not self.drive:
            return
        try:
            self.drive.sync_data_file(path)
        except Exception as exc:
            print(f"Drive sync skipped for {path.name}: {exc}")

    def worklog(self) -> dict[str, Any]:
        return _read_json(self._path("tracker_json"), {"version": 1, "entries": []})

    def save_worklog(self, data: dict[str, Any]) -> None:
        _write_json(self._path("tracker_json"), data)
        self._sync_to_drive(self._path("tracker_json"))

    def todos(self) -> dict[str, Any]:
        return _read_json(self._path("todos_json"), {"version": 1, "todos": []})

    def save_todos(self, data: dict[str, Any]) -> None:
        _write_json(self._path("todos_json"), data)
        self._sync_to_drive(self._path("todos_json"))

    def memory(self) -> dict[str, Any]:
        return _read_json(self._path("memory_json"), {"version": 1, "notes": []})

    def save_memory(self, data: dict[str, Any]) -> None:
        _write_json(self._path("memory_json"), data)
        self._sync_to_drive(self._path("memory_json"))

    def contacts(self) -> dict[str, Any]:
        return _read_json(self._path("contacts_json"), {"version": 1, "contacts": []})

    def save_contacts(self, data: dict[str, Any]) -> None:
        _write_json(self._path("contacts_json"), data)
        self._sync_to_drive(self._path("contacts_json"))

    def build_vcf(self, contact: dict[str, Any]) -> str:
        name = contact.get("name") or "Unknown"
        phone = contact.get("phone_number") or ""
        email = contact.get("email") or ""
        lines = [
            "BEGIN:VCARD",
            "VERSION:3.0",
            f"FN:{name}",
            f"N:{name};;;",
        ]
        if phone:
            lines.append(f"TEL;TYPE=CELL:{phone}")
        if email:
            lines.append(f"EMAIL:{email}")
        lines.append("END:VCARD")
        return "\n".join(lines) + "\n"

    def all_contacts_vcf(self) -> str:
        contacts = self.contacts().get("contacts", [])
        return "".join(self.build_vcf(c) for c in contacts)

    def save_contact_vcf(self, contact: dict[str, Any]) -> Path:
        contacts_dir = self._path("contacts_json").parent / "contacts"
        contacts_dir.mkdir(parents=True, exist_ok=True)
        safe_name = re.sub(r"[^A-Za-z0-9._-]+", "_", contact.get("name") or "contact").strip("._") or "contact"
        digits = re.sub(r"\D", "", contact.get("phone_number") or "")
        filename = f"{safe_name}_{digits[-4:]}.vcf" if digits else f"{safe_name}.vcf"
        path = contacts_dir / filename
        path.write_text(self.build_vcf(contact), encoding="utf-8")
        return path

    def add_contact(
        self,
        name: str,
        *,
        phone_number: str | None = None,
        email: str | None = None,
        telegram_user_id: str | int | None = None,
        first_name: str | None = None,
        last_name: str | None = None,
        whatsapp_number: str | None = None,
        email_accounts_note: str | None = None,
    ) -> dict[str, Any]:
        data = self.contacts()
        contacts = data.setdefault("contacts", [])

        existing = None
        for contact in contacts:
            if phone_number and contact.get("phone_number") == phone_number:
                existing = contact
                break
            if telegram_user_id is not None and str(contact.get("telegram_user_id")) == str(telegram_user_id):
                existing = contact
                break

        if existing is None:
            contact = {
                "id": str(uuid.uuid4()),
                "name": name or " ".join(part for part in [first_name, last_name] if part).strip() or "Unknown",
                "first_name": first_name or None,
                "last_name": last_name or None,
                "phone_number": phone_number or None,
                "email": email or None,
                "telegram_user_id": str(telegram_user_id) if telegram_user_id is not None else None,
                "whatsapp_number": whatsapp_number or None,
                "email_accounts_note": email_accounts_note or None,
                "created": now_iso(),
                "updated": now_iso(),
                "deleted": False,
            }
            contacts.append(contact)
            self.save_contacts(data)
            self.save_contact_vcf(contact)
            return contact

        existing.setdefault("name", name or "Unknown")
        existing["first_name"] = first_name or existing.get("first_name")
        existing["last_name"] = last_name or existing.get("last_name")
        existing["phone_number"] = phone_number or existing.get("phone_number")
        existing["email"] = email or existing.get("email")
        existing["telegram_user_id"] = str(telegram_user_id) if telegram_user_id is not None else existing.get("telegram_user_id")
        existing["whatsapp_number"] = whatsapp_number or existing.get("whatsapp_number")
        existing["email_accounts_note"] = email_accounts_note or existing.get("email_accounts_note")
        existing["updated"] = now_iso()
        existing.setdefault("deleted", False)
        self.save_contacts(data)
        self.save_contact_vcf(existing)
        return existing

    def add_work_entry(self, title: str, desc: str = "", project: str = "", minutes: int = 0) -> dict[str, Any]:
        data = self.worklog()
        entry = {
            "id": str(uuid.uuid4()),
            "ts": now_iso(),
            "title": title,
            "desc": desc,
            "project": project,
            "minutes": int(minutes or 0),
            "updated": now_iso(),
            "deleted": False,
        }
        data.setdefault("entries", []).append(entry)
        self.save_worklog(data)
        return entry

    def add_todo(
        self,
        title: str,
        project: str = "",
        due: str | None = None,
        recurrence: str | None = None,
        remind_before_min: int = 30,
    ) -> dict[str, Any]:
        data = self.todos()
        todo = {
            "id": str(uuid.uuid4()),
            "title": title,
            "project": project,
            "due": due or now_iso(),
            "recurrence": recurrence,
            "remind_before_min": int(remind_before_min or 30),
            "status": "open",
            "snooze_until": None,
            "created": now_iso(),
            "completed": None,
            "last_reminded": None,
            "escalation_step": 0,
            "updated": now_iso(),
            "deleted": False,
        }
        data.setdefault("todos", []).append(todo)
        self.save_todos(data)
        return todo

    def complete_todo(self, todo_id_or_words: str) -> dict[str, Any] | None:
        data = self.todos()
        todo = self._match_todo(data.get("todos", []), todo_id_or_words)
        if not todo:
            return None
        todo["status"] = "done"
        todo["completed"] = now_iso()
        todo["updated"] = now_iso()
        self.save_todos(data)
        self.add_work_entry(f"Completed: {todo['title']}", "Auto-logged from to-do completion.", todo.get("project", ""), 0)
        return todo

    def snooze_todo(self, todo_id_or_words: str, until_iso: str) -> dict[str, Any] | None:
        data = self.todos()
        todo = self._match_todo(data.get("todos", []), todo_id_or_words)
        if not todo:
            return None
        todo["status"] = "snoozed"
        todo["snooze_until"] = until_iso
        todo["updated"] = now_iso()
        self.save_todos(data)
        return todo

    def remember(self, text: str, tags: list[str] | None = None) -> dict[str, Any]:
        data = self.memory()
        note = {
            "id": str(uuid.uuid4()),
            "text": text,
            "tags": tags or [],
            "created": now_iso(),
            "updated": now_iso(),
            "deleted": False,
        }
        data.setdefault("notes", []).append(note)
        self.save_memory(data)
        return note

    def recall(self, query: str, limit: int = 8) -> list[dict[str, Any]]:
        words = {w.lower() for w in query.split() if w.strip()}
        notes = self.memory().get("notes", [])
        scored: list[tuple[int, dict[str, Any]]] = []
        for note in notes:
            haystack = f"{note.get('text', '')} {' '.join(note.get('tags', []))}".lower()
            score = sum(1 for w in words if w in haystack)
            if score:
                scored.append((score, note))
        return [n for _, n in sorted(scored, key=lambda x: x[0], reverse=True)[:limit]]

    @staticmethod
    def _match_todo(todos: list[dict[str, Any]], query: str) -> dict[str, Any] | None:
        if not todos:
            return None
        for todo in todos:
            if todo.get("id") == query:
                return todo
        q = query.lower()
        open_todos = [t for t in todos if t.get("status") != "done"]
        for todo in open_todos:
            if q in todo.get("title", "").lower():
                return todo
        terms = {x for x in q.split() if x}
        ranked = sorted(open_todos, key=lambda t: len(terms & set(t.get("title", "").lower().split())), reverse=True)
        return ranked[0] if ranked and terms & set(ranked[0].get("title", "").lower().split()) else None

    _COLLECTIONS = {
        "entries": ("tracker_json", "worklog", "save_worklog", "entries"),
        "todos": ("todos_json", "todos", "save_todos", "todos"),
        "notes": ("memory_json", "memory", "save_memory", "notes"),
        "contacts": ("contacts_json", "contacts", "save_contacts", "contacts"),
    }

    def list_items(self, collection: str, since: str | None = None, include_deleted: bool = False) -> list[dict[str, Any]]:
        _, reader, _, key = self._COLLECTIONS[collection]
        items = [with_sync_fields(i) for i in getattr(self, reader)().get(key, [])]
        if since:
            since_dt = _updated_dt(since)
            items = [i for i in items if _updated_dt(i["updated"]) > since_dt]
        if not include_deleted:
            items = [i for i in items if not i.get("deleted")]
        return items

    def upsert_item(self, collection: str, item: dict[str, Any]) -> dict[str, Any]:
        """Last-write-wins upsert by id + updated timestamp. Returns the winner."""
        _, reader, saver, key = self._COLLECTIONS[collection]
        data = getattr(self, reader)()
        items = data.setdefault(key, [])
        item = with_sync_fields(dict(item))
        item.setdefault("id", str(uuid.uuid4()))
        for i, existing in enumerate(items):
            if existing.get("id") == item["id"]:
                existing = with_sync_fields(existing)
                if _updated_dt(existing["updated"]) >= _updated_dt(item["updated"]):
                    return existing
                items[i] = item
                getattr(self, saver)(data)
                return item
        items.append(item)
        getattr(self, saver)(data)
        return item

    def soft_delete_item(self, collection: str, item_id: str) -> dict[str, Any] | None:
        _, reader, saver, key = self._COLLECTIONS[collection]
        data = getattr(self, reader)()
        for existing in data.get(key, []):
            if existing.get("id") == item_id:
                existing["deleted"] = True
                existing["updated"] = now_iso()
                getattr(self, saver)(data)
                return existing
        return None


class FirestoreStorage(JsonStorage):
    def __init__(self, base_dir: Path, config: dict[str, Any]):
        super().__init__(base_dir, config)
        from google.cloud import firestore

        self.client = firestore.Client(project=os.getenv("FIRESTORE_PROJECT_ID") or None)

    def _collection_as_doc(self, collection: str, key: str) -> dict[str, Any]:
        docs = [doc.to_dict() | {"id": doc.id} for doc in self.client.collection(collection).stream()]
        return {"version": 1, key: docs}

    def _save_collection(self, collection: str, key: str, data: dict[str, Any]) -> None:
        batch = self.client.batch()
        for item in data.get(key, []):
            doc = self.client.collection(collection).document(item["id"])
            batch.set(doc, item)
        batch.commit()

    def worklog(self) -> dict[str, Any]:
        return self._collection_as_doc("entries", "entries")

    def save_worklog(self, data: dict[str, Any]) -> None:
        self._save_collection("entries", "entries", data)

    def todos(self) -> dict[str, Any]:
        return self._collection_as_doc("todos", "todos")

    def save_todos(self, data: dict[str, Any]) -> None:
        self._save_collection("todos", "todos", data)

    def memory(self) -> dict[str, Any]:
        return self._collection_as_doc("memory", "notes")

    def save_memory(self, data: dict[str, Any]) -> None:
        self._save_collection("memory", "notes", data)


def load_config(agent_dir: Path) -> dict[str, Any]:
    return json.loads((agent_dir / "config.json").read_text(encoding="utf-8"))


def make_storage(agent_dir: Path, config: dict[str, Any]) -> JsonStorage:
    drive = None
    if config.get("google_drive", {}).get("enabled"):
        from drive_sync import DriveSync

        drive = DriveSync(agent_dir, config)
    if config.get("storage") == "firestore":
        return FirestoreStorage(agent_dir, config)
    if config.get("storage") == "sqlite":
        from storage_sqlite import SqliteStorage

        return SqliteStorage(agent_dir, config, drive)
    return JsonStorage(agent_dir, config, drive)
