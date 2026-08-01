"""One-time interactive Telegram user login (PLAN_V2 Task 5.3).

Run this once from a real terminal (it needs to prompt you for input):

    D:\\Projects\\MyPersonalAgent\\agent\\.venv\\Scripts\\python.exe setup_telegram_user.py

It asks for the phone number registered on your Telegram account, then the
confirmation code Telegram sends you (and your 2FA password, if you have one
set). On success it saves agent/tg_user.session - never commit or share this
file; it grants the same access as being logged into your account. Delete it
any time to force a fresh login (or revoke it remotely via Telegram's own
Settings > Devices screen).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from telethon import TelegramClient

AGENT_DIR = Path(__file__).resolve().parent
load_dotenv(AGENT_DIR / ".env")


def main() -> None:
    api_id = os.getenv("TG_API_ID")
    api_hash = os.getenv("TG_API_HASH")
    if not api_id or not api_hash:
        print("TG_API_ID and TG_API_HASH must be set in agent/.env first.")
        print("Get them from https://my.telegram.org (API development tools),")
        print("then add to agent/.env:")
        print("  TG_API_ID=1234567")
        print("  TG_API_HASH=your_api_hash_here")
        sys.exit(1)

    session_path = AGENT_DIR / "tg_user.session"
    client = TelegramClient(str(session_path), int(api_id), api_hash)
    client.start()  # interactive: prompts for phone, code, and 2FA password if needed
    me = client.get_me()
    print(f"Logged in as {me.first_name} (@{me.username or 'no username'}).")
    print(f"Session saved to {session_path} - keep this file private.")
    client.disconnect()


if __name__ == "__main__":
    main()
