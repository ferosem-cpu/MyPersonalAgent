from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query

from api.schemas import Note
from api.server import get_storage
from storage import now_iso

router = APIRouter(tags=["memory"])


@router.get("/memory", response_model=list[Note])
def list_notes(s=Depends(get_storage)):
    return s.list_items("notes")


@router.get("/memory/recall", response_model=list[Note])
def recall_notes(q: str = Query(...), s=Depends(get_storage)):
    return s.recall(q)


@router.post("/memory", response_model=Note, status_code=201)
def create_note(note: Note, s=Depends(get_storage)):
    return s.remember(note.text, note.tags)


@router.put("/memory/{note_id}", response_model=Note)
def update_note(note_id: str, note: Note, s=Depends(get_storage)):
    note.id = note_id
    note.updated = now_iso()
    return s.upsert_item("notes", note.model_dump())


@router.delete("/memory/{note_id}", response_model=Note)
def delete_note(note_id: str, s=Depends(get_storage)):
    result = s.soft_delete_item("notes", note_id)
    if not result:
        raise HTTPException(404, "note not found")
    return result
