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

_ALLOWED_TOOLS = {
    "log_work", "add_todo", "complete_todo", "list_todos", "remember", "recall",
    # Shopping-list tools are just tagged notes (same risk profile as remember/recall)
    # and the whole point is managing the list from the phone - safe to allow.
    "add_to_shopping_list", "show_shopping_list", "clear_shopping_list",
    # Zan-APP read-only lookups - same risk profile as remember/recall/list_todos,
    # no side effects. The write tools (zan_create_invoice etc.) are deliberately
    # NOT included - see the comment where they're registered below.
    "zan_list_customers", "zan_list_sites", "zan_list_invoices", "zan_list_work_orders",
}

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
        "open_url": tools.open_url,
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
        # Drive tools can read/write/publish files, same risk category as read_file/
        # write_file above - excluded from phone chat by default for the same reason.
        "drive_search": tools.drive_search,
        "drive_upload": tools.drive_upload,
        "drive_download": tools.drive_download,
        "drive_share_link": tools.drive_share_link,
        "add_to_shopping_list": tools.add_to_shopping_list,
        "show_shopping_list": tools.show_shopping_list,
        "clear_shopping_list": tools.clear_shopping_list,
        # order_food/order_groceries open a browser on THIS (laptop) machine regardless
        # of caller - wrong behavior from a phone chat request, so kept refused here
        # like open_app/open_url. The Android app does its own local deep-linking instead.
        "order_food": tools.order_food,
        "order_groceries": tools.order_groceries,
        # Zan-APP tools: read-only lookups are safe (see _ALLOWED_TOOLS below). The
        # write tools (invoice/work-order create/update) are registered here so they
        # get the _refused() stub rather than an unhandled KeyError, but are NOT in
        # _ALLOWED_TOOLS by default - same treatment as send_mail/drive_share_link
        # above: this is a remote endpoint on a different trust boundary than the
        # laptop-only CLI/web/Telegram interfaces, so side-effect tools stay excluded
        # here regardless of their own confirm-gating. See handover notes for the
        # open question on whether to lift this restriction.
        "zan_list_customers": tools.zan_list_customers,
        "zan_list_sites": tools.zan_list_sites,
        "zan_list_invoices": tools.zan_list_invoices,
        "zan_list_work_orders": tools.zan_list_work_orders,
        "zan_create_invoice": tools.zan_create_invoice,
        "zan_issue_invoice": tools.zan_issue_invoice,
        "zan_record_payment": tools.zan_record_payment,
        "zan_create_work_order": tools.zan_create_work_order,
        "zan_update_work_order": tools.zan_update_work_order,
        "zan_ingest_work_order_file": tools.zan_ingest_work_order_file,
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
