from __future__ import annotations

import json
import os
import logging
from pathlib import Path
from typing import Any, Callable
from abc import ABC, abstractmethod

from anthropic import Anthropic
from dotenv import load_dotenv

logger = logging.getLogger(__name__)

AGENT_DIR = Path(__file__).resolve().parent
load_dotenv(AGENT_DIR / ".env")

SYSTEM_PROMPT = """You are a local personal agent. Use tools for file, shell, app, web, worklog, todo, and memory tasks.
Keep answers concise. Never call destructive shell/file actions without relying on the tool confirmation gate.

Outbound-communication tools (send_whatsapp_message, and any future send_telegram_message /
send_mail) are two-step and confirm-before-send: your first call omits confirm (or passes
confirm=false), which only resolves the contact and returns a draft - it does NOT send
anything. You must show the recipient, their number/address, and the full message text to
the user verbatim and wait for their explicit yes/send reply. Only after that explicit
confirmation may you call the same tool again with confirm=true to actually send. Never set
confirm=true on the first call, never send without an explicit user reply, and never
paraphrase or shorten the draft you show them."""

TOOL_SCHEMA = [
    {
        "name": "run_shell",
        "description": "Run a shell command and return stdout/stderr.",
        "input_schema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]},
    },
    {
        "name": "read_file",
        "description": "Read a file from an allowed directory.",
        "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]},
    },
    {
        "name": "write_file",
        "description": "Write a file inside an allowed directory.",
        "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]},
    },
    {
        "name": "open_app",
        "description": "Open an app, file, URL, or folder.",
        "input_schema": {"type": "object", "properties": {"name_or_path": {"type": "string"}}, "required": ["name_or_path"]},
    },
    {
        "name": "list_dir",
        "description": "List a directory from an allowed path.",
        "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]},
    },
    {
        "name": "log_work",
        "description": "Append a worklog entry.",
        "input_schema": {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "desc": {"type": "string"},
                "project": {"type": "string"},
                "minutes": {"type": "integer"},
            },
            "required": ["title"],
        },
    },
    {
        "name": "add_todo",
        "description": "Add a to-do with due ISO datetime.",
        "input_schema": {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "project": {"type": "string"},
                "due": {"type": "string"},
                "recurrence": {"type": "string"},
                "remind_before_min": {"type": "integer"},
            },
            "required": ["title", "due"],
        },
    },
    {
        "name": "list_todos",
        "description": "List to-dos. status defaults to 'open'; pass 'done' or 'all' to see other/all to-dos.",
        "input_schema": {"type": "object", "properties": {"status": {"type": "string"}}, "required": []},
    },
    {
        "name": "complete_todo",
        "description": "Complete a todo by id or matching words.",
        "input_schema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
    },
    {
        "name": "snooze_todo",
        "description": "Snooze a todo by id or matching words until an ISO datetime.",
        "input_schema": {"type": "object", "properties": {"query": {"type": "string"}, "until": {"type": "string"}}, "required": ["query", "until"]},
    },
    {
        "name": "remember",
        "description": "Save a memory note.",
        "input_schema": {"type": "object", "properties": {"text": {"type": "string"}, "tags": {"type": "array", "items": {"type": "string"}}}, "required": ["text"]},
    },
    {
        "name": "recall",
        "description": "Recall memory notes by keyword query.",
        "input_schema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
    },
    {
        "name": "save_contact",
        "description": "Save a contact (name + phone/email) and write it out as a .vcf file.",
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "phone_number": {"type": "string"},
                "email": {"type": "string"},
                "first_name": {"type": "string"},
                "last_name": {"type": "string"},
            },
            "required": ["name"],
        },
    },
    {
        "name": "list_contacts",
        "description": "List saved contacts, optionally filtered by a name/phone-number search query.",
        "input_schema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": []},
    },
    {
        "name": "send_whatsapp_message",
        "description": (
            "Send a WhatsApp message to a saved contact by name, via the local WhatsApp bridge. "
            "Two-step confirm-before-send: first call without confirm (or confirm=false) to get a "
            "draft to show the user; only call again with confirm=true after they explicitly agree."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "contact_name": {"type": "string"},
                "message": {"type": "string"},
                "confirm": {"type": "boolean"},
            },
            "required": ["contact_name", "message"],
        },
    },
    {
        "name": "send_telegram_message",
        "description": (
            "Send a real Telegram DM to a saved contact by name, using the user's own "
            "Telegram account (not the bot) - works for any Telegram user, not just ones "
            "who've started the bot. Two-step confirm-before-send: first call without "
            "confirm (or confirm=false) to get a draft to show the user; only call again "
            "with confirm=true after they explicitly agree."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "contact_name": {"type": "string"},
                "message": {"type": "string"},
                "confirm": {"type": "boolean"},
            },
            "required": ["contact_name", "message"],
        },
    },
    {
        "name": "send_mail",
        "description": (
            "Send an email from one of the user's configured personal accounts (never a "
            "work/corporate account - those are blocked outright). contact_or_address may be "
            "a saved contact name or a raw email address. Two-step confirm-before-send: first "
            "call without confirm (or confirm=false) to get a full preview to show the user; "
            "only call again with confirm=true after they explicitly agree."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "account": {"type": "string", "description": "Which configured email_accounts key to send from."},
                "contact_or_address": {"type": "string"},
                "subject": {"type": "string"},
                "body": {"type": "string"},
                "cc": {"type": "string"},
                "confirm": {"type": "boolean"},
            },
            "required": ["account", "contact_or_address", "subject", "body"],
        },
    },
]


def serialize_block(block: Any) -> dict[str, Any]:
    if hasattr(block, "model_dump"):
        return block.model_dump()
    if hasattr(block, "to_dict"):
        return block.to_dict()
    return dict(block)


class LLMProvider(ABC):
    """Abstract base class for LLM providers."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]]):
        self.model = model
        self.tools = tools
        self.history: list[dict[str, Any]] = []

    def reset(self) -> None:
        self.history.clear()

    def seed_history(self, turns: list[dict[str, str]]) -> None:
        """Restore prior user/assistant turns (plain text only, no tool-call detail)
        so switching providers mid-conversation doesn't drop context."""
        self.history = [{"role": t["role"], "content": t["text"]} for t in turns]

    @abstractmethod
    def ask(self, text: str) -> str:
        """Send a message and return the response."""
        pass


class AnthropicProvider(LLMProvider):
    """Anthropic Claude provider."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]]):
        super().__init__(model, tools)
        api_key = os.getenv("ANTHROPIC_API_KEY")
        if not api_key:
            raise ValueError("ANTHROPIC_API_KEY not set")
        self.client = Anthropic(api_key=api_key)

    def ask(self, text: str) -> str:
        self.history.append({"role": "user", "content": text})
        for _ in range(6):
            message = self.client.messages.create(
                model=self.model,
                max_tokens=1800,
                system=SYSTEM_PROMPT,
                tools=TOOL_SCHEMA,
                messages=self.history,
            )
            self.history.append({"role": "assistant", "content": [serialize_block(block) for block in message.content]})
            tool_results = []
            for block in message.content:
                if block.type == "text":
                    continue
                if block.type == "tool_use":
                    fn = self.tools[block.name]
                    try:
                        result = fn(**block.input)
                    except Exception as exc:
                        result = {"error": str(exc)}
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": json.dumps(result, ensure_ascii=False, default=str),
                    })
            if not tool_results:
                return "\n".join(block.text for block in message.content if block.type == "text").strip()
            self.history.append({"role": "user", "content": tool_results})
        return "I stopped after several tool rounds. Try a narrower command."


class OpenAICompatibleProvider(LLMProvider):
    """Base for chat-completions APIs that speak the OpenAI tool-calling protocol
    (OpenAI, OpenRouter, Grok, NVIDIA). Runs a multi-round tool loop like AnthropicProvider."""

    client: Any

    def _build_client(self, api_key: str, base_url: str | None = None, default_headers: dict | None = None):
        try:
            from openai import OpenAI
        except ImportError:
            raise ImportError("openai library not installed. Run: pip install openai")
        kwargs: dict[str, Any] = {"api_key": api_key}
        if base_url:
            kwargs["base_url"] = base_url
        if default_headers:
            kwargs["default_headers"] = default_headers
        return OpenAI(**kwargs)

    def seed_history(self, turns: list[dict[str, str]]) -> None:
        self.history = [{"role": "system", "content": SYSTEM_PROMPT}]
        self.history.extend({"role": t["role"], "content": t["text"]} for t in turns)

    def ask(self, text: str) -> str:
        if not self.history:
            self.history.append({"role": "system", "content": SYSTEM_PROMPT})
        self.history.append({"role": "user", "content": text})
        tool_defs = [
            {
                "type": "function",
                "function": {
                    "name": schema["name"],
                    "description": schema["description"],
                    "parameters": schema["input_schema"],
                },
            }
            for schema in TOOL_SCHEMA
        ]

        for _ in range(6):
            response = self.client.chat.completions.create(
                model=self.model,
                messages=self.history,
                tools=tool_defs,
                tool_choice="auto",
                max_tokens=1024,
            )
            message = response.choices[0].message

            assistant_msg: dict[str, Any] = {"role": "assistant", "content": message.content}
            if message.tool_calls:
                assistant_msg["tool_calls"] = [tc.model_dump() for tc in message.tool_calls]
            self.history.append(assistant_msg)

            if not message.tool_calls:
                return (message.content or "").strip()

            for tool_call in message.tool_calls:
                fn_name = tool_call.function.name
                try:
                    fn_args = json.loads(tool_call.function.arguments or "{}")
                except json.JSONDecodeError:
                    fn_args = {}
                if fn_name in self.tools:
                    try:
                        result = self.tools[fn_name](**fn_args)
                    except Exception as exc:
                        result = {"error": str(exc)}
                else:
                    result = {"error": f"Unknown tool: {fn_name}"}
                self.history.append({
                    "role": "tool",
                    "tool_call_id": tool_call.id,
                    "content": json.dumps(result, ensure_ascii=False, default=str),
                })

        return "I stopped after several tool rounds. Try a narrower command."


class OpenAIProvider(OpenAICompatibleProvider):
    """OpenAI GPT provider."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]]):
        super().__init__(model, tools)
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise ValueError("OPENAI_API_KEY not set")
        self.client = self._build_client(api_key)


class GoogleProvider(LLMProvider):
    """Google Gemini provider (via the google-genai SDK)."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]]):
        super().__init__(model, tools)
        api_key = os.getenv("GOOGLE_API_KEY")
        if not api_key:
            raise ValueError("GOOGLE_API_KEY not set")
        try:
            from google import genai
            from google.genai import types
        except ImportError:
            raise ImportError("google-genai library not installed. Run: pip install google-genai")
        self._types = types
        self._client = genai.Client(api_key=api_key)

        function_declarations = [
            types.FunctionDeclaration(
                name=schema["name"],
                description=schema["description"],
                parameters=schema["input_schema"],
            )
            for schema in TOOL_SCHEMA
        ]
        self._chat_config = types.GenerateContentConfig(
            system_instruction=SYSTEM_PROMPT,
            tools=[types.Tool(function_declarations=function_declarations)],
        )
        self._chat = self._client.chats.create(model=model, config=self._chat_config)

    def seed_history(self, turns: list[dict[str, str]]) -> None:
        types = self._types
        history = [
            types.Content(role="model" if t["role"] == "assistant" else "user", parts=[types.Part.from_text(text=t["text"])])
            for t in turns
        ]
        self._chat = self._client.chats.create(model=self.model, config=self._chat_config, history=history)

    def ask(self, text: str) -> str:
        types = self._types
        message: Any = text

        for _ in range(6):
            response = self._chat.send_message(message)
            function_calls = response.function_calls
            if not function_calls:
                return (response.text or "").strip()

            parts = []
            for fc in function_calls:
                fn_name = fc.name
                fn_args = dict(fc.args) if fc.args else {}
                if fn_name in self.tools:
                    try:
                        result = self.tools[fn_name](**fn_args)
                    except Exception as exc:
                        result = {"error": str(exc)}
                else:
                    result = {"error": f"Unknown tool: {fn_name}"}
                parts.append(types.Part.from_function_response(name=fn_name, response={"result": result}))
            message = parts

        return "I stopped after several tool rounds. Try a narrower command."

    def reset(self) -> None:
        self.history.clear()
        self._chat = self._client.chats.create(model=self.model, config=self._chat_config)


class GrokProvider(OpenAICompatibleProvider):
    """Grok (xAI) provider."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]]):
        super().__init__(model, tools)
        api_key = os.getenv("GROK_API_KEY")
        if not api_key:
            raise ValueError("GROK_API_KEY not set")
        self.client = self._build_client(api_key, base_url="https://api.x.ai/v1")


class NVIDIAProvider(OpenAICompatibleProvider):
    """NVIDIA Nemotron provider."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]]):
        super().__init__(model, tools)
        api_key = os.getenv("NVIDIA_API_KEY") or os.getenv("NVIDIA_API_KEY_2")
        if not api_key:
            raise ValueError("NVIDIA_API_KEY or NVIDIA_API_KEY_2 not set")
        self.client = self._build_client(api_key, base_url="https://integrate.api.nvidia.com/v1")


class OpenRouterProvider(OpenAICompatibleProvider):
    """OpenRouter provider - supports 1000+ models via single API."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]]):
        super().__init__(model, tools)
        api_key = os.getenv("OPENROUTER_API_KEY")
        if not api_key:
            raise ValueError("OPENROUTER_API_KEY not set")
        self.client = self._build_client(
            api_key,
            base_url="https://openrouter.ai/api/v1",
            default_headers={"HTTP-Referer": "https://myagent.local", "X-Title": "MyPersonalAgent"},
        )


class MultiProviderLLMClient:
    """Multi-provider LLM client with fallback support."""

    PROVIDERS = {
        "openrouter": OpenRouterProvider,
        "anthropic": AnthropicProvider,
        "openai": OpenAIProvider,
        "google": GoogleProvider,
        "grok": GrokProvider,
        "nvidia": NVIDIAProvider,
    }

    def __init__(self,
                 config: dict[str, Any],
                 tools: dict[str, Callable[..., Any]],
                 manual_provider: str | None = None):
        """
        Initialize multi-provider client.

        Args:
            config: Config dict with 'llm_providers' list
            tools: Dict of available tools
            manual_provider: Force specific provider (overrides auto-fallback)
        """
        self.config = config
        self.tools = tools
        self.manual_provider = manual_provider or os.getenv("LLM_PROVIDER") or config.get("llm_provider")
        self.current_provider: LLMProvider | None = None
        self.current_provider_name: str | None = None
        # Providers that have failed an actual request in this session - skipped
        # by auto-fallback so it doesn't keep re-picking a provider that just failed.
        self._failed_providers: set[str] = set()
        # Plain-text turn log, independent of any one provider's internal history
        # format, so switching providers mid-conversation carries context over.
        self.turns: list[dict[str, str]] = []

        # Get provider list from config
        configured_providers = config.get("llm_providers", [
            {"provider": "anthropic", "model": "claude-opus-4-8"},
            {"provider": "openai", "model": "gpt-4o"},
            {"provider": "google", "model": "gemini-2.0-flash"},
            {"provider": "grok", "model": "grok-3"},
            {"provider": "nvidia", "model": "nemotron-ultra"},
        ])
        if self.manual_provider:
            preferred = [p for p in configured_providers if p.get("provider") == self.manual_provider]
            if preferred:
                self.providers = preferred + [p for p in configured_providers if p.get("provider") != self.manual_provider]
            else:
                self.providers = configured_providers
        elif config.get("llm_provider"):
            preferred = [p for p in configured_providers if p.get("provider") == config.get("llm_provider")]
            if preferred:
                self.providers = preferred + [p for p in configured_providers if p.get("provider") != config.get("llm_provider")]
            else:
                self.providers = configured_providers
        else:
            self.providers = configured_providers

        self.fallback_enabled = config.get("fallback_enabled", True)
        self._initialize_provider()

    def _initialize_provider(self) -> None:
        """Initialize the LLM provider (manual or first available)."""
        if self.manual_provider:
            # Manual provider selection
            for prov_config in self.providers:
                if prov_config["provider"] == self.manual_provider:
                    if self._load_provider(prov_config):
                        return
                    break
            logger.warning(f"Manual provider '{self.manual_provider}' not found or failed. Trying fallback providers.")

        # Auto-fallback mode
        self._try_providers()

    def _try_providers(self) -> None:
        """Try providers in order until one works, skipping ones already known to fail."""
        for prov_config in self.providers:
            if prov_config["provider"] in self._failed_providers:
                continue
            if not self._provider_available(prov_config):
                self._failed_providers.add(prov_config["provider"])
                continue
            if self._load_provider(prov_config, suppress_error=True):
                return

        # If all fail, raise error
        raise RuntimeError("All LLM providers failed. Check API keys in .env")

    def _provider_available(self, prov_config: dict) -> bool:
        provider_name = prov_config.get("provider")
        if not provider_name:
            return False
        if provider_name == "anthropic":
            return bool(os.getenv("ANTHROPIC_API_KEY"))
        if provider_name == "openai":
            return bool(os.getenv("OPENAI_API_KEY"))
        if provider_name == "google":
            return bool(os.getenv("GOOGLE_API_KEY"))
        if provider_name == "grok":
            return bool(os.getenv("GROK_API_KEY"))
        if provider_name == "nvidia":
            return bool(os.getenv("NVIDIA_API_KEY") or os.getenv("NVIDIA_API_KEY_2"))
        if provider_name == "openrouter":
            return bool(os.getenv("OPENROUTER_API_KEY"))
        return True

    def _load_provider(self, prov_config: dict, suppress_error: bool = False) -> bool:
        """Load a specific provider. Returns True if successful."""
        provider_name = prov_config["provider"]
        model = prov_config["model"]

        try:
            ProviderClass = self.PROVIDERS[provider_name]
            self.current_provider = ProviderClass(model, self.tools)
            self.current_provider_name = provider_name
            if self.turns:
                self.current_provider.seed_history(self.turns)
            logger.info(f"✓ Loaded {provider_name} ({model})")
            return True
        except Exception as e:
            if not suppress_error:
                logger.error(f"✗ Failed to load {provider_name}: {e}")
            else:
                logger.debug(f"✗ {provider_name}: {e}")
            return False

    def reset(self) -> None:
        """Reset conversation history."""
        self.turns.clear()
        if self.current_provider:
            self.current_provider.reset()

    def ask(self, text: str) -> str:
        """Ask the current provider, falling through the whole chain on failure if enabled."""
        if not self.current_provider:
            raise RuntimeError("No LLM provider initialized")

        last_exc: Exception | None = None
        for _ in range(len(self.providers) + 1):
            try:
                reply = self.current_provider.ask(text)
                self.turns.append({"role": "user", "text": text})
                self.turns.append({"role": "assistant", "text": reply})
                return reply
            except Exception as e:
                last_exc = e
                if not self.fallback_enabled:
                    raise
                if self.manual_provider:
                    logger.warning(f"Provider {self.current_provider_name} failed: {e}. Trying fallback...")
                    self._failed_providers.add(self.current_provider_name)
                    try:
                        self._try_providers()
                    except RuntimeError:
                        break
                else:
                    logger.warning(f"Provider {self.current_provider_name} failed: {e}. Trying fallback...")
                    self._failed_providers.add(self.current_provider_name)
                    try:
                        self._try_providers()
                    except RuntimeError:
                        break
        raise last_exc  # type: ignore[misc]

    def set_manual_provider(self, provider_name: str | None) -> None:
        """Pin to a specific provider, or pass None to return to auto-fallback."""
        self.manual_provider = provider_name
        self._failed_providers.clear()
        self._initialize_provider()

    def get_status(self) -> str:
        """Return current provider status."""
        return f"Provider: {self.current_provider_name} ({self.current_provider.model if self.current_provider else 'N/A'})"


# For backward compatibility with agent.py
class AnthropicToolClient:
    """Backward-compatible wrapper using multi-provider client."""

    def __init__(self, model: str, tools: dict[str, Callable[..., Any]], config: dict[str, Any] | None = None):
        if config is None:
            config = {"llm_providers": [{"provider": "anthropic", "model": model}]}
        self.multi_client = MultiProviderLLMClient(config, tools)

    def reset(self) -> None:
        self.multi_client.reset()

    def ask(self, text: str) -> str:
        return self.multi_client.ask(text)
