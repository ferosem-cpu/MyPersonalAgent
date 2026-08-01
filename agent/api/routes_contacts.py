from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query

from api.schemas import Contact
from api.server import get_storage
from storage import now_iso

router = APIRouter(tags=["contacts"])


@router.get("/contacts", response_model=list[Contact])
def list_contacts(q: str | None = Query(None), s=Depends(get_storage)):
    items = s.list_items("contacts")
    if q:
        needle = q.lower()
        items = [c for c in items if needle in (c.get("name") or "").lower() or needle in (c.get("phone_number") or "")]
    return items


@router.post("/contacts", response_model=Contact, status_code=201)
def create_contact(contact: Contact, s=Depends(get_storage)):
    return s.add_contact(
        contact.name,
        phone_number=contact.phone_number,
        email=contact.email,
        telegram_user_id=contact.telegram_user_id,
        first_name=contact.first_name,
        last_name=contact.last_name,
        whatsapp_number=contact.whatsapp_number,
        email_accounts_note=contact.email_accounts_note,
    )


@router.put("/contacts/{contact_id}", response_model=Contact)
def update_contact(contact_id: str, contact: Contact, s=Depends(get_storage)):
    contact.id = contact_id
    contact.updated = now_iso()
    return s.upsert_item("contacts", contact.model_dump())


@router.delete("/contacts/{contact_id}", response_model=Contact)
def delete_contact(contact_id: str, s=Depends(get_storage)):
    result = s.soft_delete_item("contacts", contact_id)
    if not result:
        raise HTTPException(404, "contact not found")
    return result
