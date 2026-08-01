from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

TOKEN = "test-token-smoke"


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


def test_health_requires_no_key(client):
    resp = client.get("/api/v1/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_todos_requires_key(client):
    resp = client.get("/api/v1/todos")
    assert resp.status_code == 401

    resp = client.get("/api/v1/todos", headers=auth_headers())
    assert resp.status_code == 200
    assert resp.json() == []


def test_todo_crud_roundtrip(client):
    created = client.post("/api/v1/todos", json={"title": "Call Vivian", "project": "personal"}, headers=auth_headers())
    assert created.status_code == 201
    todo_id = created.json()["id"]

    listed = client.get("/api/v1/todos?status=open", headers=auth_headers()).json()
    assert any(t["id"] == todo_id for t in listed)

    completed = client.post(f"/api/v1/todos/{todo_id}/complete", headers=auth_headers())
    assert completed.status_code == 200
    assert completed.json()["status"] == "done"

    entries = client.get("/api/v1/entries", headers=auth_headers()).json()
    assert any("Call Vivian" in e["title"] for e in entries)

    deleted = client.delete(f"/api/v1/todos/{todo_id}", headers=auth_headers())
    assert deleted.status_code == 200
    assert deleted.json()["deleted"] is True

    listed_all = client.get("/api/v1/todos?status=all", headers=auth_headers()).json()
    assert not any(t["id"] == todo_id for t in listed_all)


def test_entry_crud(client):
    created = client.post("/api/v1/entries", json={"title": "Wrote docs", "minutes": 30}, headers=auth_headers())
    assert created.status_code == 201
    entry_id = created.json()["id"]

    deleted = client.delete(f"/api/v1/entries/{entry_id}", headers=auth_headers())
    assert deleted.status_code == 200


def test_memory_remember_and_recall(client):
    created = client.post("/api/v1/memory", json={"text": "Vivian likes coffee", "tags": ["personal"]}, headers=auth_headers())
    assert created.status_code == 201

    recalled = client.get("/api/v1/memory/recall", params={"q": "coffee"}, headers=auth_headers())
    assert recalled.status_code == 200
    assert any("coffee" in n["text"] for n in recalled.json())


def test_contact_crud(client):
    created = client.post("/api/v1/contacts", json={"name": "Jane Doe", "phone_number": "+123456789"}, headers=auth_headers())
    assert created.status_code == 201
    contact_id = created.json()["id"]

    listed = client.get("/api/v1/contacts", headers=auth_headers()).json()
    assert any(c["id"] == contact_id for c in listed)

    deleted = client.delete(f"/api/v1/contacts/{contact_id}", headers=auth_headers())
    assert deleted.status_code == 200
