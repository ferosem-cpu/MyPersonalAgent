from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query

from api.schemas import Todo
from api.server import get_storage
from storage import now_iso

router = APIRouter(tags=["todos"])


@router.get("/todos", response_model=list[Todo])
def list_todos(status: str = Query("open", pattern="^(open|done|snoozed|all)$"), s=Depends(get_storage)):
    items = s.list_items("todos")
    if status != "all":
        items = [t for t in items if t.get("status") == status]
    return sorted(items, key=lambda t: t.get("snooze_until") or t.get("due") or "")


@router.post("/todos", response_model=Todo, status_code=201)
def create_todo(todo: Todo, s=Depends(get_storage)):
    return s.add_todo(todo.title, todo.project, todo.due, todo.recurrence, todo.remind_before_min)


@router.put("/todos/{todo_id}", response_model=Todo)
def update_todo(todo_id: str, todo: Todo, s=Depends(get_storage)):
    todo.id = todo_id
    todo.updated = now_iso()
    return s.upsert_item("todos", todo.model_dump())


@router.post("/todos/{todo_id}/complete", response_model=Todo)
def complete_todo(todo_id: str, s=Depends(get_storage)):
    result = s.complete_todo(todo_id)
    if not result:
        raise HTTPException(404, "todo not found")
    return result


@router.delete("/todos/{todo_id}", response_model=Todo)
def delete_todo(todo_id: str, s=Depends(get_storage)):
    result = s.soft_delete_item("todos", todo_id)
    if not result:
        raise HTTPException(404, "todo not found")
    return result
