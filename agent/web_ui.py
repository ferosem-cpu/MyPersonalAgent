"""Web UI for MyPersonalAgent - Model selection and chat interface."""

from flask import Flask, render_template, jsonify, request, session
import os
import json
from pathlib import Path
from datetime import datetime
from dotenv import load_dotenv

from llm_client import MultiProviderLLMClient
from storage import load_config, make_storage
from platform_ops import open_app as platform_open_app

AGENT_DIR = Path(__file__).resolve().parent
load_dotenv(AGENT_DIR / ".env")

# Available models per provider (verified live against each provider's own
# model-list endpoint / catalog - update periodically as providers retire models)
AVAILABLE_MODELS = {
    "openrouter": [
        "anthropic/claude-sonnet-5",
        "anthropic/claude-opus-4.8",
        "openai/gpt-5.6-sol",
        "google/gemini-3.5-flash",
        "meta-llama/llama-4-maverick",
        "mistralai/mistral-medium-3-5",
        "deepseek/deepseek-v3.2",
        "x-ai/grok-4.3",
    ],
    "anthropic": [
        "claude-opus-4-8",
        "claude-sonnet-5",
        "claude-haiku-4-5-20251001",
    ],
    "openai": [
        "gpt-5.6-sol",
        "gpt-4o",
        "gpt-4o-mini",
    ],
    "google": [
        "gemini-3.5-flash",
        "gemini-2.5-pro",
    ],
    "grok": ["grok-4.3", "grok-4.5"],
    "nvidia": [
        "nvidia/llama-3.3-nemotron-super-49b-v1.5",
        "meta/llama-3.1-8b-instruct",
    ],
}

PROVIDER_METADATA = {
    "openrouter": {"label": "OpenRouter", "env_var": "OPENROUTER_API_KEY", "default_model": "anthropic/claude-sonnet-5"},
    "anthropic": {"label": "Anthropic", "env_var": "ANTHROPIC_API_KEY", "default_model": "claude-opus-4-8"},
    "openai": {"label": "OpenAI", "env_var": "OPENAI_API_KEY", "default_model": "gpt-4o"},
    "google": {"label": "Google", "env_var": "GOOGLE_API_KEY", "default_model": "gemini-3.5-flash"},
    "grok": {"label": "Grok", "env_var": "GROK_API_KEY", "default_model": "grok-4.3"},
    "nvidia": {"label": "NVIDIA", "env_var": "NVIDIA_API_KEY", "default_model": "nvidia/llama-3.3-nemotron-super-49b-v1.5"},
}

DEFAULT_PROVIDER_CONFIGS = [
    {"provider": "openrouter", "model": "anthropic/claude-sonnet-5", "enabled": True, "key_env": "OPENROUTER_API_KEY"},
    {"provider": "anthropic", "model": "claude-opus-4-8", "enabled": True, "key_env": "ANTHROPIC_API_KEY"},
    {"provider": "openai", "model": "gpt-4o", "enabled": True, "key_env": "OPENAI_API_KEY"},
    {"provider": "google", "model": "gemini-3.5-flash", "enabled": True, "key_env": "GOOGLE_API_KEY"},
    {"provider": "grok", "model": "grok-4.3", "enabled": True, "key_env": "GROK_API_KEY"},
    {"provider": "nvidia", "model": "nvidia/llama-3.3-nemotron-super-49b-v1.5", "enabled": True, "key_env": "NVIDIA_API_KEY"},
]

app = Flask(__name__)
app.secret_key = "myagent-secret"
app.jinja_env.cache = None
app.config['SEND_FILE_MAX_AGE_DEFAULT'] = 0
app.config['TEMPLATES_AUTO_RELOAD'] = True

# Global state
current_llm_client = None
current_provider = None
current_model = None
tools = None


def get_provider_configs(config=None):
    """Return provider configs from the agent config, falling back to built-in defaults."""
    if config is None:
        config = load_config(AGENT_DIR)
    providers = config.get("llm_providers") or DEFAULT_PROVIDER_CONFIGS.copy()
    normalized = []
    for entry in providers:
        provider_name = entry.get("provider")
        if not provider_name:
            continue
        metadata = PROVIDER_METADATA.get(provider_name, {})
        normalized_entry = {
            "provider": provider_name,
            "model": entry.get("model") or metadata.get("default_model", ""),
            "enabled": bool(entry.get("enabled", True)),
            "key_env": entry.get("key_env") or metadata.get("env_var", ""),
        }
        normalized.append(normalized_entry)
    if not normalized:
        normalized = [p.copy() for p in DEFAULT_PROVIDER_CONFIGS]
    return normalized


def save_provider_configs(config, providers, default_provider=None, default_model=None):
    """Persist provider configuration back to config.json."""
    config["llm_providers"] = providers
    if default_provider:
        config["llm_provider"] = default_provider
    if default_model:
        config["llm_model"] = default_model
    (AGENT_DIR / "config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")


def detect_available_providers():
    """Detect which enabled providers have API keys configured."""
    config = load_config(AGENT_DIR)
    env_keys = {}
    for provider in get_provider_configs(config):
        env_var = provider.get("key_env") or PROVIDER_METADATA.get(provider.get("provider", {}), {}).get("env_var")
        if not env_var:
            continue
        env_keys[provider["provider"]] = os.getenv(env_var) or os.getenv(env_var.replace("_API_KEY", "_API_KEY_2"))
    return {k: bool(v) for k, v in env_keys.items() if k in {p["provider"] for p in get_provider_configs(config)}}


def initialize_llm(provider, model):
    """Initialize LLM client with selected provider/model."""
    global current_llm_client, current_provider, current_model, tools

    config = load_config(AGENT_DIR)
    providers = get_provider_configs(config)
    existing = None
    for entry in providers:
        if entry["provider"] == provider:
            existing = entry
            break
    if existing is None:
        providers.append({
            "provider": provider,
            "model": model,
            "enabled": True,
            "key_env": PROVIDER_METADATA.get(provider, {}).get("env_var", ""),
        })
    else:
        existing["model"] = model
        existing["enabled"] = True
    for entry in providers:
        if entry.get("provider") == provider:
            entry["enabled"] = True
            entry["model"] = model
    config["llm_provider"] = provider
    config["llm_model"] = model
    config["llm_providers"] = providers
    save_provider_configs(config, providers, provider, model)

    # Initialize tools
    if tools is None:
        from agent import LocalTools
        tools = LocalTools(config)

    # Create LLM client
    current_llm_client = MultiProviderLLMClient(
        config,
        {
            "run_shell": tools.run_shell,
            "read_file": tools.read_file,
            "write_file": tools.write_file,
            "open_app": tools.open_app,
            "open_url": tools.open_url,
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
            "drive_search": tools.drive_search,
            "drive_upload": tools.drive_upload,
            "drive_download": tools.drive_download,
            "drive_share_link": tools.drive_share_link,
        },
        manual_provider=provider
    )
    current_provider = provider
    current_model = model


@app.route("/")
def index():
    """Serve the web UI."""
    available = detect_available_providers()
    response = app.make_response(render_template(
        "index.html",
        providers_json=json.dumps(available),
        models_json=json.dumps(AVAILABLE_MODELS)
    ))
    response.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate'
    response.headers['Pragma'] = 'no-cache'
    response.headers['Expires'] = '0'
    return response


@app.route("/api/initialize", methods=["POST"])
def api_initialize():
    """Initialize LLM with selected provider/model."""
    try:
        data = request.json
        provider = data.get("provider")
        model = data.get("model")

        if not provider or not model:
            return jsonify({"success": False, "error": "Provider and model required"})

        initialize_llm(provider, model)
        return jsonify({
            "success": True,
            "provider": current_provider,
            "model": current_model
        })
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


@app.route("/api/chat", methods=["POST"])
def api_chat():
    """Send message to agent."""
    try:
        if not current_llm_client:
            return jsonify({"success": False, "error": "Agent not initialized"})

        data = request.json
        message = data.get("message")

        if not message:
            return jsonify({"success": False, "error": "Message required"})

        response = current_llm_client.ask(message)
        return jsonify({"success": True, "response": response})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


@app.route("/api/get-keys", methods=["GET"])
def api_get_keys():
    """Get current API keys from .env (masked)."""
    try:
        env_path = AGENT_DIR / ".env"
        keys = {
            "OPENROUTER_API_KEY": os.getenv("OPENROUTER_API_KEY") or "",
            "ANTHROPIC_API_KEY": os.getenv("ANTHROPIC_API_KEY") or "",
            "OPENAI_API_KEY": os.getenv("OPENAI_API_KEY") or "",
            "GOOGLE_API_KEY": os.getenv("GOOGLE_API_KEY") or "",
            "GROK_API_KEY": os.getenv("GROK_API_KEY") or "",
            "NVIDIA_API_KEY": os.getenv("NVIDIA_API_KEY") or "",
        }
        # Mask keys for security (show only first and last 4 chars)
        masked = {}
        for k, v in keys.items():
            if v and len(v) > 8:
                masked[k] = v[:4] + "..." + v[-4:]
            elif v:
                masked[k] = "***"
            else:
                masked[k] = ""
        return jsonify({"success": True, "keys": masked})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


@app.route("/api/get-providers", methods=["GET"])
def api_get_providers():
    """Return persisted provider configuration."""
    try:
        config = load_config(AGENT_DIR)
        providers = get_provider_configs(config)
        return jsonify({"success": True, "providers": providers})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


@app.route("/whatsapp-setup")
def whatsapp_setup():
    """QR pairing page for the WhatsApp bridge (PLAN_V2 Task 5.2)."""
    return render_template("whatsapp_setup.html")


@app.route("/api/whatsapp/status", methods=["GET"])
def api_whatsapp_status():
    """Proxies the bridge's /status - keeps WA_BRIDGE_KEY server-side, never sent to the browser."""
    from services.whatsapp import wa_status
    try:
        return jsonify({"success": True, **wa_status()})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


@app.route("/api/whatsapp/qr", methods=["GET"])
def api_whatsapp_qr():
    from services.whatsapp import wa_qr
    try:
        qr = wa_qr()
        return jsonify({"success": True, "qr": qr})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


@app.route("/api/save-providers", methods=["POST"])
def api_save_providers():
    """Save provider configuration and default selection to config.json."""
    try:
        data = request.json or {}
        providers = data.get("providers", [])
        default_provider = data.get("default_provider")
        default_model = data.get("default_model")
        config = load_config(AGENT_DIR)
        save_provider_configs(config, providers, default_provider, default_model)
        if default_provider:
            os.environ["LLM_PROVIDER"] = default_provider
        if default_model:
            os.environ["LLM_MODEL"] = default_model
        return jsonify({"success": True, "message": "Providers saved successfully"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


def run_web_ui(debug=False, port=5000):
    """Run the web UI server."""
    print(f"\nMyPersonalAgent Web UI running at http://localhost:{port}")
    print("   Open this URL in your browser to get started")
    app.run(debug=debug, port=port, use_reloader=False)


if __name__ == "__main__":
    run_web_ui(debug=True, port=5000)
