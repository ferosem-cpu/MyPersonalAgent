# MyPersonalAgent — Feature List

Current capabilities as of 2026-08-01, organized by interface. See `PLAN.md` for the roadmap and `handover.md` for session-by-session build history.

## Core (shared by every interface, via `storage.py`)

- **To-dos**: add, complete, snooze, list (open/done/all), due dates, recurrence field, reminder escalation.
- **Work log**: title/description/project/minutes entries; completing a to-do auto-logs an entry.
- **Memory**: freeform remember/recall notes with tag support, keyword-scored search.
- **Contacts**: save (name/phone/email), list/search, always written out as a real `.vcf` file alongside the JSON record.
- **Reminder scheduler**: background thread scans to-dos every 60s, escalates via desktop notification + Telegram (15min → 1h → 3h → 24h).
- **Storage backend is config-driven**: `"storage": "sqlite"` is now the live default on this laptop (was `"json"`) — SQLite is the source of truth, with every write also mirrored back to the JSON files so the tracker portal and Drive sync keep working unchanged. `"firestore"` also still exists as an option. Roll back to `"json"` any time (the mirror is always current).
- **Google Drive mirror**: every JSON save also best-effort syncs to a Drive folder; Telegram file uploads get classified and synced too.

## Telegram bot (`run_telegram.py`)

- Full LLM-backed free-form chat (multi-provider with auto-fallback across OpenRouter/Anthropic/OpenAI/Google/Grok/NVIDIA).
- Conversation memory survives provider fallback mid-chat (plain-text turn log replayed into whichever provider loads next).
- Fast non-AI shortcuts: `todo `, `done`, `snooze`, `log `, `remember `, `recall `, `list`/`todos`.
- `/provider <name>` — pin to a specific LLM provider, or `/provider auto` to return to fallback.
- Native Telegram "share contact" card handling.
- Deterministic (non-AI) contact parsing for messages containing a name + phone number — reliable even when the free-tier/rate-limited AI would get it wrong.
- "Export all contacts as vcf" — sends one combined multi-vCard file of every saved contact.
- File/photo/video/audio uploads: saved locally under `agent/uploads/<Category>/` and mirrored to Drive.
- Desktop + Telegram reminder delivery.

## Web UI (`web_ui.py`, port 5000)

- Browser chat interface with the same multi-provider LLM backend.
- Provider/model picker, API key management (writes to `.env`).
- Auto-fallback across providers, same as Telegram.

## CLI (`agent.py`)

- Terminal chat with the same tool surface (file/shell/app-open/worklog/todo/memory/contacts).
- Destructive shell commands require explicit `y` confirmation.
- Interactive provider selection at startup.

## Tracker portal (`tracker/index.html`)

- Standalone offline HTML app, no server, opens via `file://`.
- File System Access API folder connection (remembers last folder, `id`+`startIn` hints reduce re-navigation friction).
- Today / History / To-Dos / Memory / Contacts / Report tabs.
- Contacts tab: list, **View** (raw vCard dialog), **Delete** — refreshes live when you click the tab, not just on initial folder pick.
- Daily summary, plain-text report generation + copy.
- Light/dark mode, JSON import/export fallback for browsers without File System Access API.

## REST API (`agent/api/`, port 8500)

- FastAPI server over the same `storage.py` gateway — no data-layer duplication.
- `X-API-Key` header auth (`AGENT_API_TOKEN` in `.env`, user-managed, never auto-written).
- Full CRUD: `/api/v1/{todos,entries,memory,contacts}` (list/create/update/delete + todo-complete, memory-recall).
- `/api/v1/health` — unauthenticated liveness check.
- `/api/v1/sync` — **real bidirectional sync** (Phase 2): push-then-pull, last-write-wins by `updated` timestamp (UTC-normalized so client/server timezone differences can't break conflict resolution), soft-deletes propagate, idempotent re-sync.
- Interactive OpenAPI docs at `/docs`.
- 22 automated tests (18 pytest + 4 unittest — includes live smoke + sync-conflict suites against an isolated temp data dir, never touches real data).

## Android app (`android/`)

- Kotlin/Jetpack Compose, Hilt DI, Room local cache, Retrofit + runtime-configurable server URL.
- **Offline-first (Phase 2)**: every write (add/complete/snooze/delete todo, log work) hits Room immediately — the UI never waits on the network. A background `SyncWorker` (WorkManager) pushes pending changes and pulls server changes: on every local write, every app foreground, and every 15 minutes while online. Conflicts resolve last-write-wins server-side; a losing local edit gets overwritten with the server's authoritative copy rather than silently vanishing.
- **Todos tab**: list, add, complete, manual Refresh, visible error message + Retry on failed loads.
- **Log tab**: create work-log entries, scrollable list of past entries.
- **Settings tab**: server URL + API token fields, Test connection button.
- Debug APK builds via `gradlew.bat assembleDebug`; installable directly (same debug key across builds, updates in place).
- **Not yet verified on an actual device** — no phone/emulator available in the build environment; server-side sync/conflict logic is tested, but on-device WorkManager scheduling and offline Room persistence need real-device testing.

## Always-on reliability

- Three self-healing Windows Task Scheduler entries (`MyPersonalAgent-WebUI`, `MyPersonalAgent-Telegram`, `MyPersonalAgent-API`), each launching a `run_*_forever.bat` wrapper that restarts the process 5 seconds after any crash, forever, starting at user logon — no manual restarts needed for normal operation (code/`.env` changes still require one manual restart to take effect, since Python doesn't hot-reload).

## Known gaps / not built yet

Phase 0, 1, and 2 of `PLAN.md` are complete. Only **Phase 3 (polish)** remains: Android push notifications, Memory/Contacts screens in the app, in-app AI chat, API rate limiting, release-build hardening, optional cloud deployment. See `handover.md` for full detail.
