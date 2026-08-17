"""Zan-APP API client for MyPersonalAgent.

Talks to the Zan-APP backend (zan-app-api) over HTTPS using a management-role
JWT obtained via email/password login. The token is cached on disk
(agent/data/zan_app_token.json) and refreshed automatically on 401 or when
the cached copy is close to expiry.

Required agent/.env entries:
    ZAN_APP_API_URL=https://<your-zan-app-api-deployment>.vercel.app
    ZAN_APP_EMAIL=<a management-role user's login email>
    ZAN_APP_PASSWORD=<that user's password>

IMPORTANT: use a dedicated management-role account for this agent rather than
your own personal login. That way agent-driven writes are distinguishable in
Zan-APP's audit/edit logs (createdById / editedById), and the credential can
be rotated or revoked independently of your own account if this machine or
the .env file is ever compromised.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any

import requests

AGENT_DIR = Path(__file__).resolve().parent.parent
TOKEN_CACHE_PATH = AGENT_DIR / "data" / "zan_app_token.json"


class ZanAppError(RuntimeError):
    pass


def _load_cached_token() -> str | None:
    if not TOKEN_CACHE_PATH.exists():
        return None
    try:
        cached = json.loads(TOKEN_CACHE_PATH.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None
    # Refresh a bit before actual expiry so an in-flight request never rides a token
    # that dies mid-call.
    if cached.get("expires_at", 0) < time.time() + 60:
        return None
    return cached.get("token")


def _store_token(token: str, expires_in_seconds: int) -> None:
    TOKEN_CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    TOKEN_CACHE_PATH.write_text(
        json.dumps({"token": token, "expires_at": time.time() + expires_in_seconds}),
        encoding="utf-8",
    )


def _base_url() -> str:
    base_url = os.getenv("ZAN_APP_API_URL", "").rstrip("/")
    if not base_url:
        raise ZanAppError("ZAN_APP_API_URL is not set in agent/.env")
    return base_url


def _login() -> str:
    email = os.getenv("ZAN_APP_EMAIL")
    password = os.getenv("ZAN_APP_PASSWORD")
    if not email or not password:
        raise ZanAppError("ZAN_APP_EMAIL and ZAN_APP_PASSWORD must be set in agent/.env")

    resp = requests.post(
        f"{_base_url()}/auth/login", json={"email": email, "password": password}, timeout=15
    )
    if resp.status_code != 200:
        raise ZanAppError(f"Zan-APP login failed ({resp.status_code}): {resp.text}")
    token = resp.json()["token"]
    # JWT_EXPIRES_IN defaults to 7d server-side; cache for 6 days so we always refresh
    # comfortably inside that window without needing to know the exact server setting.
    _store_token(token, expires_in_seconds=6 * 24 * 60 * 60)
    return token


def get_token() -> str:
    return _load_cached_token() or _login()


def _request(method: str, path: str, **kwargs: Any) -> Any:
    token = get_token()
    url = f"{_base_url()}{path}"
    headers = kwargs.pop("headers", {})
    headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, url, headers=headers, timeout=30, **kwargs)
    if resp.status_code == 401:
        # Cached token was rejected (e.g. revoked/expired server-side) - force one fresh
        # login and retry once before giving up.
        token = _login()
        headers["Authorization"] = f"Bearer {token}"
        resp = requests.request(method, url, headers=headers, timeout=30, **kwargs)
    if resp.status_code >= 400:
        raise ZanAppError(f"Zan-APP API error ({resp.status_code}) on {method} {path}: {resp.text}")
    if resp.status_code == 204 or not resp.content:
        return None
    return resp.json()


# --- Lookups (read-only, no confirmation needed) ------------------------------

def list_customers(query: str = "") -> list[dict[str, Any]]:
    customers = _request("GET", "/customers")
    if query:
        q = query.lower()
        customers = [c for c in customers if q in (c.get("name") or "").lower()]
    return customers


def list_sites(query: str = "") -> list[dict[str, Any]]:
    sites = _request("GET", "/sites")
    if query:
        q = query.lower()
        sites = [s for s in sites if q in json.dumps(s).lower()]
    return sites


# --- Invoices (finance writes - always called via the confirm-gated tools in agent.py) ---

def create_invoice(payload: dict[str, Any]) -> dict[str, Any]:
    return _request("POST", "/invoices", json=payload)


def issue_invoice(invoice_id: str) -> dict[str, Any]:
    return _request("POST", f"/invoices/{invoice_id}/issue")


def record_payment(invoice_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    return _request("POST", f"/invoices/{invoice_id}/payments", json=payload)


def list_invoices(status: str | None = None) -> list[dict[str, Any]]:
    params = {"status": status} if status else {}
    return _request("GET", "/invoices", params=params)


def get_invoice(invoice_id: str) -> dict[str, Any]:
    return _request("GET", f"/invoices/{invoice_id}")


# --- Work orders ---------------------------------------------------------------

def create_work_order(payload: dict[str, Any]) -> dict[str, Any]:
    return _request("POST", "/work-orders", json=payload)


def update_work_order(work_order_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    return _request("PATCH", f"/work-orders/{work_order_id}", json=payload)


def list_work_orders() -> list[dict[str, Any]]:
    return _request("GET", "/work-orders")
