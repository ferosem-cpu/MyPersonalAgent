from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv
from google.cloud import firestore

from storage import JsonStorage, load_config


AGENT_DIR = Path(__file__).resolve().parent


def upload_collection(client: firestore.Client, collection: str, items: list[dict]) -> None:
    batch = client.batch()
    for item in items:
        doc_id = item["id"]
        batch.set(client.collection(collection).document(doc_id), item, merge=True)
    batch.commit()


def main() -> None:
    load_dotenv(AGENT_DIR / ".env")
    config = load_config(AGENT_DIR)
    local = JsonStorage(AGENT_DIR, config)
    client = firestore.Client(project=os.getenv("FIRESTORE_PROJECT_ID") or None)
    upload_collection(client, "entries", local.worklog().get("entries", []))
    upload_collection(client, "todos", local.todos().get("todos", []))
    upload_collection(client, "memory", local.memory().get("notes", []))
    print("Migration complete. It is safe to re-run this script.")


if __name__ == "__main__":
    main()
