# Portable Personal Agent + Work Tracker — Build Specification

**Purpose:** Hand this document to any AI coding agent (Claude, Codex, Grok, Antigravity, etc.) to build the complete system. Everything needed is specified below. Build Part 2 first (simpler), then Part 1.

---

## Overall Architecture

```
GoogleDrive/
└── MyWorkspace/                  ← single parent folder, synced by Google Drive desktop app
    ├── agent/                    ← Part 1: portable local agent
    │   ├── agent.exe (or agent/ with venv)
    │   ├── .env.example
    │   ├── config.json
    │   └── logs/
    └── tracker/                  ← Part 2: portable work tracker
        ├── index.html            ← single-file app (HTML+CSS+JS inline)
        └── data/
            └── worklog.json
```

**Portability principle:** Everything lives in one folder. No installs on the host machine beyond the Google Drive desktop app. All paths relative. Google Drive sync = both backup and portability.

---

## PART 2 (build first): Portable Work Tracker Page

### What it is
A single-file HTML application that tracks daily work. Opens in any browser via `file://`. Stores data in a JSON file beside it. No server, no build step, no dependencies.

### Requirements
1. **Single file:** All HTML, CSS, and JavaScript in one `index.html`. No external CDNs required for core function (offline-capable).
2. **Data storage:**
   - Primary: File System Access API (`showSaveFilePicker` / `showOpenFilePicker`) to read/write `data/worklog.json`. Remember the file handle in IndexedDB so the user picks the file only once per machine.
   - Fallback (browsers without File System Access API): export/import JSON buttons.
   - Do NOT use localStorage as primary storage (not portable across machines).
3. **Features:**
   - Add a work entry: title, description, project tag, timestamp (auto), optional duration.
   - Today view: chronological list of today's entries.
   - History view: filter by date range and project tag.
   - Edit and delete entries.
   - Daily summary: total entries, total hours, per-project breakdown.
   - Quick-add bar at top: type and press Enter to log instantly.
   - Search across all entries.
   - Export: full JSON download and a formatted plain-text daily report (for pasting into emails/status updates).
4. **Data format** (`worklog.json`):
```json
{
  "version": 1,
  "entries": [
    {
      "id": "uuid",
      "ts": "2026-07-18T10:30:00+05:30",
      "title": "string",
      "desc": "string",
      "project": "string",
      "minutes": 45
    }
  ]
}
```
5. **UI:** Clean, professional, dark/light toggle, fully responsive (usable on a phone browser too). No frameworks required — vanilla JS is fine.
6. **Backup:** None to code. The folder sits inside Google Drive; the Drive desktop app syncs `worklog.json` automatically. Add a small "last saved" indicator in the UI.

### Acceptance tests
- Open `index.html` from a USB stick or Drive folder on a fresh machine → works.
- Add 3 entries, close browser, reopen → entries persist via the JSON file.
- Copy folder to another machine → same data appears.

---

## PART 1: Portable Local Agent

### What it is
A local AI agent that runs on the user's laptop, accepts natural-language commands (typed; voice optional v2), and executes them: file operations, launching apps, running shell commands, web lookups, and writing entries into the tracker's `worklog.json`.

### Stack
- **Language:** Python 3.11+
- **LLM:** Anthropic API (model: `claude-sonnet-4-6`) with tool use. Design the LLM client as a swappable module so other providers can be substituted.
- **Packaging:** PyInstaller single-folder build (`--onedir`) so it runs with zero installs. Also keep the plain-Python source runnable via a bundled venv for development.
- **Interface:** CLI REPL first (v1). Optional local web UI (FastAPI + simple page) as v2. Voice (whisper/speech-to-text) as v3.

### Tools the agent must implement (function-calling tools exposed to the LLM)
1. `run_shell(command)` — execute a shell command, return stdout/stderr. **Must ask user confirmation before any destructive command** (delete, format, move system files, registry edits).
2. `read_file(path)` / `write_file(path, content)` — restricted by default to a configurable allowlist of directories (default: the workspace folder + user's Documents/Downloads).
3. `open_app(name_or_path)` — launch an application (use `os.startfile` on Windows, `open` on macOS, `xdg-open` on Linux).
4. `web_search(query)` — optional; via any free search API or the LLM provider's built-in search tool.
5. `log_work(title, desc, project, minutes)` — append an entry to the tracker's `worklog.json` (path from config). This connects the two systems.
6. `list_dir(path)` — directory listing.

### Behavior requirements
- Conversation loop with context retained per session; `/reset` clears it.
- Confirmation gate: any tool call flagged destructive → print the exact action and require explicit `y` from the user.
- All actions logged to `logs/agent-YYYY-MM-DD.log` (timestamp, tool, args, result summary).
- Graceful error handling: tool failures are reported back to the LLM to retry or explain, never crash the loop.

### Configuration
- `.env` file (gitignored) for `ANTHROPIC_API_KEY`. On first run, if missing, prompt the user and write it.
- `config.json`:
```json
{
  "workspace_root": "..",
  "tracker_json": "../tracker/data/worklog.json",
  "allowed_dirs": ["..", "~/Documents", "~/Downloads"],
  "model": "claude-sonnet-4-6",
  "confirm_destructive": true
}
```
- All paths resolved relative to the agent folder → portable.

### Portability constraints
- No absolute paths anywhere.
- OS detection at runtime; abstract OS-specific calls behind a `platform_ops.py` module (Windows first-class; macOS/Linux best-effort).
- Distribution: the built `agent/` folder is copied into the Drive-synced workspace. Note in README: the PyInstaller build is per-OS (a Windows build won't run on macOS — build once per OS if needed).

### Acceptance tests
- Fresh Windows machine with only Google Drive installed: open workspace folder → run `agent.exe` → enter API key → say "create a file called notes.txt on my desktop saying hello" → file appears.
- Say "log that I spent 30 minutes on the ZAN-F website" → entry appears in the tracker page on next open.
- Say "delete everything in Downloads" → agent shows the command and asks for confirmation first.

---

## Build Order & Deliverables
1. `tracker/index.html` — complete single-file app (Part 2).
2. `agent/` Python source with all six tools, CLI REPL, config, logging (Part 1).
3. PyInstaller build script (`build.bat` / `build.sh`).
4. `README.md` — setup on a new machine in under 5 minutes, folder layout, how sync/backup works.

---

## READY-TO-PASTE BUILD PROMPT

> Build a two-part portable productivity system per the attached specification.
>
> **Part A — Work Tracker:** A single-file `index.html` (vanilla HTML/CSS/JS, no CDNs, no frameworks) that logs work entries (title, description, project, auto timestamp, optional minutes) to a `data/worklog.json` file beside it using the File System Access API with the file handle persisted in IndexedDB, with JSON export/import fallback. Include a quick-add bar, today view, filterable history, search, edit/delete, per-project daily summaries, plain-text daily report export, dark/light mode, and a "last saved" indicator. It must work fully offline from a `file://` URL and remain functional when the folder is copied to another machine.
>
> **Part B — Local Agent:** A Python 3.11 CLI agent using the Anthropic API (`claude-sonnet-4-6`) with tool use. Implement tools: run_shell (with mandatory y/n confirmation for destructive commands), read_file/write_file (restricted to allowlisted dirs from config.json), open_app (cross-platform via a platform_ops module), list_dir, and log_work which appends entries to the tracker's worklog.json (path from config). Load ANTHROPIC_API_KEY from .env, prompting and saving it on first run. Log all actions to daily log files. All paths relative to the agent folder. Provide a PyInstaller --onedir build script and a README covering setup on a fresh machine.
>
> Both parts live inside one parent folder intended to sit in a Google Drive synced directory, which provides backup and portability. Build Part A first, then Part B. Deliver complete, runnable code with no placeholders.
>
> **Part C — Memory & Reminders Layer (build after A and B):** Implement everything in the "PART 3" section of the attached spec: to-do module with due dates and recurring tasks, a background reminder scheduler owned by the agent (system tray process), Telegram bot integration for reminders/morning brief/end-of-day capture/reply-to-complete, escalating re-reminders for overdue items, and a "remember/recall" free-form memory vault. Reminders must fire from the agent process, not the HTML page.
>
> **Part D — Storage abstraction (design constraint from day one):** Route ALL data access through a single storage module in both the tracker (storage.js) and the agent (storage.py), selectable via config: `"storage": "json"` (default, local files) or `"storage": "firestore"` (Google Cloud Firestore using the user's existing Firebase project, with the Firebase JS SDK in the page and the Python Firestore client in the agent, offline persistence enabled). Collections mirror the JSON schemas exactly. Include an idempotent `migrate_json_to_firestore.py` script. Switching backends must require zero code changes outside config.

---

## PART 3: Memory & Reminders Layer (v2 — the core purpose)

**Design driver:** The user's primary problem is forgetting things. The system must be *proactive* — it pushes information to the user rather than waiting to be checked. One-shot reminders are considered a failure mode; unacknowledged items must escalate.

### 3.1 To-Do Module (extends the tracker)
- New file `data/todos.json`:
```json
{
  "version": 1,
  "todos": [
    {
      "id": "uuid",
      "title": "string",
      "project": "string",
      "due": "2026-07-20T15:00:00+05:30",
      "recurrence": null,
      "remind_before_min": 30,
      "status": "open | done | snoozed",
      "snooze_until": null,
      "created": "iso",
      "completed": null
    }
  ]
}
```
- `recurrence` supports: `daily`, `weekly:MO,WE`, `monthly:15`.
- Tracker `index.html` gains a To-Do tab: add/edit/complete/snooze, overdue items highlighted at top, completed items auto-logged as work entries.
- Agent gains tools: `add_todo`, `complete_todo`, `list_todos`, `snooze_todo`.

### 3.2 Reminder Scheduler (owned by the agent, NOT the page)
- Rationale: a `file://` page can only notify while open in a browser. Reminders must come from an always-running process.
- The agent runs a background scheduler thread (or a separate `scheduler.py` launched at OS startup / as a tray app via `pystray`).
- Every 60s it scans `todos.json` and fires reminders via: (a) native desktop notification (`plyer` or `win10toast`), and (b) Telegram.
- **Escalating re-reminders:** if a reminder is not acknowledged (marked done/snoozed), re-send at +15 min, +1 h, +3 h, then daily — until resolved. This is mandatory behavior, not optional.

### 3.3 Telegram Integration
- Free Telegram Bot (token via @BotFather) + user's chat_id, both stored in `.env`; a `/start` handshake captures chat_id automatically on first run.
- Outbound: reminders, overdue escalations, morning brief, end-of-day prompt.
- Inbound (long-polling, no public server needed): 
  - "done <task words>" → fuzzy-match and complete the todo
  - "snooze 2h" / "remind me tomorrow 10am" → snooze
  - "todo <text> tomorrow 5pm" → create a todo
  - "log <text>" → add a work entry
  - any other message → routed to the LLM agent, reply sent back to Telegram (remote control of the agent from the phone)
- **Morning brief (08:30, configurable):** today's todos, overdue items, yesterday's work summary.
- **End-of-day capture (18:00, configurable):** "What did you get done today?" — the reply is parsed by the LLM into work entries.

### 3.4 Memory Vault ("remember / recall")
- `data/memory.json`: free-form notes with timestamp and tags.
- Agent tools: `remember(text, tags)` and `recall(query)` — recall does keyword + LLM semantic search over stored notes.
- Usable from CLI or Telegram: "remember the electrician's number is …" → later: "what was the electrician's number?"

### 3.5 Known limitation & future phase
- Everything fires only while the laptop is on. Phase 3 option: move the scheduler + Telegram bot to a small cloud cron reading shared storage, so reminders work even with the laptop off. Design the scheduler as a separate module now so it can be lifted to the cloud later without rework.
- **Preferred cloud target:** Google Cloud free tier e2-micro VM (always-free, us-west1/us-central1/us-east1), created inside the user's existing Google Cloud / Firebase project.

---

## PART 4: Storage Abstraction — Local JSON now, Firestore later (REQUIRED design constraint)

**All reads/writes of worklog, todos, and memory data MUST go through a single storage module** (`storage.py` for the agent; a `storage.js` object in the tracker page). No other code touches files or databases directly.

- **v1 backend (default):** local JSON files as specified above (portable, offline, Drive-synced).
- **v2 backend (config switch):** Google Cloud Firestore, using the user's existing Firebase project.
  - Collections mirror the JSON structure: `entries`, `todos`, `memory` (same field names as the JSON schemas).
  - Tracker page: Firebase JS SDK loaded in the single HTML file; anonymous or email auth; offline persistence enabled so it still works without a connection.
  - Agent/scheduler: `google-cloud-firestore` Python client with a service-account key path in `.env`.
- Backend selection via one config value: `"storage": "json" | "firestore"`. Switching backends must require zero changes outside config.
- Provide a one-shot migration script `migrate_json_to_firestore.py` that uploads existing JSON data to Firestore, idempotently (safe to re-run).
- Rationale: with Firestore as the source of truth, the tracker page, laptop agent, cloud scheduler (free e2-micro VM), and a future Android app all share the same live data, and Google Drive backup becomes optional.

### Acceptance tests (Part 4)
- With `"storage": "json"` everything behaves exactly as Parts 1–3 specify.
- Flip config to `"storage": "firestore"`, run the migration script → tracker page and agent show the same data; adding a todo on the page appears to the agent (and vice versa) without file sync.

### Acceptance tests (Part C)
- Create a todo due in 2 minutes → desktop popup AND Telegram message arrive.
- Ignore the reminder → second reminder arrives ~15 min later.
- Reply "done" on Telegram → todo marked complete, confirmation received, work entry logged.
- At configured time, morning brief arrives on Telegram listing open and overdue todos.
- Tell agent "remember X" then later "recall X" from Telegram → correct answer returned.
