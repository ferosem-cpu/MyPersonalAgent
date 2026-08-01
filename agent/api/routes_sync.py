from __future__ import annotations

from fastapi import APIRouter, Depends

from api.schemas import SyncRequest, SyncResponse
from api.server import get_storage
from storage import now_iso

router = APIRouter(tags=["sync"])

_COLLECTIONS = ("todos", "entries", "notes", "contacts")


@router.post("/sync", response_model=SyncResponse)
def sync(req: SyncRequest, s=Depends(get_storage)):
    """Bidirectional sync: push the client's changes first (so its own writes
    never come back as "new" changes to itself), then pull everything the
    server has seen since the client's last cursor - including deletes."""
    applied: dict[str, list[str]] = {}
    rejected: dict[str, list[dict]] = {}

    for collection, items in req.changes.items():
        if collection not in _COLLECTIONS:
            continue
        for item in items:
            winner = s.upsert_item(collection, item)
            item_id = item.get("id")
            if item_id and winner.get("id") == item_id and winner.get("updated") == item.get("updated"):
                applied.setdefault(collection, []).append(item_id)
            else:
                rejected.setdefault(collection, []).append({"id": item_id, "server_copy": winner})

    # server_time is captured AFTER applying pushes, BEFORE the pull, so the
    # client's next last_sync cursor never misses anything written during
    # this same request and never re-fetches what it just pushed.
    server_time = now_iso()

    changes = {
        collection: s.list_items(collection, since=req.last_sync, include_deleted=True)
        for collection in _COLLECTIONS
    }

    return SyncResponse(server_time=server_time, applied=applied, rejected=rejected, changes=changes)
