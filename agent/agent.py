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
        todos = [t for t in self.storage.todos().get("todos", []) if not t.get("deleted")]
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

    def save_contact(
        self,
        name: str,
        phone_number: str | None = None,
        email: str | None = None,
        telegram_user_id: str | int | None = None,
        first_name: str | None = None,
        last_name: str | None = None,
        whatsapp_number: str | None = None,
        email_accounts_note: str | None = None,
    ) -> dict[str, Any]:
        contact = self.storage.add_contact(
            name=name,
            phone_number=phone_number,
            email=email,
            telegram_user_id=telegram_user_id,
            first_name=first_name,
            last_name=last_name,
            whatsapp_number=whatsapp_number,
            email_accounts_note=email_accounts_note,
        )
        log_action("save_contact", contact, name)
        return contact

    def list_contacts(self, query: str | None = None) -> list[dict[str, Any]]:
        contacts = self.storage.contacts().get("contacts", [])
        if query:
            q = query.lower()
            contacts = [
                c for c in contacts
                if q in (c.get("name") or "").lower() or q in (c.get("phone_number") or "")
            ]
        log_action("list_contacts", {"query": query}, f"{len(contacts)} results")
        return contacts

    def send_whatsapp_message(self, contact_name: str, message: str, confirm: bool = False) -> dict[str, Any]:
        """Two-step confirm-before-send (PLAN_V2 Ground Rule 2).

        Call 1 (confirm=False, the default): resolves the contact and returns a
        draft for the model to show the user verbatim, asking them to confirm.
        Call 2 (confirm=True): only after the user has explicitly agreed, actually
        sends via the WhatsApp bridge and logs the send to the worklog.
        """
        from services.contacts_resolve import resolve_contact, whatsapp_number_for

        contact = resolve_contact(self.storage, contact_name)
        if contact is None:
            result = {"status": "not_found", "message": f"No contact matching '{contact_name}'."}
            log_action("send_whatsapp_message", {"contact_name": contact_name}, result["message"])
            return result
        if isinstance(contact, list):
            result = {
                "status": "ambiguous",
                "candidates": [c.get("name") for c in contact],
                "message": "Multiple contacts match - ask the user which one they mean.",
            }
            log_action("send_whatsapp_message", {"contact_name": contact_name}, "ambiguous")
            return result

        number = whatsapp_number_for(contact)
        if not number:
            result = {"status": "error", "message": f"{contact.get('name')} has no phone/WhatsApp number on file."}
            log_action("send_whatsapp_message", {"contact_name": contact_name}, result["message"])
            return result

        if not confirm:
            result = {
                "status": "confirm_required",
                "contact": contact.get("name"),
                "number": number,
                "message_text": message,
                "instruction": (
                    "Show the recipient name, number, and full message text to the user verbatim "
                    "and ask them to confirm. Only call this tool again with confirm=true after "
                    "they explicitly reply yes/send. Never send without that explicit confirmation."
                ),
            }
            log_action("send_whatsapp_message", {"contact_name": contact_name, "confirm": False}, "draft shown")
            return result

        from services.whatsapp import send_whatsapp

        send_result = send_whatsapp(number, message)
        self.storage.add_work_entry(
            title=f"WhatsApp to {contact.get('name')}",
            desc=message,
            project="comms",
            minutes=0,
        )
        result = {"status": "sent", "contact": contact.get("name"), "number": number, **send_result}
        log_action("send_whatsapp_message", {"contact_name": contact_name, "confirm": True}, f"sent to {contact.get('name')}")
        return result

    def send_telegram_message(self, contact_name: str, message: str, confirm: bool = False) -> dict[str, Any]:
        """Same two-step confirm-before-send pattern as send_whatsapp_message. Uses the
        user's own Telegram account via Telethon (services/telegram_user.py) - this can
        message any Telegram user, not just ones who've started the bot."""
        from services.contacts_resolve import resolve_contact

        contact = resolve_contact(self.storage, contact_name)
        if contact is None:
            result = {"status": "not_found", "message": f"No contact matching '{contact_name}'."}
            log_action("send_telegram_message", {"contact_name": contact_name}, result["message"])
            return result
        if isinstance(contact, list):
            result = {
                "status": "ambiguous",
                "candidates": [c.get("name") for c in contact],
                "message": "Multiple contacts match - ask the user which one they mean.",
            }
            log_action("send_telegram_message", {"contact_name": contact_name}, "ambiguous")
            return result

        target = contact.get("phone_number") or contact.get("telegram_user_id")
        if not target:
            result = {"status": "error", "message": f"{contact.get('name')} has no phone number or Telegram ID on file."}
            log_action("send_telegram_message", {"contact_name": contact_name}, result["message"])
            return result

        if not confirm:
            result = {
                "status": "confirm_required",
                "contact": contact.get("name"),
                "target": target,
                "message_text": message,
                "instruction": (
                    "Show the recipient name, target, and full message text to the user verbatim "
                    "and ask them to confirm. Only call this tool again with confirm=true after "
                    "they explicitly reply yes/send. Never send without that explicit confirmation."
                ),
            }
            log_action("send_telegram_message", {"contact_name": contact_name, "confirm": False}, "draft shown")
            return result

        from services.telegram_user import send_telegram_dm

        send_result = send_telegram_dm(str(target), message)
        self.storage.add_work_entry(
            title=f"Telegram DM to {contact.get('name')}",
            desc=message,
            project="comms",
            minutes=0,
        )
        result = {"status": "sent", "contact": contact.get("name"), **send_result}
        log_action("send_telegram_message", {"contact_name": contact_name, "confirm": True}, f"sent to {contact.get('name')}")
        return result

    def send_mail(
        self,
        account: str,
        contact_or_address: str,
        subject: str,
        body: str,
        cc: str | None = None,
        confirm: bool = False,
    ) -> dict[str, Any]:
        """Same two-step confirm-before-send pattern. `contact_or_address` may be a
        saved contact name (resolved via resolve_contact) or a raw email address."""
        import re

        from services.contacts_resolve import resolve_contact

        if re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", contact_or_address):
            to_address = contact_or_address
            display_name = contact_or_address
        else:
            contact = resolve_contact(self.storage, contact_or_address)
            if contact is None:
                result = {"status": "not_found", "message": f"No contact matching '{contact_or_address}'."}
                log_action("send_mail", {"contact_or_address": contact_or_address}, result["message"])
                return result
            if isinstance(contact, list):
                result = {
                    "status": "ambiguous",
                    "candidates": [c.get("name") for c in contact],
                    "message": "Multiple contacts match - ask the user which one they mean.",
                }
                log_action("send_mail", {"contact_or_address": contact_or_address}, "ambiguous")
                return result
            to_address = contact.get("email")
            display_name = contact.get("name")
            if not to_address:
                result = {"status": "error", "message": f"{display_name} has no email address on file."}
                log_action("send_mail", {"contact_or_address": contact_or_address}, result["message"])
                return result

        if not confirm:
            result = {
                "status": "confirm_required",
                "account": account,
                "to": to_address,
                "recipient_name": display_name,
                "subject": subject,
                "body": body,
                "cc": cc,
                "instruction": (
                    "Show the full email preview (from account, to, subject, body, cc if any) "
                    "to the user verbatim and ask them to confirm. Only call this tool again "
                    "with confirm=true after they explicitly reply yes/send."
                ),
            }
            log_action("send_mail", {"account": account, "to": to_address, "confirm": False}, "draft shown")
            return result

        from services.mailer import send_email

        send_result = send_email(self.config, account, to_address, subject, body, cc)
        self.storage.add_work_entry(
            title=f"Email to {display_name}",
            desc=f"Subject: {subject}",
            project="comms",
            minutes=0,
        )
        result = {"status": "sent", "to": to_address, **send_result}
        log_action("send_mail", {"account": account, "to": to_address, "confirm": True}, f"sent to {to_address}")
        return result


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
        "save_contact": tools.save_contact,
        "list_contacts": tools.list_contacts,
        "send_whatsapp_message": tools.send_whatsapp_message,
        "send_telegram_message": tools.send_telegram_message,
        "send_mail": tools.send_mail,
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
