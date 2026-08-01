from __future__ import annotations

import unittest
from unittest.mock import patch

from llm_client import MultiProviderLLMClient


class DummyProvider:
    def __init__(self, model: str, tools: dict):
        self.model = model
        self.tools = tools

    def reset(self) -> None:
        return None

    def ask(self, text: str) -> str:
        return f"ok:{self.model}"


class FailingProvider(DummyProvider):
    def __init__(self, model: str, tools: dict):
        raise RuntimeError("provider unavailable")


class LLMProviderSelectionTests(unittest.TestCase):
    def test_configured_provider_is_used_first(self) -> None:
        config = {
            "llm_provider": "anthropic",
            "llm_model": "claude-test",
            "llm_providers": [
                {"provider": "openrouter", "model": "openrouter-model"},
                {"provider": "anthropic", "model": "claude-test"},
            ],
        }

        providers = {
            "openrouter": DummyProvider,
            "anthropic": DummyProvider,
        }

        with patch.object(MultiProviderLLMClient, "PROVIDERS", providers):
            client = MultiProviderLLMClient(config, {})

        self.assertEqual(client.current_provider_name, "anthropic")
        self.assertEqual(client.providers[0]["provider"], "anthropic")

    def test_manual_provider_falls_back_when_selected_provider_fails(self) -> None:
        config = {
            "llm_provider": "anthropic",
            "llm_model": "claude-test",
            "llm_providers": [
                {"provider": "anthropic", "model": "claude-test"},
                {"provider": "openrouter", "model": "openrouter-model"},
            ],
        }

        providers = {
            "anthropic": FailingProvider,
            "openrouter": DummyProvider,
        }

        with patch.object(MultiProviderLLMClient, "PROVIDERS", providers):
            client = MultiProviderLLMClient(config, {}, manual_provider="anthropic")
            reply = client.ask("hello")

        self.assertEqual(reply, "ok:openrouter-model")


if __name__ == "__main__":
    unittest.main()
