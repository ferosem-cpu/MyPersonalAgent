"""Telegram send-to-anyone via the user's own account (Telethon/MTProto, PLAN_V2 Task 5.3).

Separate from the existing bot (run_telegram.py / telegram_bot.py), which can only
message users who have already started the bot. This lets the agent message ANY
Telegram contact by phone number or @username, using the user's own logged-in
account - a real DM, not a bot message.

One-time setup: run `agent/setup_telegram_user.py` once (interactive phone + code
login), which creates `agent/tg_user.session` (gitignored - never commit or share
it, it grants full account access, same as a stolen login session).
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path

from telethon import TelegramClient
from telethon.errors import RPCError

AGENT_DIR = Path(__file__).resolve().parent.parent
SESSION_PATH = AGENT_DIR / "tg_user.session"


def _require_client() -> TelegramClient:
    api_id = os.getenv("TG_API_ID")
    api_hash = os.getenv("TG_API_HASH")
    if not api_id or not api_hash:
        raise RuntimeError(
            "TG_API_ID / TG_API_HASH are not set in agent/.env. Get both from "
            "https://my.telegram.org (API development tools), add them, then run "
            "agent/setup_telegram_user.py once to log in."
        )
    if not SESSION_PATH.exists():
        raise RuntimeError(
            "No Telegram user session found. Run agent/setup_telegram_user.py once "
            "(one-time phone number + confirmation code login)."
        )
    return TelegramClient(str(SESSION_PATH), int(api_id), api_hash)


async def _send_dm_async(target: str, message: str) -> dict:
    client = _require_client()
    await client.connect()
    try:
        if not await client.is_user_authorized():
            raise RuntimeError(
                "Telegram user session exists but isn't authorized. Run "
                "agent/setup_telegram_user.py again to log back in."
            )
        try:
            entity = await client.get_entity(target)
        except (ValueError, RPCError) as e:
            raise RuntimeError(
                f"Couldn't find a Telegram user for '{target}': {e}. Try their @username, "
                "or make sure they're saved as a contact in this Telegram account."
            )
        sent = await client.send_message(entity, message)
        return {"id": sent.id, "to": target}
    finally:
        await client.disconnect()


def send_telegram_dm(phone_or_username: str, message: str) -> dict:
    """Sync wrapper - runs a short-lived Telethon client per call (asyncio.run), rather
    than keeping one alive, to avoid event-loop clashes with the long-running bot
    process. They're separate processes with separate session files, so no conflict."""
    try:
        return asyncio.run(_send_dm_async(phone_or_username, message))
    except RPCError as e:
        raise RuntimeError(f"Telegram send failed: {e}")
