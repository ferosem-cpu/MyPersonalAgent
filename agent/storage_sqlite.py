from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Any

from storage import JsonStorage, _write_json, now_iso


SCHEMA = """
CREATE TABLE IF NOT EXISTS entries (
  id TEXT PRIMARY KEY,
  ts TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  desc TEXT NOT NULL DEFAULT '',
  project TEXT NOT NULL DEFAULT '',
  minutes INTEGER NOT NULL DEFAULT 0,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS todos (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  project TEXT NOT NULL DEFAULT '',
  due TEXT,
  recurrence TEXT,
  remind_before_min INTEGER NOT NULL DEFAULT 30,
  status TEXT NOT NULL DEFAULT 'open',
  snooze_until TEXT,
  created TEXT NOT NULL,
  completed TEXT,
  last_reminded TEXT,
  escalation_step INTEGER NOT NULL DEFAULT 0,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS notes (
  id TEXT PRIMARY KEY,
  text TEXT NOT NULL,
  tags TEXT NOT NULL DEFAULT '[]',
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS contacts (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  first_name TEXT, last_name TEXT,
  phone_number TEXT, email TEXT, telegram_user_id TEXT,
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_entries_updated ON entries(updated);
CREATE INDEX IF NOT EXISTS idx_todos_updated ON todos(updated);
CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated);
CREATE INDEX IF NOT EXISTS idx_contacts_updated ON contacts(updated);
"""

_ENTRY_COLS = ["id", "ts", "title", "desc", "project", "minutes", "updated", "deleted"]
_TODO_COLS = [
    "id", "title", "project", "due", "recurrence", "remind_before_min", "status",
    "snooze_until", "created", "completed", "last_reminded", "escalation_step",
    "updated", "deleted",
]
_NOTE_COLS = ["id", "text", "tags", "created", "updated", "deleted"]
_CONTACT_COLS = [
    "id", "name", "first_name", "last_name", "phone_number", "email",
    "telegram_user_id", "created", "updated", "deleted",
]


def _row_to_dict(row: sqlite3.Row, bool_cols: tuple[str, ...] = ("deleted",)) -> dict[str, Any]:
    d = dict(row)
    for col in bool_cols:
        if col in d:
            d[col] = bool(d[col])
    return d


class SqliteStorage(JsonStorage):
    """SQLite is the source of truth; every save also mirrors the same payload
    to the legacy JSON file so tracker/index.html (which reads JSON directly
    via the File System Access API) and Drive sync keep working unchanged."""

    def __init__(self, base_dir: Path, config: dict[str, Any], drive: Any = None):
        super().__init__(base_dir, config, drive)
        self.db_path = base_dir / "data.db"
        self.conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL;")
        self.conn.executescript(SCHEMA)
        self.conn.commit()

    def _write_mirror(self, path_key: str, data: dict[str, Any]) -> None:
        path = self._path(path_key)
        _write_json(path, data)
        self._sync_to_drive(path)

    def worklog(self) -> dict[str, Any]:
        rows = self.conn.execute(f"SELECT {', '.join(_ENTRY_COLS)} FROM entries").fetchall()
        return {"version": 1, "entries": [_row_to_dict(r) for r in rows]}

    def save_worklog(self, data: dict[str, Any]) -> None:
        entries = data.get("entries", [])
        placeholders = ", ".join("?" for _ in _ENTRY_COLS)
        with self.conn:
            for e in entries:
                values = [
                    e.get("id"), e.get("ts", now_iso()), e.get("title", ""), e.get("desc", ""),
                    e.get("project", ""), int(e.get("minutes") or 0), e.get("updated") or now_iso(),
                    int(bool(e.get("deleted"))),
                ]
                self.conn.execute(f"INSERT OR REPLACE INTO entries ({', '.join(_ENTRY_COLS)}) VALUES ({placeholders})", values)
        self._write_mirror("tracker_json", self.worklog())

    def todos(self) -> dict[str, Any]:
        rows = self.conn.execute(f"SELECT {', '.join(_TODO_COLS)} FROM todos").fetchall()
        return {"version": 1, "todos": [_row_to_dict(r) for r in rows]}

    def save_todos(self, data: dict[str, Any]) -> None:
        todos = data.get("todos", [])
        placeholders = ", ".join("?" for _ in _TODO_COLS)
        with self.conn:
            for t in todos:
                values = [
                    t.get("id"), t.get("title", ""), t.get("project", ""), t.get("due"),
                    t.get("recurrence"), int(t.get("remind_before_min") or 30), t.get("status", "open"),
                    t.get("snooze_until"), t.get("created") or now_iso(), t.get("completed"),
                    t.get("last_reminded"), int(t.get("escalation_step") or 0),
                    t.get("updated") or now_iso(), int(bool(t.get("deleted"))),
                ]
                self.conn.execute(f"INSERT OR REPLACE INTO todos ({', '.join(_TODO_COLS)}) VALUES ({placeholders})", values)
        self._write_mirror("todos_json", self.todos())

    def memory(self) -> dict[str, Any]:
        rows = self.conn.execute(f"SELECT {', '.join(_NOTE_COLS)} FROM notes").fetchall()
        notes = []
        for r in rows:
            d = _row_to_dict(r)
            d["tags"] = json.loads(d.get("tags") or "[]")
            notes.append(d)
        return {"version": 1, "notes": notes}

    def save_memory(self, data: dict[str, Any]) -> None:
        notes = data.get("notes", [])
        placeholders = ", ".join("?" for _ in _NOTE_COLS)
        with self.conn:
            for n in notes:
                values = [
                    n.get("id"), n.get("text", ""), json.dumps(n.get("tags") or [], ensure_ascii=False),
                    n.get("created") or now_iso(), n.get("updated") or now_iso(), int(bool(n.get("deleted"))),
                ]
                self.conn.execute(f"INSERT OR REPLACE INTO notes ({', '.join(_NOTE_COLS)}) VALUES ({placeholders})", values)
        self._write_mirror("memory_json", self.memory())

    def contacts(self) -> dict[str, Any]:
        rows = self.conn.execute(f"SELECT {', '.join(_CONTACT_COLS)} FROM contacts").fetchall()
        return {"version": 1, "contacts": [_row_to_dict(r) for r in rows]}

    def save_contacts(self, data: dict[str, Any]) -> None:
        contacts = data.get("contacts", [])
        placeholders = ", ".join("?" for _ in _CONTACT_COLS)
        with self.conn:
            for c in contacts:
                values = [
                    c.get("id"), c.get("name", ""), c.get("first_name"), c.get("last_name"),
                    c.get("phone_number"), c.get("email"), c.get("telegram_user_id"),
                    c.get("created") or now_iso(), c.get("updated") or now_iso(), int(bool(c.get("deleted"))),
                ]
                self.conn.execute(f"INSERT OR REPLACE INTO contacts ({', '.join(_CONTACT_COLS)}) VALUES ({placeholders})", values)
        self._write_mirror("contacts_json", self.contacts())
