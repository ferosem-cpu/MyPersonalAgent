# Portable Personal Agent + Work Tracker

This folder contains a portable productivity system designed to live in a Google Drive synced folder.

## Layout

```text
tracker/
  index.html
  data/
    worklog.json
    todos.json
    memory.json
agent/
  agent.py
  config.json
  .env.example
  requirements.txt
  build.bat
  storage.py
  storage_sqlite.py
  migrate_json_to_firestore.py
  migrate_json_to_sqlite.py
  api/
    server.py
    schemas.py
    routes_todos.py, routes_worklog.py, routes_memory.py, routes_contacts.py, routes_sync.py
  run_api.py
android/            # Phase 1, see PLAN.md
```

## Work Tracker

Open `tracker/index.html` in a browser. It runs from `file://` with no server and no CDN.

Use **Open data folder** to pick `tracker/data` once. Browsers that support the File System Access API will remember the folder handle and save `worklog.json`, `todos.json`, and `memory.json` together. You can also use **Open worklog** for worklog-only access. If your browser does not support it, use JSON import/export.

The tracker includes:

- Work entries with title, description, project, timestamp, and minutes.
- Today, history, search, daily summary, and plain-text report views.
- To-do tab with due dates, recurrence field, complete, snooze, and overdue highlighting.
- Memory tab for simple remember/recall notes.
- Light/dark mode.

## Local Agent

### Setup

From `agent/`:

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
copy .env.example .env
notepad .env
```

### Web UI (Recommended)

Run the Flask web UI for easy provider/model selection and API key management:

```powershell
cd agent
.\run_web.bat
```

Open http://localhost:5000 in your browser. Features:

- **Provider Selection**: Choose from 6 LLM providers (OpenRouter, Anthropic, OpenAI, Google, Grok, NVIDIA)
- **Model Selection**: Auto-populated based on provider
- **API Key Management**: Add/update API keys in the UI (saved to .env)
- **Chat Interface**: Full agent capabilities with real-time responses
- **Auto-Fallback**: Automatically switches providers if one fails
- **Tool Access**: All agent tools available (shell, files, apps, todos, memory, etc.)

### CLI Mode

For terminal-based interaction:

```powershell
.\.venv\Scripts\python.exe agent.py
```

The agent exposes tools for shell commands, file read/write, app opening, directory listing, work logging, to-dos, snoozing, completion, memory, and recall.

Destructive shell commands require an explicit `y` confirmation.

### Multi-Provider Support

The agent supports 6 LLM providers with automatic fallback:

- **OpenRouter** (default) - 1000+ models via single API
- **Anthropic** - Claude family
- **OpenAI** - GPT family
- **Google** - Gemini family
- **Grok** - xAI models
- **NVIDIA** - Nemotron models

Configure in `config.json` or select via the web UI.

## Reminders and Telegram

The agent starts a background scheduler while the CLI is running. It checks `todos.json` every 60 seconds, sends desktop notifications, and sends Telegram messages if these values are present in `agent/.env`:

```text
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
```

Run `/start` with your Telegram bot to discover your chat ID. The standalone `telegram_bot.py` module supports long polling commands such as `done`, `todo`, `log`, `remember`, and `recall`.

## Firestore

Default storage is local JSON:

```json
"storage": "json"
```

To switch to Firestore, set:

```json
"storage": "firestore"
```

Then configure:

```text
FIRESTORE_PROJECT_ID=
GOOGLE_APPLICATION_CREDENTIALS=
```

Run the idempotent migration:

```powershell
python migrate_json_to_firestore.py
```

## REST API (for the Android app)

A FastAPI server exposes todos, worklog, memory, and contacts over authenticated REST, backed by the same `storage.py` gateway as everything else.

```powershell
cd agent
copy .env.example .env   # if you haven't already
python -c "import secrets;print(secrets.token_urlsafe(32))"   # generate a token
notepad .env              # paste it into AGENT_API_TOKEN=
.\run_api.bat
```

Open http://localhost:8500/docs for the interactive OpenAPI UI. Every request except `/api/v1/health` requires an `X-API-Key` header matching `AGENT_API_TOKEN`.

Storage backend is config-driven (`config.json`: `"storage": "json" | "sqlite" | "firestore"`). To move to SQLite (needed once multiple processes write concurrently — see Phase 2 of `PLAN.md`), run `python migrate_json_to_sqlite.py`, then flip the config value; flip back to `"json"` any time to roll back instantly (the JSON files are never touched by SQLite mode).

## Phone access

To reach the API from your Android phone:

- **Recommended:** install [Tailscale](https://tailscale.com) on both the laptop and the phone. The Android app then talks to `http://<tailscale-ip>:8500` — no port-forwarding, traffic is encrypted, and it's free.
- **Alternative:** LAN-only access at `http://192.168.x.x:8500` while your phone is on the same home Wi-Fi as the laptop.

**Known limitation:** the API is only reachable while the laptop is on and the scheduled task (`MyPersonalAgent-*`, see `handover.md`) is running. A future cloud deployment (an always-free GCP e2-micro instance, or Firestore storage) would lift `run_api.py` and `scheduler.py` off the laptop unchanged, since everything already routes through `storage.py` — see Phase 3 of `PLAN.md` for that stretch goal.

## Build

On Windows:

```powershell
cd agent
.\build.bat
```

PyInstaller builds are per operating system. A Windows build will not run on macOS or Linux.
