"""POST /api/v1/chat - free-form agent chat for the phone app.

Reuses the same MultiProviderLLMClient + LocalTools pattern as run_telegram.py, built once
and reused across requests (LLM calls are slow; a fresh client per request would also lose
conversation history). A single lock serializes requests, since MultiProviderLLMClient keeps
one shared turn history - this endpoint is for one user's phone, not concurrent multi-user
chat, so that's the right tradeoff, not a bottleneck.

Security: the tools_dict handed to the LLM here is deliberately restricted to data tools only
(log_work, add_todo, complete_todo, list_todos, remember, recall). Everything else - run_shell,
read_file, write_file, open_app, list_dir, snooze_todo, save_contact, list_contacts - is mapped
to a stub that refuses, rather than the real LocalTools method. It can't be omitted outright:
TOOL_SCHEMA in llm_client.py is a shared module-level constant advertised to the model in full
regardless of which tools_dict a given client was built with, and the provider tool-call loop
does a raw `self.tools[name]` lookup with no missing-key guard - an omitted tool would raise an
unhandled KeyError the first time the model tried to call it, rather than failing safely.
"""

from __future__ import annotations

import threading
from pathlib import Path

from fastapi import APIRouter
from fastapi.concurrency import run_in_threadpool

from agent import LocalTools
from api.schemas import ChatRequest, ChatResponse
from llm_client import MultiProviderLLMClient
from storage import load_config

router = APIRouter(tags=["chat"])

AGENT_DIR = Path(__file__).resolve().parent.parent

_ALLOWED_TOOLS = {"log_work", "add_todo", "complete_todo", "list_todos", "remember", "recall"}

_lock = threading.Lock()
_llm: MultiProviderLLMClient | None = None


def _refused(name: str):
    def _tool(**_kwargs):
        return {"error": f"'{name}' is not available through the phone chat API"}
    return _tool


def _get_llm() -> MultiProviderLLMClient:
    global _llm
    if _llm is not None:
        return _llm

    config = load_config(AGENT_DIR)
    if not config.get("llm_providers"):
        config["llm_providers"] = [
            {"provider": config.get("llm_provider"), "model": config.get("llm_model", "")}
        ]
    tools = LocalTools(config)
    all_tools = {
        "run_shell": tools.run_shell,
        "read_file": tools.read_file,
        "write_file": tools.write_file,
        "open_app": tools.open_app,
        "list_dir": tools.list_dir,
        "log_work": tools.log_work,
        "add_todo": tools.add_todo,
        "list_todos": tools.list_todos,
        "complete_todo": tools.complete_todo,
        "snooze_todo": tools.snooze_todo,
        "remember": tools.remember,
        "recall": tools.recall,
        "save_contact": tools.save_contact,
        "list_contacts": tools.list_contacts,
        # Registered so they get the _refused() stub below (see module docstring) -
        # never bound to the real methods. Outbound-comms tools are never exposed to
        # the remote phone chat endpoint (PLAN_V2 Ground Rule 3).
        "send_whatsapp_message": tools.send_whatsapp_message,
        "send_telegram_message": tools.send_telegram_message,
        "send_mail": tools.send_mail,
    }
    restricted = {
        name: (fn if name in _ALLOWED_TOOLS else _refused(name))
        for name, fn in all_tools.items()
    }
    _llm = MultiProviderLLMClient(config, restricted, manual_provider=config.get("llm_provider"))
    return _llm


@router.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest) -> ChatResponse:
    def _ask() -> str:
        with _lock:
            return _get_llm().ask(req.message)

    reply = await run_in_threadpool(_ask)
    return ChatResponse(reply=reply)
