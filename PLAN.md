# MyPersonalAgent — Mobile & Backend Implementation Plan

**Target executor:** Sonnet 5 (autonomous, task-by-task)
**Repo:** `D:\Projects\MyPersonalAgent`
**Goal:** Add a REST API backend over the existing storage layer, then an Android app with full offline sync, without breaking the existing tracker page, CLI agent, web UI, Telegram bot, or scheduler.

---

## Ground Rules (read before every task)

1. **`storage.py` is the only data gateway.** The API server must call `JsonStorage`/`SqliteStorage` methods — never touch `tracker/data/*.json` directly. This is a hard design constraint from the original spec (Part 4).
2. **Existing entry points must keep working unchanged:** `agent/agent.py` (CLI), `agent/web_ui.py` (Flask, port 5000), `agent/run_telegram.py`, `agent/scheduler.py`, `tracker/index.html`.
3. **Backend selection stays config-driven:** `config.json` `"storage": "json" | "sqlite" | "firestore"`. Switching backends requires zero code changes outside config.
4. **New server code goes in `agent/api/`** (new package). New Android code goes in a new top-level `android/` folder.
5. **Never commit secrets.** `.env`, `drive_credentials.json`, `drive_token.json` stay untracked. Create `.gitignore` in Task 0.1.
6. **Windows dev machine.** Use `agent\.venv\Scripts\python.exe` for all Python commands. Test commands are given in PowerShell.

---

## Phase 0 — Backend API Foundation (2 weeks)

**Outcome:** A FastAPI server (`agent/api/`) on port 8500 exposing todos, worklog, memory, and contacts over authenticated REST, backed by a new SQLite storage backend with sync metadata, with the JSON backend still the default and untouched.

### Task 0.1 — Repo hygiene and dependencies

**Files:** `D:\Projects\MyPersonalAgent\.gitignore` (new), `D:\Projects\MyPersonalAgent\agent\requirements.txt` (edit)

Create `.gitignore`:

```gitignore
agent/.venv/
agent/logs/
agent/uploads/
agent/.env
agent/drive_credentials.json
agent/drive_token.json
agent/data.db
agent/data.db-*
__pycache__/
*.pyc
android/.gradle/
android/build/
android/app/build/
android/local.properties
```

Append to `agent/requirements.txt`:

```text
fastapi>=0.115
uvicorn[standard]>=0.30
pydantic>=2.7
```

Install: `& 'D:\Projects\MyPersonalAgent\agent\.venv\Scripts\pip.exe' install -r D:\Projects\MyPersonalAgent\agent\requirements.txt`

**Acceptance:** `pip show fastapi uvicorn` succeeds; `git status` (after `git init` if the user wants a repo) shows no secrets.

---

### Task 0.2 — Add sync metadata to the storage layer (backward compatible)

**File:** `D:\Projects\MyPersonalAgent\agent\storage.py`

Every record needs `updated` (ISO timestamp) and `deleted` (bool) for sync. Existing records lack them, so treat missing values as `updated = created/ts` and `deleted = false`.

1. Add a module-level helper:

```python
def with_sync_fields(item: dict) -> dict:
    """Ensure sync metadata exists. Non-destructive; safe on legacy records."""
    item.setdefault("updated", item.get("created") or item.get("ts") or now_iso())
    item.setdefault("deleted", False)
    return item
```

2. In `JsonStorage.add_work_entry`, `add_todo`, `remember`, `add_contact`: add `"updated": now_iso(), "deleted": False` to the new-record dicts.
3. In `complete_todo` and `snooze_todo`: set `todo["updated"] = now_iso()` before `save_todos(data)`.
4. Add two new generic methods to `JsonStorage` (the API will use these):

```python
_COLLECTIONS = {
    "entries": ("tracker_json", "worklog", "save_worklog", "entries"),
    "todos":   ("todos_json", "todos", "save_todos", "todos"),
    "notes":   ("memory_json", "memory", "save_memory", "notes"),
    "contacts": ("contacts_json", "contacts", "save_contacts", "contacts"),
}

def list_items(self, collection: str, since: str | None = None,
               include_deleted: bool = False) -> list[dict]:
    _, reader, _, key = self._COLLECTIONS[collection]
    items = [with_sync_fields(i) for i in getattr(self, reader)().get(key, [])]
    if since:
        items = [i for i in items if i["updated"] > since]
    if not include_deleted:
        items = [i for i in items if not i.get("deleted")]
    return items

def upsert_item(self, collection: str, item: dict) -> dict:
    """Last-write-wins upsert by id + updated timestamp. Returns the winner."""
    _, reader, saver, key = self._COLLECTIONS[collection]
    data = getattr(self, reader)()
    items = data.setdefault(key, [])
    item = with_sync_fields(dict(item))
    item.setdefault("id", str(uuid.uuid4()))
    for i, existing in enumerate(items):
        if existing.get("id") == item["id"]:
            if with_sync_fields(existing)["updated"] >= item["updated"]:
                return existing          # server copy newer — reject client write
            items[i] = item
            getattr(self, saver)(data)
            return item
    items.append(item)
    getattr(self, saver)(data)
    return item

def soft_delete_item(self, collection: str, item_id: str) -> dict | None:
    _, reader, saver, key = self._COLLECTIONS[collection]
    data = getattr(self, reader)()
    for existing in data.get(key, []):
        if existing.get("id") == item_id:
            existing["deleted"] = True
            existing["updated"] = now_iso()
            getattr(self, saver)(data)
            return existing
    return None
```

**Compatibility check (critical):** `tracker/index.html` and `scheduler.py` iterate todos and filter on `status`. Soft-deleted items keep their `status`, so **also verify** the scheduler skips deleted items — in `scheduler.py`, find the todo-scan loop and add `if todo.get("deleted"): continue` at the top. Do the same in `agent.py`'s `list_todos` tool and `telegram_bot.py`'s `_send_todo_list()`.

**Acceptance:** run the existing storage smoke pattern:

```powershell
& 'D:\Projects\MyPersonalAgent\agent\.venv\Scripts\python.exe' -c "
from pathlib import Path; import storage
cfg = storage.load_config(Path(r'D:\Projects\MyPersonalAgent\agent'))
s = storage.make_storage(Path(r'D:\Projects\MyPersonalAgent\agent'), cfg)
t = s.add_todo('sync-meta smoke test')
assert 'updated' in t and t['deleted'] is False
assert any(i['id']==t['id'] for i in s.list_items('todos'))
s.soft_delete_item('todos', t['id'])
assert not any(i['id']==t['id'] for i in s.list_items('todos'))
assert any(i['id']==t['id'] for i in s.list_items('todos', include_deleted=True))
print('OK')"
```

CLI agent, web UI, and Telegram bot still start and list todos correctly.

---

### Task 0.3 — SQLite backend (new, optional; JSON remains default)

**Files:** `D:\Projects\MyPersonalAgent\agent\storage_sqlite.py` (new), `D:\Projects\MyPersonalAgent\agent\migrate_json_to_sqlite.py` (new), `agent/storage.py` (edit `make_storage`)

SQLite gives the API server safe concurrent access and fast `updated > ?` delta queries. It subclasses `JsonStorage` exactly like `FirestoreStorage` does — override the four load/save pairs, inherit all business logic (`add_todo`, `complete_todo`, `recall`, etc.).

Schema (embed as `SCHEMA` string in `storage_sqlite.py`, applied idempotently on connect):

```sql
CREATE TABLE IF NOT EXISTS entries (
  id TEXT PRIMARY KEY,
  ts TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  desc TEXT NOT NULL DEFAULT '',
  project TEXT NOT NULL DEFAULT '',
  minutes INTEGER NOT NULL DEFAULT 0,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS todos (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  project TEXT NOT NULL DEFAULT '',
  due TEXT,
  recurrence TEXT,
  remind_before_min INTEGER NOT NULL DEFAULT 30,
  status TEXT NOT NULL DEFAULT 'open',
  snooze_until TEXT,
  created TEXT NOT NULL,
  completed TEXT,
  last_reminded TEXT,
  escalation_step INTEGER NOT NULL DEFAULT 0,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS notes (
  id TEXT PRIMARY KEY,
  text TEXT NOT NULL,
  tags TEXT NOT NULL DEFAULT '[]',      -- JSON array string
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS contacts (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  first_name TEXT, last_name TEXT,
  phone_number TEXT, email TEXT, telegram_user_id TEXT,
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_entries_updated ON entries(updated);
CREATE INDEX IF NOT EXISTS idx_todos_updated ON todos(updated);
CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated);
CREATE INDEX IF NOT EXISTS idx_contacts_updated ON contacts(updated);
```

Implementation notes:
- `SqliteStorage(JsonStorage)` with `db_path = base_dir / "data.db"`, connect with `sqlite3.connect(db_path, check_same_thread=False)` and `PRAGMA journal_mode=WAL;` (allows the API server and scheduler to share the file).
- `worklog()` returns `{"version": 1, "entries": [row_dicts]}`; `save_worklog(data)` does `INSERT OR REPLACE` per row inside one transaction (mirrors `FirestoreStorage._save_collection`). Serialize `tags` to a JSON string on write, parse on read. Map `deleted` int ↔ bool.
- In `make_storage()` add:

```python
if config.get("storage") == "sqlite":
    from storage_sqlite import SqliteStorage
    return SqliteStorage(agent_dir, config)
```

`migrate_json_to_sqlite.py`: mirror the structure of the existing `migrate_json_to_firestore.py` — read all four JSON files via a `JsonStorage`, `upsert` every record into SQLite, idempotent (safe to re-run; `INSERT OR REPLACE` keyed on `id`, but skip if the SQLite copy has a newer `updated`). Print counts.

**Rollback:** flip config back to `"storage": "json"`. JSON files are never modified by SQLite mode, so this is instant. **For all of Phase 0–1, leave `config.json` at `"storage": "json"`** — the API works against either backend. Switch to sqlite only in Phase 2 Task 2.1.

**Acceptance:** run migration; re-run it (idempotent, same counts); temporary config flip to sqlite → CLI `list_todos` shows the same todos → flip back.

---

### Task 0.4 — FastAPI server skeleton with auth

**Files (all new):**
```
D:\Projects\MyPersonalAgent\agent\api\__init__.py
D:\Projects\MyPersonalAgent\agent\api\server.py      # app factory, auth, router mounting
D:\Projects\MyPersonalAgent\agent\api\schemas.py     # Pydantic models
D:\Projects\MyPersonalAgent\agent\api\routes_todos.py
D:\Projects\MyPersonalAgent\agent\api\routes_worklog.py
D:\Projects\MyPersonalAgent\agent\api\routes_memory.py
D:\Projects\MyPersonalAgent\agent\api\routes_contacts.py
D:\Projects\MyPersonalAgent\agent\api\routes_sync.py # Phase 2 (stub now)
D:\Projects\MyPersonalAgent\agent\run_api.py         # launcher
D:\Projects\MyPersonalAgent\agent\run_api.bat        # mirrors run_web.bat
```

`agent/api/server.py`:

```python
from __future__ import annotations
import os, secrets
from pathlib import Path
from fastapi import FastAPI, Depends, HTTPException, Security
from fastapi.security import APIKeyHeader
from dotenv import load_dotenv
import sys
AGENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AGENT_DIR))          # api/ modules import storage from agent/
import storage as storage_mod

load_dotenv(AGENT_DIR / ".env")
api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)

def get_storage():
    cfg = storage_mod.load_config(AGENT_DIR)
    return storage_mod.make_storage(AGENT_DIR, cfg)

def require_api_key(key: str = Security(api_key_header)):
    expected = os.getenv("AGENT_API_TOKEN")
    if not expected:
        raise HTTPException(503, "AGENT_API_TOKEN not set in agent/.env")
    if not (key and secrets.compare_digest(key, expected)):
        raise HTTPException(401, "Invalid or missing X-API-Key")

def create_app() -> FastAPI:
    app = FastAPI(title="MyPersonalAgent API", version="0.1.0")
    from api import routes_todos, routes_worklog, routes_memory, routes_contacts, routes_sync
    deps = [Depends(require_api_key)]
    app.include_router(routes_todos.router,   prefix="/api/v1", dependencies=deps)
    app.include_router(routes_worklog.router, prefix="/api/v1", dependencies=deps)
    app.include_router(routes_memory.router,  prefix="/api/v1", dependencies=deps)
    app.include_router(routes_contacts.router,prefix="/api/v1", dependencies=deps)
    app.include_router(routes_sync.router,    prefix="/api/v1", dependencies=deps)

    @app.get("/api/v1/health")               # unauthenticated liveness probe
    def health():
        return {"status": "ok", "version": "0.1.0"}
    return app
```

`agent/run_api.py`:

```python
import uvicorn
from api.server import create_app
app = create_app()
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8500)   # 8500: web_ui already owns 5000
```

`agent/run_api.bat` (copy the pattern from `run_web.bat`, but run `.venv\Scripts\python.exe run_api.py`).

Also: add `AGENT_API_TOKEN=` to `agent/.env.example` with a comment (`# generate: python -c "import secrets;print(secrets.token_urlsafe(32))"`). Do NOT write into the real `.env` — tell the user to add it.

**Acceptance:** `run_api.bat` starts; `http://localhost:8500/docs` shows OpenAPI UI; `GET /api/v1/health` → 200 without a key; `GET /api/v1/todos` → 401 without key, 200 with correct `X-API-Key`.

---

### Task 0.5 — Pydantic schemas

**File:** `D:\Projects\MyPersonalAgent\agent\api\schemas.py`

Mirror the JSON schemas exactly (field names must match `storage.py` — the tracker page and agent share them):

```python
from __future__ import annotations
from pydantic import BaseModel, Field
from typing import Literal

class WorkEntry(BaseModel):
    id: str | None = None
    ts: str | None = None
    title: str
    desc: str = ""
    project: str = ""
    minutes: int = 0
    updated: str | None = None
    deleted: bool = False

class Todo(BaseModel):
    id: str | None = None
    title: str
    project: str = ""
    due: str | None = None
    recurrence: str | None = None
    remind_before_min: int = 30
    status: Literal["open", "done", "snoozed"] = "open"
    snooze_until: str | None = None
    created: str | None = None
    completed: str | None = None
    last_reminded: str | None = None
    escalation_step: int = 0
    updated: str | None = None
    deleted: bool = False

class Note(BaseModel):
    id: str | None = None
    text: str
    tags: list[str] = Field(default_factory=list)
    created: str | None = None
    updated: str | None = None
    deleted: bool = False

class Contact(BaseModel):
    id: str | None = None
    name: str
    first_name: str | None = None
    last_name: str | None = None
    phone_number: str | None = None
    email: str | None = None
    telegram_user_id: str | None = None
    created: str | None = None
    updated: str | None = None
    deleted: bool = False
```

---

### Task 0.6 — CRUD routes (sample implementation: todos — replicate pattern for the other three)

**File:** `D:\Projects\MyPersonalAgent\agent\api\routes_todos.py`

```python
from fastapi import APIRouter, Depends, HTTPException, Query
from api.schemas import Todo
from api.server import get_storage

router = APIRouter(tags=["todos"])

@router.get("/todos", response_model=list[Todo])
def list_todos(status: str = Query("open", pattern="^(open|done|snoozed|all)$"),
               s=Depends(get_storage)):
    items = s.list_items("todos")
    if status != "all":
        items = [t for t in items if t.get("status") == status]
    return sorted(items, key=lambda t: t.get("snooze_until") or t.get("due") or "")

@router.post("/todos", response_model=Todo, status_code=201)
def create_todo(todo: Todo, s=Depends(get_storage)):
    return s.add_todo(todo.title, todo.project, todo.due,
                      todo.recurrence, todo.remind_before_min)

@router.put("/todos/{todo_id}", response_model=Todo)
def update_todo(todo_id: str, todo: Todo, s=Depends(get_storage)):
    todo.id = todo_id
    from storage import now_iso
    todo.updated = now_iso()
    return s.upsert_item("todos", todo.model_dump())

@router.post("/todos/{todo_id}/complete", response_model=Todo)
def complete_todo(todo_id: str, s=Depends(get_storage)):
    result = s.complete_todo(todo_id)        # also auto-logs the work entry
    if not result:
        raise HTTPException(404, "todo not found")
    return result

@router.delete("/todos/{todo_id}", response_model=Todo)
def delete_todo(todo_id: str, s=Depends(get_storage)):
    result = s.soft_delete_item("todos", todo_id)
    if not result:
        raise HTTPException(404, "todo not found")
    return result
```

Replicate for the other three routers (thin wrappers over `list_items`/`upsert_item`/`soft_delete_item` plus the specific creators `add_work_entry`, `remember`, `add_contact`; memory gets an extra `GET /memory/recall?q=` calling `s.recall(q)`).

**Full endpoint specification (Phase 0 contract — Android builds against this):**

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/v1/health` | — | `{"status":"ok"}` |
| GET | `/api/v1/todos?status=open\|done\|snoozed\|all` | — | `Todo[]` |
| POST | `/api/v1/todos` | `Todo` (no id) | `Todo` 201 |
| PUT | `/api/v1/todos/{id}` | `Todo` | `Todo` |
| POST | `/api/v1/todos/{id}/complete` | — | `Todo` |
| DELETE | `/api/v1/todos/{id}` | — | soft-deleted `Todo` |
| GET | `/api/v1/entries?since=ISO&project=&q=` | — | `WorkEntry[]` |
| POST | `/api/v1/entries` | `WorkEntry` | `WorkEntry` 201 |
| PUT/DELETE | `/api/v1/entries/{id}` | … | … |
| GET | `/api/v1/memory` / `GET /api/v1/memory/recall?q=` | — | `Note[]` |
| POST/PUT/DELETE | `/api/v1/memory[...]` | `Note` | `Note` |
| GET/POST/PUT/DELETE | `/api/v1/contacts[...]` | `Contact` | `Contact` |
| POST | `/api/v1/sync` | see Phase 2 | see Phase 2 |

Example — create todo:

```http
POST /api/v1/todos HTTP/1.1
X-API-Key: <token>
Content-Type: application/json

{"title": "Call Vivian", "project": "personal", "due": "2026-08-02T10:00:00+05:30"}
```

```json
201 {"id":"7f3c...","title":"Call Vivian","project":"personal","due":"2026-08-02T10:00:00+05:30",
     "recurrence":null,"remind_before_min":30,"status":"open","snooze_until":null,
     "created":"2026-08-01T21:04:11+05:30","completed":null,"last_reminded":null,
     "escalation_step":0,"updated":"2026-08-01T21:04:11+05:30","deleted":false}
```

**Acceptance / Milestone M0:**
- All CRUD routes return correct data against the *live* `tracker/data/*.json` files.
- A todo created via `POST /api/v1/todos` appears in the tracker page (after folder reload), in Telegram `list`, and triggers the reminder scheduler.
- A todo completed via the API auto-logs a work entry (inherited `complete_todo` behavior).
- Smoke script (curl or Python `httpx`) exercising every endpoint passes; keep it as `agent/tests/test_api_smoke.py` (pytest, spins up the app with `fastapi.testclient.TestClient` and a **temp copy** of the data dir — point `config` at a tmp path so tests never touch real data).

**Safety:** the API server is additive — nothing else imports it. If anything misbehaves, stop `run_api.bat`; the rest of the system is untouched.

---

### Task 0.7 — Remote access for the phone

No code — documentation task. Append a "Phone access" section to `D:\Projects\MyPersonalAgent\README.md`:

- **Recommended:** install Tailscale on laptop + phone; Android app uses `http://<tailscale-ip>:8500`. Zero port-forwarding, encrypted, free.
- Alternative: LAN-only (`http://192.168.x.x:8500`) when on home Wi-Fi.
- Note the known limitation from the spec (Part 3.5): API only reachable while laptop is on; the future e2-micro VM deployment (Phase 3 stretch) lifts `run_api.py` + `scheduler.py` to the cloud unchanged because everything routes through `storage.py`.

---

## Phase 1 — Android MVP (2–3 weeks)

**Outcome:** Kotlin/Compose app in `D:\Projects\MyPersonalAgent\android\` that lists/creates/completes todos and logs work entries online-only against the Phase 0 API. Offline comes in Phase 2 — but the architecture (Room + Repository) is laid down now so Phase 2 adds sync, not rewrites.

**Dependency:** Milestone M0 passed.

### Task 1.1 — Project scaffold

Create with Android Studio "Empty Activity (Compose)" template equivalents:

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/mypersonalagent/app/
            ├── MainActivity.kt
            ├── App.kt                      # @HiltAndroidApp
            ├── di/AppModule.kt
            ├── data/
            │   ├── local/ (AppDatabase.kt, TodoDao.kt, EntryDao.kt, TodoEntity.kt, EntryEntity.kt)
            │   ├── remote/ (ApiService.kt, ApiModels.kt, AuthInterceptor.kt)
            │   └── repo/ (TodoRepository.kt, EntryRepository.kt, SettingsRepository.kt)
            ├── sync/ (SyncWorker.kt)        # Phase 2
            └── ui/
                ├── todos/ (TodoListScreen.kt, TodoViewModel.kt)
                ├── log/ (QuickLogScreen.kt, LogViewModel.kt)
                └── settings/ (SettingsScreen.kt)
```

`gradle/libs.versions.toml` key versions (pin; adjust patch versions to latest stable at build time):

```toml
[versions]
agp = "8.6.0"
kotlin = "2.0.20"
compose-bom = "2024.09.00"
room = "2.6.1"
retrofit = "2.11.0"
okhttp = "4.12.0"
hilt = "2.52"
work = "2.9.1"
datastore = "1.1.1"
kotlinx-serialization = "1.7.1"
```

`app/build.gradle.kts` essentials: `minSdk = 26`, `targetSdk = 35`, plugins `com.android.application`, `org.jetbrains.kotlin.android`, `kotlin-kapt` (or KSP for Room/Hilt), `dagger.hilt.android.plugin`, `kotlinx-serialization`. Dependencies: compose BOM, material3, navigation-compose, room-runtime/ktx + ksp compiler, retrofit + `retrofit2:converter-kotlinx-serialization`, okhttp + logging-interceptor, hilt-android + compiler, hilt-navigation-compose, work-runtime-ktx, hilt-work, datastore-preferences.

`AndroidManifest.xml`: `INTERNET` permission; `android:usesCleartextTraffic="true"` **only in the debug manifest** (`src/debug/AndroidManifest.xml`) so plain-HTTP Tailscale/LAN URLs work in dev without weakening release builds.

**Acceptance:** `.\gradlew.bat assembleDebug` succeeds from `android/`; app launches showing an empty todo screen.

### Task 1.2 — Networking layer (sample implementation)

`data/remote/ApiModels.kt` — `@Serializable` data classes mirroring the Pydantic schemas exactly (same field names, `@SerialName("remind_before_min")` etc. for snake_case).

`data/remote/ApiService.kt`:

```kotlin
interface ApiService {
    @GET("api/v1/todos") suspend fun listTodos(@Query("status") status: String = "all"): List<TodoDto>
    @POST("api/v1/todos") suspend fun createTodo(@Body todo: TodoDto): TodoDto
    @PUT("api/v1/todos/{id}") suspend fun updateTodo(@Path("id") id: String, @Body todo: TodoDto): TodoDto
    @POST("api/v1/todos/{id}/complete") suspend fun completeTodo(@Path("id") id: String): TodoDto
    @DELETE("api/v1/todos/{id}") suspend fun deleteTodo(@Path("id") id: String): TodoDto
    @GET("api/v1/entries") suspend fun listEntries(@Query("since") since: String? = null): List<EntryDto>
    @POST("api/v1/entries") suspend fun createEntry(@Body entry: EntryDto): EntryDto
    @POST("api/v1/sync") suspend fun sync(@Body req: SyncRequest): SyncResponse   // Phase 2
    @GET("api/v1/health") suspend fun health(): HealthDto
}
```

`AuthInterceptor.kt` adds `X-API-Key` from DataStore. `SettingsRepository` stores base URL + token in `androidx.datastore` (Preferences). `AppModule` builds Retrofit with a **mutable base URL** (rebuild Retrofit when settings change, or use an interceptor that rewrites the host — simplest reliable approach: `Provider`-style factory recreated on settings change).

`SettingsScreen.kt`: two text fields (Server URL, API token) + "Test connection" button calling `/api/v1/health`.

### Task 1.3 — Room database (schema-final in Phase 1, even though sync starts Phase 2)

`TodoEntity` — all Todo fields plus `pendingSync: Boolean` (local changes not yet pushed) and `locallyDeleted: Boolean`. `EntryEntity` likewise. DAOs expose `Flow<List<TodoEntity>>` for UI and `suspend fun upsertAll(items: List<TodoEntity>)`, `@Query("SELECT * FROM todos WHERE pendingSync = 1")`.

### Task 1.4 — Repository + UI (sample implementation: TodoRepository, Phase 1 online-first)

```kotlin
class TodoRepository @Inject constructor(
    private val api: ApiService,
    private val dao: TodoDao,
) {
    val todos: Flow<List<TodoEntity>> =
        dao.observeAll()                     // UI always reads Room (single source of truth)

    suspend fun refresh() {                  // Phase 1: naive full refresh
        val remote = api.listTodos("all")
        dao.upsertAll(remote.map { it.toEntity(pendingSync = false) })
    }

    suspend fun create(title: String, project: String, due: String?) {
        val created = api.createTodo(TodoDto(title = title, project = project, due = due))
        dao.upsert(created.toEntity(pendingSync = false))
    }

    suspend fun complete(id: String) {
        val done = api.completeTodo(id)
        dao.upsert(done.toEntity(pendingSync = false))
    }
}
```

UI: `TodoListScreen` (grouped: Overdue / Today / Upcoming / Done toggle; swipe-to-complete; FAB to add), `QuickLogScreen` (title, desc, project, minutes → `POST /entries`), bottom navigation between Todos / Log / Settings.

**Milestone M1 (end of Phase 1):**
- On phone with Tailscale: configure URL + token in Settings, "Test connection" green.
- Todo created on phone appears in laptop tracker page and Telegram `list` within seconds.
- Todo completed on phone auto-logs a work entry visible in the tracker.
- Quick-log from phone appears in the worklog.
- Airplane mode: app still *shows* cached todos from Room (writes fail with a visible error snackbar — acceptable until Phase 2).

---

## Phase 2 — Full Offline Sync (2 weeks)

**Outcome:** Reads and writes work fully offline; changes reconcile bidirectionally with last-write-wins by `updated` timestamp; background sync via WorkManager.

**Dependency:** M1 passed. **First step: switch the laptop to SQLite** (Task 2.1) so concurrent API + scheduler + Telegram writes are safe.

### Task 2.1 — Activate SQLite backend on the laptop

1. Stop all agent processes (`web_ui.py`, `run_telegram.py`, `run_api.py`).
2. Run `migrate_json_to_sqlite.py`; verify counts match the JSON files.
3. Edit `config.json`: `"storage": "sqlite"`.
4. Restart everything; verify CLI/web/Telegram/API/scheduler all see identical data.
5. **Known trade-off to document in README:** the tracker `index.html` still reads the JSON files directly via File System Access API. Add a small export bridge: in `SqliteStorage`'s `save_*` methods, also write the JSON file (call `_write_json` with the same payload) so the tracker page stays read-consistent. JSON becomes a mirror; SQLite is the source of truth. (This also keeps Drive sync working since `_sync_to_drive` hooks the same paths — wire `drive` into `SqliteStorage` via `make_storage`.)

**Rollback:** `"storage": "json"` — the JSON mirror is always current, so rollback loses nothing.

### Task 2.2 — Server sync endpoint

**File:** `D:\Projects\MyPersonalAgent\agent\api\routes_sync.py` (replace stub)

Contract:

```http
POST /api/v1/sync
{
  "last_sync": "2026-08-01T18:00:00+05:30",     // null on first sync
  "changes": {
    "todos":   [ {Todo with client-set updated}, ... ],
    "entries": [ ... ],
    "notes":   [ ... ],
    "contacts":[ ... ]
  }
}
```

```json
{
  "server_time": "2026-08-01T21:10:00+05:30",
  "applied":  {"todos": ["id1"], "entries": []},
  "rejected": {"todos": [{"id": "id2", "server_copy": {…}}]},   // server was newer
  "changes":  {"todos": [...], "entries": [...], "notes": [...], "contacts": [...]}
}
```

Implementation:

```python
@router.post("/sync")
def sync(req: SyncRequest, s=Depends(get_storage)):
    server_time = now_iso()
    applied, rejected = {}, {}
    for coll, items in req.changes.items():
        for item in items:
            winner = s.upsert_item(coll, item)
            (applied if winner["updated"] == item["updated"] else rejected)\
                .setdefault(coll, []).append(...)
    changes = {c: s.list_items(c, since=req.last_sync, include_deleted=True)
               for c in ("todos", "entries", "notes", "contacts")}
    return {"server_time": server_time, "applied": applied,
            "rejected": rejected, "changes": changes}
```

Rules (must-follow):
- Conflict resolution: **last-write-wins on `updated`** (ISO-8601 strings compare correctly only in the same offset — normalize both sides to UTC before comparing in `upsert_item`; fix `with_sync_fields`/comparison accordingly).
- Deletes propagate as `deleted: true` records (that's why pulls use `include_deleted=True`).
- Push-before-pull inside one request avoids echoing the client's own changes as conflicts.
- `server_time` becomes the client's next `last_sync` (server clock only — never mix client clocks into the cursor).

### Task 2.3 — Android offline-first rewrite of repositories

Change Phase 1 repos to **write locally first**:

```kotlin
suspend fun create(title: String, project: String, due: String?) {
    val now = isoNow()
    dao.upsert(TodoEntity(id = UUID.randomUUID().toString(), title = title,
        project = project, due = due, status = "open", created = now,
        updated = now, pendingSync = true))
    SyncWorker.requestExpedited(context)     // try to sync immediately if online
}
```

`sync/SyncWorker.kt` (Hilt-injected `CoroutineWorker`):

```kotlin
override suspend fun doWork(): Result = try {
    val lastSync = settings.lastSync()
    val pending = SyncRequest(
        lastSync = lastSync,
        changes = mapOf(
            "todos" to todoDao.pendingSync().map { it.toDto() },
            "entries" to entryDao.pendingSync().map { it.toDto() },
        ))
    val resp = api.sync(pending)
    db.withTransaction {
        resp.changes.forEach { (coll, items) -> daos[coll]!!.applyServer(items) }
        todoDao.clearPendingSync(resp.applied["todos"].orEmpty())
        // rejected items: overwrite local with server_copy (server won)
    }
    settings.setLastSync(resp.serverTime)
    Result.success()
} catch (e: Exception) { Result.retry() }
```

Scheduling: periodic `PeriodicWorkRequest` every 15 min with `NetworkType.CONNECTED` constraint + expedited one-shot on every local write + on app foreground. `applyServer` upserts unless the local row has `pendingSync = true` **and** a newer `updated` (keep local, it will win next push).

**Milestone M2 acceptance tests:**
1. Airplane mode → create 2 todos, complete 1, log an entry → all visible in app. Re-enable network → within a minute everything appears on the laptop (tracker + Telegram).
2. While phone is offline, edit the *same* todo's title on the laptop (via API), then edit it differently on the phone, then reconnect → the later `updated` wins on both sides; no duplicates.
3. Delete a todo on the laptop → next phone sync removes it from the app list.
4. Kill and reopen the app offline → data intact (Room).
5. Run sync twice in a row with no changes → second response has empty `changes` (cursor correctness).

---

## Phase 3 — Polish & Refinement (2 weeks)

Dependencies: M2 passed. Tasks are independent — pick in any order.

### Task 3.1 — Android notifications for reminders
Since the scheduler runs on the laptop, the phone already gets Telegram reminders. Add native app notifications: in `SyncWorker`, after pull, diff todos due within `remind_before_min` and post a local notification (`POST_NOTIFICATIONS` runtime permission, channel "Reminders"). Keep Telegram as the guaranteed escalation channel (spec: escalation is mandatory and stays server-side in `scheduler.py`).

### Task 3.2 — Memory vault + contacts screens in Android
Two more screens over the existing endpoints: Memory (remember box + recall search hitting `/api/v1/memory/recall?q=`), Contacts (read-only list, tap to dial/email via intents).

### Task 3.3 — Agent chat from the phone (stretch)
Add `POST /api/v1/chat {"message": "..."} → {"reply": "..."}` in a new `agent/api/routes_chat.py`, reusing the exact pattern from `run_telegram.py` (build `MultiProviderLLMClient` + `tools_dict` once at app startup, guard with a lock — LLM calls are slow; set uvicorn timeout accordingly). Android gets a simple chat screen. Risk: exposes shell tools remotely — **restrict the API chat tools_dict to data tools only** (`log_work`, `add_todo`, `complete_todo`, `list_todos`, `remember`, `recall`); exclude `run_shell`, `write_file`, `open_app`.

### Task 3.4 — Hardening
- Rate-limit auth failures (simple in-memory counter, 429 after 10 bad keys/min).
- `pytest` suite: sync conflict matrix (offline-edit-both-sides, delete-vs-edit, clock-skew ±5 min).
- Android release build: disable cleartext, ProGuard rules for Room/Retrofit/kotlinx-serialization.
- README: full setup guide (server token, Tailscale, app install via `adb install`).

### Task 3.5 — Cloud lift (optional, from spec Part 3.5)
Deploy `run_api.py` + `scheduler.py` + `run_telegram.py` to a GCP e2-micro (always-free) with the SQLite file, or flip `"storage": "firestore"` and run stateless. Not required for M3.

**Milestone M3:** release APK on the user's phone; one week of daily use with zero data loss; all Phase 2 acceptance tests re-pass on the release build.

---

## Known Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Concurrent JSON writes (API + scheduler + Telegram) corrupt files in Phase 0–1 | Low write volume; mitigate by keeping Phase 0 read-heavy testing, then move to SQLite WAL in Task 2.1 before real multi-device write load |
| Tracker `index.html` shows stale data after SQLite switch | JSON mirror write in Task 2.1 step 5 |
| Clock skew between phone and laptop breaks last-write-wins | Compare `updated` in UTC; client uses its own clock for `updated` but server clock for the `last_sync` cursor; document ±5 min tolerance test in Task 3.4 |
| Masked-key style data-loss bugs (happened before in web_ui) | API never returns or accepts masked secrets; token lives only in `.env` and phone DataStore |
| Duplicate process spawn quirk (see handover.md 2026-07-20) | `run_api.py` binds port 8500 — duplicate spawn fails to bind and exits harmlessly; never kill individual PIDs from the session launcher |
| ISO timestamp format drift (`storage.now_iso()` uses local offset) | Normalize to UTC at every comparison point (`upsert_item`, sync cursor); add a unit test with mixed-offset timestamps |
| Breaking existing tools_dict wiring | Never modify `agent.py`/`web_ui.py`/`run_telegram.py` in Phases 0–2 except the three one-line `deleted`-skip guards in Task 0.2 |

## Rollback Summary

- Phase 0: stop `run_api.bat`; delete `agent/api/` — zero residue.
- Phase 2.1: set `"storage": "json"` — JSON mirror is always current.
- Android: uninstall app; server unaffected.
- Data safety net: Google Drive mirror sync (already live) backs up every JSON save.
