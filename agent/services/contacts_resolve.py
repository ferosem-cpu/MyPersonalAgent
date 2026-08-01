"""Shared contact-name resolution for the outbound-communication tools (5.2 WhatsApp,
5.3 Telegram-to-anyone, 5.4 email). Never sends anything itself - just looks a name up.
"""

from __future__ import annotations

from typing import Any


def resolve_contact(storage, query: str) -> dict[str, Any] | list[dict[str, Any]] | None:
    """Resolve a free-form name query against the contacts collection.

    Returns:
        - a single contact dict on an unambiguous match
        - a list of candidate contacts when more than one matches (the caller
          must ask the user to disambiguate before doing anything with them)
        - None when nothing matches
    """
    if not query or not query.strip():
        return None
    q = query.strip().lower()

    contacts = [c for c in storage.contacts().get("contacts", []) if not c.get("deleted")]

    # An exact full-name match wins outright, even if other contacts partially
    # match the same substring (e.g. "Rose" exact vs "Rosemary" partial).
    exact = [c for c in contacts if (c.get("name") or "").strip().lower() == q]
    if len(exact) == 1:
        return exact[0]

    def matches(c: dict[str, Any]) -> bool:
        fields = (c.get("name"), c.get("first_name"), c.get("last_name"))
        return any(q in (f or "").lower() for f in fields)

    candidates = [c for c in contacts if matches(c)]
    if not candidates:
        return None
    if len(candidates) == 1:
        return candidates[0]
    return candidates


def whatsapp_number_for(contact: dict[str, Any]) -> str | None:
    """whatsapp_number falls back to phone_number when not set separately."""
    return contact.get("whatsapp_number") or contact.get("phone_number")
