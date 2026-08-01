from __future__ import annotations

from pathlib import Path

from storage import JsonStorage, load_config, with_sync_fields
from storage_sqlite import SqliteStorage


AGENT_DIR = Path(__file__).resolve().parent


def migrate_collection(local: JsonStorage, target: SqliteStorage, collection: str) -> int:
    _, reader, saver, key = SqliteStorage._COLLECTIONS[collection]
    items = [with_sync_fields(dict(i)) for i in getattr(local, reader)().get(key, [])]
    count = 0
    for item in items:
        winner = target.upsert_item(collection, item)
        if winner is item or winner.get("updated") == item.get("updated"):
            count += 1
    return count


def main() -> None:
    config = load_config(AGENT_DIR)
    local = JsonStorage(AGENT_DIR, config)
    target = SqliteStorage(AGENT_DIR, config)

    for collection in ("entries", "todos", "notes", "contacts"):
        count = migrate_collection(local, target, collection)
        print(f"{collection}: migrated {count} records")

    print("Migration complete. It is safe to re-run this script.")


if __name__ == "__main__":
    main()
