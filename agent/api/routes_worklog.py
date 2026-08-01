from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query

from api.schemas import WorkEntry
from api.server import get_storage
from storage import now_iso

router = APIRouter(tags=["entries"])


@router.get("/entries", response_model=list[WorkEntry])
def list_entries(
    since: str | None = Query(None),
    project: str | None = Query(None),
    q: str | None = Query(None),
    s=Depends(get_storage),
):
    items = s.list_items("entries", since=since)
    if project:
        items = [e for e in items if (e.get("project") or "").lower() == project.lower()]
    if q:
        needle = q.lower()
        items = [e for e in items if needle in f"{e.get('title', '')} {e.get('desc', '')}".lower()]
    return sorted(items, key=lambda e: e.get("ts") or "", reverse=True)


@router.post("/entries", response_model=WorkEntry, status_code=201)
def create_entry(entry: WorkEntry, s=Depends(get_storage)):
    return s.add_work_entry(entry.title, entry.desc, entry.project, entry.minutes)


@router.put("/entries/{entry_id}", response_model=WorkEntry)
def update_entry(entry_id: str, entry: WorkEntry, s=Depends(get_storage)):
    entry.id = entry_id
    entry.updated = now_iso()
    return s.upsert_item("entries", entry.model_dump())


@router.delete("/entries/{entry_id}", response_model=WorkEntry)
def delete_entry(entry_id: str, s=Depends(get_storage)):
    result = s.soft_delete_item("entries", entry_id)
    if not result:
        raise HTTPException(404, "entry not found")
    return result
