"""Standalone Telegram bot runner - two-way chat + reminders via Telegram.

Run this alongside (or instead of) the web UI / CLI. It starts:
  - The reminder scheduler (desktop + Telegram notifications for due to-dos)
  - The Telegram long-polling bot, with free-form messages routed to the LLM agent
"""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

AGENT_DIR = Path(__file__).resolve().parent
load_dotenv(AGENT_DIR / ".env")

from agent import LocalTools
from llm_client import MultiProviderLLMClient
from scheduler import ReminderScheduler
from storage import load_config
from telegram_bot import TelegramBot


def main() -> None:
    if not os.getenv("TELEGRAM_BOT_TOKEN"):
        print("TELEGRAM_BOT_TOKEN is not set in .env. Add it, then run this again.")
        return

    config = load_config(AGENT_DIR)
    if os.getenv("LLM_PROVIDER"):
        config["llm_provider"] = os.getenv("LLM_PROVIDER")
    if os.getenv("LLM_MODEL"):
        config["llm_model"] = os.getenv("LLM_MODEL")
    if not config.get("llm_providers"):
        config["llm_providers"] = [{"provider": config.get("llm_provider"), "model": config.get("llm_model", "")}] 
    tools = LocalTools(config)

    scheduler = ReminderScheduler(config, tools.storage)
    if config.get("scheduler", {}).get("enabled", True):
        scheduler.start()

    tools_dict = {
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
        "send_whatsapp_message": tools.send_whatsapp_message,
        "send_telegram_message": tools.send_telegram_message,
        "send_mail": tools.send_mail,
        "drive_search": tools.drive_search,
        "drive_upload": tools.drive_upload,
        "drive_download": tools.drive_download,
        "drive_share_link": tools.drive_share_link,
    }
    llm = MultiProviderLLMClient(
        config,
        tools_dict,
        manual_provider=config.get("llm_provider") or os.getenv("LLM_PROVIDER"),
    )
    print(f"LLM ready: {llm.get_status()}")

    bot = TelegramBot(
        tools.storage,
        agent_reply=llm.ask,
        llm=llm,
        drive=tools.storage.drive,
        uploads_dir=AGENT_DIR / "uploads",
    )
    if bot.chat_id:
        print(f"Telegram bot starting. Chat ID already set: {bot.chat_id}")
    else:
        print("Telegram bot starting. Message your bot with /start to get your chat ID,")
        print("then add it to .env as TELEGRAM_CHAT_ID so scheduled reminders can reach you.")
    bot.run_forever()


if __name__ == "__main__":
    main()
