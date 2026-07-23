from __future__ import annotations

import json
import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


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

    def add_work_entry(self, title: str, desc: str = "", project: str = "", minutes: int = 0) -> dict[str, Any]:
        data = self.worklog()
        entry = {
            "id": str(uuid.uuid4()),
            "ts": now_iso(),
            "title": title,
            "desc": desc,
            "project": project,
            "minutes": int(minutes or 0),
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
        self.save_todos(data)
        return todo

    def remember(self, text: str, tags: list[str] | None = None) -> dict[str, Any]:
        data = self.memory()
        note = {"id": str(uuid.uuid4()), "text": text, "tags": tags or [], "created": now_iso()}
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
    return JsonStorage(agent_dir, config, drive)
