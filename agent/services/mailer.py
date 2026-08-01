"""Multi-account email sending (PLAN_V2 Task 5.4). Gmail API (OAuth, reusing the
same Google Cloud project/client-secret as the existing Drive sync) with an SMTP
fallback for non-Google personal accounts.

Hard rule, enforced here rather than left to configuration discipline: sending as
- or to - any address matching email_blocklist is refused outright. This is how
the DXC/corporate account requirement in PLAN_V2 is satisfied: that account is
simply never configured, and even if it somehow were, the blocklist stops it.
"""

from __future__ import annotations

import base64
import fnmatch
import os
import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from pathlib import Path
from typing import Any

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

GMAIL_SCOPES = ["https://www.googleapis.com/auth/gmail.send"]

AGENT_DIR = Path(__file__).resolve().parent.parent


def _is_blocked(address: str, blocklist: list[str]) -> bool:
    address = (address or "").lower()
    return any(fnmatch.fnmatch(address, pattern.lower()) for pattern in blocklist)


def _gmail_service(token_path: Path):
    if not token_path.exists():
        raise RuntimeError(
            f"No Gmail token at {token_path}. Run agent/setup_gmail_account.py "
            "<account_key> <email_address> once to authorize it."
        )
    creds = Credentials.from_authorized_user_file(str(token_path), GMAIL_SCOPES)
    if creds.expired and creds.refresh_token:
        creds.refresh(Request())
        token_path.write_text(creds.to_json(), encoding="utf-8")
    if not creds.valid:
        raise RuntimeError(f"Gmail credentials at {token_path} are invalid. Re-run agent/setup_gmail_account.py.")
    return build("gmail", "v1", credentials=creds, cache_discovery=False)


def _send_via_gmail_api(account: dict, to: str, subject: str, body: str, cc: str | None) -> dict:
    token_path = AGENT_DIR / account["token_file"]
    service = _gmail_service(token_path)
    message = MIMEText(body)
    message["to"] = to
    message["from"] = account["address"]
    message["subject"] = subject
    if cc:
        message["cc"] = cc
    raw = base64.urlsafe_b64encode(message.as_bytes()).decode()
    sent = service.users().messages().send(userId="me", body={"raw": raw}).execute()
    return {"id": sent.get("id")}


def _send_via_smtp(account: dict, to: str, subject: str, body: str, cc: str | None) -> dict:
    password_env = account.get("password_env")
    password = os.getenv(password_env) if password_env else None
    if not password:
        raise RuntimeError(
            f"SMTP account '{account.get('address')}' has no app password set - "
            f"add {password_env or '<password_env not configured>'} to agent/.env."
        )
    message = MIMEMultipart()
    message["From"] = account["address"]
    message["To"] = to
    message["Subject"] = subject
    if cc:
        message["Cc"] = cc
    message.attach(MIMEText(body, "plain"))

    recipients = [to] + ([cc] if cc else [])
    with smtplib.SMTP(account["host"], int(account.get("port", 587))) as server:
        server.starttls()
        server.login(account["address"], password)
        server.sendmail(account["address"], recipients, message.as_string())
    return {"status": "smtp_sent"}


def send_email(
    config: dict[str, Any], account_key: str, to: str, subject: str, body: str, cc: str | None = None
) -> dict:
    accounts = config.get("email_accounts", {})
    account = accounts.get(account_key)
    if not account:
        raise RuntimeError(
            f"Unknown email account '{account_key}'. Configured accounts: {', '.join(accounts) or '(none)'}"
        )

    blocklist = config.get("email_blocklist", [])
    sender = account.get("address", "")
    if _is_blocked(sender, blocklist):
        raise RuntimeError(f"Sending as '{sender}' is blocked by email_blocklist - this account can never send.")
    for recipient in [to] + ([cc] if cc else []):
        if _is_blocked(recipient, blocklist):
            raise RuntimeError(f"Recipient '{recipient}' is blocked by email_blocklist.")

    account_type = account.get("type", "gmail_api")
    if account_type == "gmail_api":
        return _send_via_gmail_api(account, to, subject, body, cc)
    if account_type == "smtp":
        return _send_via_smtp(account, to, subject, body, cc)
    raise RuntimeError(f"Unknown account type '{account_type}' for '{account_key}'.")
