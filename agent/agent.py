from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

from llm_client import AnthropicToolClient
from platform_ops import open_app as platform_open_app
from scheduler import ReminderScheduler
from storage import load_config, make_storage


AGENT_DIR = Path(__file__).resolve().parent
LOG_DIR = AGENT_DIR / "logs"
DESTRUCTIVE_PATTERNS = [
    r"\brm\s+-rf\b",
    r"\bdel\b",
    r"\brmdir\b",
    r"\bRemove-Item\b",
    r"\bformat\b",
    r"\breg\s+(delete|add)\b",
    r"\bgit\s+reset\s+--hard\b",
    r"\bmove\b.+(Windows|System32|Users)",
]


def ensure_env() -> None:
    env_path = AGENT_DIR / ".env"
    if not env_path.exists():
        env_path.write_text("", encoding="utf-8")
    load_dotenv(env_path)

    # Check if at least one LLM API key is set
    keys = ["ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY", "GROK_API_KEY", "NVIDIA_API_KEY"]
    if not any(os.getenv(k) for k in keys):
        print("No LLM API keys found in .env")
        print("\nChoose a provider:")
        print("1. Anthropic (Claude) - Enter sk-ant-...")
        print("2. OpenAI (GPT-4) - Enter sk-...")
        print("3. Google (Gemini) - Enter API key")
        print("4. Grok (xAI) - Enter API key")
        print("5. NVIDIA Nemotron - Enter nvapi-...")
        choice = input("\nEnter choice (1-5) or skip for manual .env edit: ").strip()

        if choice == "1":
            key = input("Enter ANTHROPIC_API_KEY: ").strip()
            with env_path.open("a", encoding="utf-8") as f:
                f.write(f"\nANTHROPIC_API_KEY={key}\n")
            os.environ["ANTHROPIC_API_KEY"] = key
        elif choice == "2":
            key = input("Enter OPENAI_API_KEY: ").strip()
            with env_path.open("a", encoding="utf-8") as f:
                f.write(f"\nOPENAI_API_KEY={key}\n")
            os.environ["OPENAI_API_KEY"] = key
        elif choice == "3":
            key = input("Enter GOOGLE_API_KEY: ").strip()
            with env_path.open("a", encoding="utf-8") as f:
                f.write(f"\nGOOGLE_API_KEY={key}\n")
            os.environ["GOOGLE_API_KEY"] = key
        elif choice == "4":
            key = input("Enter GROK_API_KEY: ").strip()
            with env_path.open("a", encoding="utf-8") as f:
                f.write(f"\nGROK_API_KEY={key}\n")
            os.environ["GROK_API_KEY"] = key
        elif choice == "5":
            key = input("Enter NVIDIA_API_KEY: ").strip()
            with env_path.open("a", encoding="utf-8") as f:
                f.write(f"\nNVIDIA_API_KEY={key}\n")
            os.environ["NVIDIA_API_KEY"] = key


def select_provider_interactive() -> str | None:
    """Allow user to manually select an LLM provider at startup."""
    print("\n=== LLM Provider Selection ===")
    print("Use auto-fallback or pick a specific provider?")
    print("(auto)  - Try providers in order, fallback on error")
    print("(1)     - Anthropic (Claude)")
    print("(2)     - OpenAI (GPT-4o)")
    print("(3)     - Google (Gemini)")
    print("(4)     - Grok")
    print("(5)     - NVIDIA Nemotron Ultra")
    print("(6)     - NVIDIA Nemotron Super")

    choice = input("\nChoice [auto]: ").strip().lower() or "auto"

    providers = {
        "auto": None,
        "1": "anthropic",
        "2": "openai",
        "3": "google",
        "4": "grok",
        "5": "nvidia",
        "6": "nvidia",
    }
    return providers.get(choice)


def log_action(tool: str, args: Any, summary: str) -> None:
    LOG_DIR.mkdir(exist_ok=True)
    path = LOG_DIR / f"agent-{datetime.now().strftime('%Y-%m-%d')}.log"
    row = {"ts": datetime.now().isoformat(timespec="seconds"), "tool": tool, "args": args, "summary": summary[:1000]}
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


class LocalTools:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.storage = make_storage(AGENT_DIR, config)
        self.allowed_dirs = [self._resolve_dir(p) for p in config.get("allowed_dirs", [])]

    def _resolve_dir(self, value: str) -> Path:
        p = Path(os.path.expanduser(value))
        if not p.is_absolute():
            p = (AGENT_DIR / p)
        return p.resolve()

    def _resolve_allowed(self, path: str) -> Path:
        p = Path(os.path.expanduser(path))
        if not p.is_absolute():
            p = (AGENT_DIR / p)
        resolved = p.resolve()
        if not any(resolved == root or root in resolved.parents for root in self.allowed_dirs):
            raise PermissionError(f"Path is outside allowed directories: {resolved}")
        return resolved

    def _confirm_if_destructive(self, command: str) -> None:
        if not self.config.get("confirm_destructive", True):
            return
        if any(re.search(pattern, command, re.IGNORECASE) for pattern in DESTRUCTIVE_PATTERNS):
            print(f"Destructive command requested:\n{command}")
            if input("Run it? Type y to confirm: ").strip().lower() != "y":
                raise PermissionError("User declined destructive command.")

    def run_shell(self, command: str) -> dict[str, Any]:
        self._confirm_if_destructive(command)
        result = subprocess.run(command, shell=True, capture_output=True, text=True, cwd=str(self.allowed_dirs[0]), timeout=120)
        out = {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
        log_action("run_shell", {"command": command}, f"exit {result.returncode}")
        return out

    def read_file(self, path: str) -> str:
        resolved = self._resolve_allowed(path)
        data = resolved.read_text(encoding="utf-8")
        log_action("read_file", {"path": str(resolved)}, f"{len(data)} chars")
        return data

    def write_file(self, path: str, content: str) -> str:
        resolved = self._resolve_allowed(path)
        resolved.parent.mkdir(parents=True, exist_ok=True)
        resolved.write_text(content, encoding="utf-8")
        log_action("write_file", {"path": str(resolved)}, f"{len(content)} chars")
        return f"Wrote {resolved}"

    def list_dir(self, path: str) -> list[dict[str, Any]]:
        resolved = self._resolve_allowed(path)
        items = [{"name": p.name, "is_dir": p.is_dir(), "size": p.stat().st_size if p.is_file() else None} for p in resolved.iterdir()]
        log_action("list_dir", {"path": str(resolved)}, f"{len(items)} items")
        return items

    def open_app(self, name_or_path: str) -> str:
        log_action("open_app", {"name_or_path": name_or_path}, "opening")
        return platform_open_app(name_or_path)

    def web_search(self, query: str) -> str:
        return f"Web search is not configured locally. Query was: {query}"

    def log_work(self, title: str, desc: str = "", project: str = "", minutes: int = 0) -> dict[str, Any]:
        entry = self.storage.add_work_entry(title, desc, project, minutes)
        log_action("log_work", entry, title)
        return entry

    def add_todo(self, title: str, due: str, project: str = "", recurrence: str | None = None, remind_before_min: int = 30) -> dict[str, Any]:
        todo = self.storage.add_todo(title, project, due, recurrence, remind_before_min)
        log_action("add_todo", todo, title)
        return todo

    def list_todos(self, status: str = "open") -> list[dict[str, Any]]:
        todos = self.storage.todos().get("todos", [])
        if status != "all":
            todos = [t for t in todos if t.get("status") == status]
        todos.sort(key=lambda t: t.get("snooze_until") or t.get("due") or "")
        log_action("list_todos", {"status": status}, f"{len(todos)} results")
        return todos

    def complete_todo(self, query: str) -> dict[str, Any] | None:
        todo = self.storage.complete_todo(query)
        log_action("complete_todo", {"query": query}, todo.get("title") if todo else "not found")
        return todo

    def snooze_todo(self, query: str, until: str) -> dict[str, Any] | None:
        todo = self.storage.snooze_todo(query, until)
        log_action("snooze_todo", {"query": query, "until": until}, todo.get("title") if todo else "not found")
        return todo

    def remember(self, text: str, tags: list[str] | None = None) -> dict[str, Any]:
        note = self.storage.remember(text, tags)
        log_action("remember", note, text)
        return note

    def recall(self, query: str) -> list[dict[str, Any]]:
        notes = self.storage.recall(query)
        log_action("recall", {"query": query}, f"{len(notes)} results")
        return notes


def main() -> None:
    ensure_env()
    config = load_config(AGENT_DIR)
    tools = LocalTools(config)
    scheduler = ReminderScheduler(config, tools.storage)
    if config.get("scheduler", {}).get("enabled", True):
        scheduler.start()

    # Allow manual provider selection or use auto-fallback
    manual_provider = select_provider_interactive()
    if manual_provider:
        print(f"Using {manual_provider} (override auto-fallback)")

    tools_dict = {
        "run_shell": tools.run_shell,
        "read_file": tools.read_file,
        "write_file": tools.write_file,
        "open_app": tools.open_app,
        "list_dir": tools.list_dir,
        "log_work": tools.log_work,
        "add_todo": tools.add_todo,
        "list_todos": tools.list_todos,
        "complete_todo": tools.complete_todo,
        "snooze_todo": tools.snooze_todo,
        "remember": tools.remember,
        "recall": tools.recall,
    }

    # Initialize multi-provider LLM client
    from llm_client import MultiProviderLLMClient
    llm = MultiProviderLLMClient(config, tools_dict, manual_provider=manual_provider)
    print(f"✓ {llm.get_status()}")
    print("Type /reset, /quit, or a command.")

    while True:
        try:
            text = input("> ").strip()
            if not text:
                continue
            if text in {"/quit", "/exit"}:
                break
            if text == "/reset":
                llm.reset()
                print("Context cleared.")
                continue
            if text == "/provider":
                print(llm.get_status())
                continue
            print(llm.ask(text))
        except KeyboardInterrupt:
            break
        except Exception as exc:
            print(f"Error: {exc}")


if __name__ == "__main__":
    main()
