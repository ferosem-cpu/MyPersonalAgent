from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class WorkEntry(BaseModel):
    id: str | None = None
    ts: str | None = None
    title: str
    desc: str = ""
    project: str = ""
    minutes: int = 0
    updated: str | None = None
    deleted: bool = False


class Todo(BaseModel):
    id: str | None = None
    title: str
    project: str = ""
    due: str | None = None
    recurrence: str | None = None
    remind_before_min: int = 30
    status: Literal["open", "done", "snoozed"] = "open"
    snooze_until: str | None = None
    created: str | None = None
    completed: str | None = None
    last_reminded: str | None = None
    escalation_step: int = 0
    updated: str | None = None
    deleted: bool = False


class Note(BaseModel):
    id: str | None = None
    text: str
    tags: list[str] = Field(default_factory=list)
    created: str | None = None
    updated: str | None = None
    deleted: bool = False


class Contact(BaseModel):
    id: str | None = None
    name: str
    first_name: str | None = None
    last_name: str | None = None
    phone_number: str | None = None
    email: str | None = None
    telegram_user_id: str | None = None
    created: str | None = None
    updated: str | None = None
    deleted: bool = False


class ChatRequest(BaseModel):
    message: str


class ChatResponse(BaseModel):
    reply: str


class SyncRequest(BaseModel):
    last_sync: str | None = None
    changes: dict[str, list[dict]] = Field(default_factory=dict)


class SyncResponse(BaseModel):
    server_time: str
    applied: dict[str, list[str]] = Field(default_factory=dict)
    rejected: dict[str, list[dict]] = Field(default_factory=dict)
    changes: dict[str, list[dict]] = Field(default_factory=dict)
