"""Thin Python client for the local WhatsApp bridge (agent/services/wa-bridge/,
PLAN_V2 Task 5.2). The bridge itself does the real work (whatsapp-web.js); this
module just makes HTTP calls to it and turns connection failures into clear,
actionable error messages instead of raw exceptions.
"""

from __future__ import annotations

import os

import requests

_DEFAULT_PORT = 8600
_TIMEOUT = 15


def _base_url() -> str:
    port = os.getenv("WA_BRIDGE_PORT") or str(_DEFAULT_PORT)
    return f"http://127.0.0.1:{port}"


def _headers() -> dict[str, str]:
    key = os.getenv("WA_BRIDGE_KEY")
    if not key:
        raise RuntimeError(
            "WA_BRIDGE_KEY is not set in agent/.env - the WhatsApp bridge and this "
            "client must share the same key."
        )
    return {"X-Bridge-Key": key}


def wa_status() -> dict:
    try:
        resp = requests.get(f"{_base_url()}/status", headers=_headers(), timeout=_TIMEOUT)
    except requests.exceptions.ConnectionError:
        raise RuntimeError(
            "WhatsApp bridge is not running. Start it with agent/run_wa_bridge.bat, "
            "then scan the QR code it prints to pair your WhatsApp account."
        )
    resp.raise_for_status()
    return resp.json()


def wa_qr() -> str | None:
    """Returns the current pairing QR as a data: URL, or None if already paired."""
    try:
        resp = requests.get(f"{_base_url()}/qr", headers=_headers(), timeout=_TIMEOUT)
    except requests.exceptions.ConnectionError:
        raise RuntimeError("WhatsApp bridge is not running. Start it with agent/run_wa_bridge.bat.")
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    return resp.json().get("qr")


def send_whatsapp(number: str, message: str) -> dict:
    status = wa_status()
    if not status.get("ready"):
        if status.get("qr_pending"):
            raise RuntimeError(
                "WhatsApp bridge is running but not paired yet. Open the web UI's "
                "/whatsapp-setup page (or the bridge's console window) and scan the QR code."
            )
        raise RuntimeError("WhatsApp bridge is not ready yet. Give it a few seconds and try again.")

    try:
        resp = requests.post(
            f"{_base_url()}/send",
            headers=_headers(),
            json={"to": number, "message": message},
            timeout=_TIMEOUT,
        )
    except requests.exceptions.ConnectionError:
        raise RuntimeError(
            "WhatsApp bridge is not running. Start it with agent/run_wa_bridge.bat."
        )
    if resp.status_code >= 400:
        detail = resp.json().get("error", resp.text) if resp.headers.get("content-type", "").startswith("application/json") else resp.text
        raise RuntimeError(f"WhatsApp send failed: {detail}")
    return resp.json()
