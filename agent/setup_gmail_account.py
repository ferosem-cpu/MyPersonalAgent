"""One-time interactive Gmail OAuth authorization for one email account (PLAN_V2 Task 5.4).

Usage:
    D:\\Projects\\MyPersonalAgent\\agent\\.venv\\Scripts\\python.exe setup_gmail_account.py <account_key> <email_address>

Example:
    ...\\python.exe setup_gmail_account.py personal_gmail you@gmail.com

Reuses the same OAuth client secret as the existing Drive sync
(agent/drive_credentials.json - see DRIVE_SETUP.md to create one if you haven't
already) under a separate gmail.send-scoped consent, and writes a per-account
token file agent/gmail_token_<account_key>.json (gitignored). Adds/updates the
matching entry under email_accounts in config.json.

Run this once per personal email account you want the agent to be able to send
from. Never configure a work/corporate account this way - see email_blocklist
in config.json, which refuses any sender or recipient matching it regardless.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from google_auth_oauthlib.flow import InstalledAppFlow

from services.mailer import GMAIL_SCOPES

AGENT_DIR = Path(__file__).resolve().parent
CREDENTIALS_PATH = AGENT_DIR / "drive_credentials.json"
CONFIG_PATH = AGENT_DIR / "config.json"


def main() -> None:
    if len(sys.argv) < 3:
        print("Usage: setup_gmail_account.py <account_key> <email_address>")
        print("Example: setup_gmail_account.py personal_gmail you@gmail.com")
        sys.exit(1)
    account_key, address = sys.argv[1], sys.argv[2]

    if not CREDENTIALS_PATH.exists():
        print(f"Missing {CREDENTIALS_PATH}.")
        print("This reuses the same OAuth client as Google Drive sync - see DRIVE_SETUP.md")
        print("to create one first (Google Cloud Console > OAuth 2.0 Client ID > Desktop app).")
        sys.exit(1)

    token_path = AGENT_DIR / f"gmail_token_{account_key}.json"
    flow = InstalledAppFlow.from_client_secrets_file(str(CREDENTIALS_PATH), GMAIL_SCOPES)
    creds = flow.run_local_server(port=0)
    token_path.write_text(creds.to_json(), encoding="utf-8")
    print(f"Authorized {address}. Token saved to {token_path}")

    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    accounts = config.setdefault("email_accounts", {})
    accounts[account_key] = {"type": "gmail_api", "address": address, "token_file": token_path.name}
    CONFIG_PATH.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"email_accounts.{account_key} added to config.json.")
    print("Restart the web UI / Telegram bot so they pick up the change.")


if __name__ == "__main__":
    main()
