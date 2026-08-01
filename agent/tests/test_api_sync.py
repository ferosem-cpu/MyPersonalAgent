from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

TOKEN = "test-token-sync"


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_API_TOKEN", TOKEN)

    from api.server import create_app, get_storage
    from storage import JsonStorage
    from fastapi.testclient import TestClient

    config = {
        "tracker_json": "worklog.json",
        "todos_json": "todos.json",
        "memory_json": "memory.json",
        "contacts_json": "contacts.json",
    }
    test_storage = JsonStorage(tmp_path, config)

    app = create_app()
    app.dependency_overrides[get_storage] = lambda: test_storage
    return TestClient(app)


def auth_headers():
    return {"X-API-Key": TOKEN}


def test_sync_push_then_pull(client):
    push = client.post(
        "/api/v1/sync",
        json={
            "last_sync": None,
            "changes": {
                "todos": [
                    # Fixed past timestamp so it's unambiguously before whatever
                    # "now" is when this test actually runs.
                    {"id": "t1", "title": "From phone", "updated": "2026-01-01T00:00:00+00:00"},
                ]
            },
        },
        headers=auth_headers(),
    )
    assert push.status_code == 200
    body = push.json()
    assert "t1" in body["applied"].get("todos", [])
    assert any(t["id"] == "t1" for t in body["changes"]["todos"])
    server_time = body["server_time"]

    # Second sync with no new changes, cursor = server_time from last response,
    # should return nothing new (idempotent, matches M2 acceptance test #5).
    second = client.post(
        "/api/v1/sync",
        json={"last_sync": server_time, "changes": {}},
        headers=auth_headers(),
    )
    assert second.status_code == 200
    assert second.json()["changes"]["todos"] == []


def test_sync_conflict_older_client_write_rejected(client):
    # Server already has a newer copy (created directly, not via sync).
    create = client.post("/api/v1/todos", json={"title": "Server version"}, headers=auth_headers())
    todo_id = create.json()["id"]
    server_updated = create.json()["updated"]

    # Client pushes a stale edit with an OLDER updated timestamp.
    older = "2020-01-01T00:00:00+00:00"
    push = client.post(
        "/api/v1/sync",
        json={
            "last_sync": None,
            "changes": {"todos": [{"id": todo_id, "title": "Stale client edit", "updated": older}]},
        },
        headers=auth_headers(),
    )
    body = push.json()
    assert todo_id in [r.get("id") for r in body["rejected"].get("todos", [])]
    rejected_entry = next(r for r in body["rejected"]["todos"] if r["id"] == todo_id)
    assert rejected_entry["server_copy"]["title"] == "Server version"
    assert rejected_entry["server_copy"]["updated"] == server_updated


def test_sync_delete_propagates_as_deleted_true(client):
    create = client.post("/api/v1/todos", json={"title": "To be deleted"}, headers=auth_headers())
    todo_id = create.json()["id"]
    client.delete(f"/api/v1/todos/{todo_id}", headers=auth_headers())

    pulled = client.post("/api/v1/sync", json={"last_sync": None, "changes": {}}, headers=auth_headers())
    match = next(t for t in pulled.json()["changes"]["todos"] if t["id"] == todo_id)
    assert match["deleted"] is True
