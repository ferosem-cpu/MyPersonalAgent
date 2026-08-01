from __future__ import annotations

import os
import secrets
import sys
from pathlib import Path

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, HTTPException, Security
from fastapi.security import APIKeyHeader

AGENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AGENT_DIR))  # api/ modules import storage from agent/

import storage as storage_mod  # noqa: E402

load_dotenv(AGENT_DIR / ".env")
api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)


def get_storage():
    cfg = storage_mod.load_config(AGENT_DIR)
    return storage_mod.make_storage(AGENT_DIR, cfg)


def require_api_key(key: str = Security(api_key_header)) -> None:
    expected = os.getenv("AGENT_API_TOKEN")
    if not expected:
        raise HTTPException(503, "AGENT_API_TOKEN not set in agent/.env")
    if not (key and secrets.compare_digest(key, expected)):
        raise HTTPException(401, "Invalid or missing X-API-Key")


def create_app() -> FastAPI:
    app = FastAPI(title="MyPersonalAgent API", version="0.1.0")
    from api import routes_chat, routes_contacts, routes_memory, routes_sync, routes_todos, routes_worklog

    deps = [Depends(require_api_key)]
    app.include_router(routes_todos.router, prefix="/api/v1", dependencies=deps)
    app.include_router(routes_worklog.router, prefix="/api/v1", dependencies=deps)
    app.include_router(routes_memory.router, prefix="/api/v1", dependencies=deps)
    app.include_router(routes_contacts.router, prefix="/api/v1", dependencies=deps)
    app.include_router(routes_sync.router, prefix="/api/v1", dependencies=deps)
    app.include_router(routes_chat.router, prefix="/api/v1", dependencies=deps)

    @app.get("/api/v1/health")  # unauthenticated liveness probe
    def health():
        return {"status": "ok", "version": "0.1.0"}

    return app
