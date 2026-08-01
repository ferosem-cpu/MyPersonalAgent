"""Web UI for MyPersonalAgent - Model selection and chat interface."""

from flask import Flask, render_template_string, jsonify, request, session
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
        },
        manual_provider=provider
    )
    current_provider = provider
    current_model = model


HTML_TEMPLATE = """
<!DOCTYPE html>
<html>
<head>
    <title>MyPersonalAgent</title>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
        }
        .container {
            width: 100%;
            max-width: 1200px;
            display: flex;
            flex-direction: column;
            height: 100vh;
        }
        .header {
            background: rgba(0,0,0,0.1);
            padding: 20px;
            color: white;
            border-bottom: 1px solid rgba(255,255,255,0.1);
        }
        .header h1 {
            margin-bottom: 5px;
        }
        .header p {
            font-size: 0.9em;
            opacity: 0.9;
        }
        .main {
            display: flex;
            flex: 1;
            gap: 20px;
            padding: 20px;
            overflow: hidden;
        }
        .sidebar {
            width: 300px;
            background: white;
            border-radius: 8px;
            padding: 20px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.2);
            overflow-y: auto;
        }
        .sidebar h3 {
            margin-bottom: 15px;
            color: #333;
            font-size: 0.9em;
            text-transform: uppercase;
            letter-spacing: 1px;
        }
        .section {
            margin-bottom: 25px;
            padding-bottom: 20px;
            border-bottom: 1px solid #eee;
        }
        .section:last-child {
            border-bottom: none;
        }
        .tab-buttons {
            display: flex;
            gap: 5px;
            margin-bottom: 15px;
        }
        .tab-btn {
            flex: 1;
            padding: 8px;
            font-size: 0.8em;
            background: #f0f0f0;
            border: 1px solid #ddd;
            cursor: pointer;
            border-radius: 4px;
            transition: all 0.2s;
        }
        .tab-btn.active {
            background: #667eea;
            color: white;
            border-color: #667eea;
        }
        .tab-content {
            display: none;
        }
        .tab-content.active {
            display: block;
        }
        .api-key-input {
            width: 100%;
            padding: 8px;
            border: 1px solid #ddd;
            border-radius: 4px;
            margin-bottom: 8px;
            font-size: 0.85em;
        }
        .save-status {
            font-size: 0.8em;
            padding: 8px;
            border-radius: 4px;
            margin-top: 10px;
            display: none;
        }
        .save-status.success {
            background: #d4edda;
            color: #155724;
            display: block;
        }
        .save-status.error {
            background: #f8d7da;
            color: #721c24;
            display: block;
        }
        .provider-status {
            font-size: 0.8em;
            margin: 10px 0;
            padding: 8px;
            border-radius: 4px;
            background: #f5f5f5;
        }
        .provider-status.active {
            background: #d4edda;
            color: #155724;
        }
        .provider-status.inactive {
            background: #f8d7da;
            color: #721c24;
        }
        select {
            width: 100%;
            padding: 10px;
            border: 1px solid #ddd;
            border-radius: 4px;
            margin-bottom: 10px;
            font-size: 0.9em;
        }
        button {
            width: 100%;
            padding: 10px;
            background: #667eea;
            color: white;
            border: none;
            border-radius: 4px;
            cursor: pointer;
            font-size: 0.9em;
            transition: background 0.2s;
        }
        button:hover {
            background: #5568d3;
        }
        button:disabled {
            background: #ccc;
            cursor: not-allowed;
        }
        .chat-container {
            flex: 1;
            display: flex;
            flex-direction: column;
            background: white;
            border-radius: 8px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.2);
            overflow: hidden;
        }
        .messages {
            flex: 1;
            overflow-y: auto;
            padding: 20px;
            display: flex;
            flex-direction: column;
            gap: 15px;
        }
        .message {
            display: flex;
            gap: 10px;
            animation: fadeIn 0.3s;
        }
        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(10px); }
            to { opacity: 1; transform: translateY(0); }
        }
        .message.user {
            justify-content: flex-end;
        }
        .message.assistant {
            justify-content: flex-start;
        }
        .message-content {
            max-width: 70%;
            padding: 12px 15px;
            border-radius: 8px;
            word-wrap: break-word;
            white-space: pre-wrap;
        }
        .message.user .message-content {
            background: #667eea;
            color: white;
        }
        .message.assistant .message-content {
            background: #f0f0f0;
            color: #333;
        }
        .input-area {
            padding: 20px;
            border-top: 1px solid #eee;
            display: flex;
            gap: 10px;
        }
        input[type="text"] {
            flex: 1;
            padding: 12px 15px;
            border: 1px solid #ddd;
            border-radius: 4px;
            font-size: 0.95em;
        }
        input[type="text"]:focus {
            outline: none;
            border-color: #667eea;
            box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
        }
        .send-btn {
            width: auto;
            padding: 12px 30px;
        }
        .loading {
            display: inline-block;
            width: 20px;
            height: 20px;
            border: 2px solid #f3f3f3;
            border-top: 2px solid #667eea;
            border-radius: 50%;
            animation: spin 1s linear infinite;
        }
        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }
        .status {
            font-size: 0.85em;
            color: #666;
            padding: 8px 12px;
            background: #f9f9f9;
            border-radius: 4px;
            margin-top: 10px;
        }
        .error {
            color: #d32f2f;
            padding: 10px;
            background: #ffebee;
            border-radius: 4px;
            margin-bottom: 10px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🤖 MyPersonalAgent</h1>
            <p>Multi-provider AI assistant with tool access</p>
        </div>
        <div class="main">
            <div class="sidebar">
                <div class="tab-buttons">
                    <button class="tab-btn active" onclick="switchTab('agent', this)">Agent</button>
                    <button class="tab-btn" onclick="switchTab('keys', this)">API Keys</button>
                </div>

                <div id="agent-tab" class="tab-content active">
                    <div class="section">
                        <h3>📡 Provider</h3>
                        <select id="providerSelect" onchange="onProviderChange()">
                            <option value="">Select Provider...</option>
                        </select>
                        <div id="availableProviders"></div>
                    </div>
                    <div class="section">
                        <h3>🎯 Model</h3>
                        <select id="modelSelect" onchange="onModelChange()"></select>
                    </div>
                    <button onclick="initializeAgent()" id="initBtn">Initialize Agent</button>
                    <div class="status" id="status">Not initialized</div>
                </div>

                <div id="keys-tab" class="tab-content">
                    <div class="section">
                        <h3>🔑 Providers & API Keys</h3>
                        <div id="providerEditor"></div>
                        <button onclick="addProviderRow()" style="width: 100%; margin-top: 10px;">Add Provider</button>
                        <button onclick="saveApiKeys()" style="width: 100%; margin-top: 10px;">Save Providers</button>
                        <div class="save-status" id="saveStatus"></div>
                    </div>
                </div>
            </div>
            <div class="chat-container">
                <div class="messages" id="messages">
                    <div class="message assistant">
                        <div class="message-content">
                            👋 Welcome! Select a provider and model, then click "Initialize Agent" to get started.
                        </div>
                    </div>
                </div>
                <div class="input-area">
                    <input type="text" id="input" placeholder="Type your command..." onkeypress="onKeyPress(event)" disabled>
                    <button class="send-btn" onclick="sendMessage()" id="sendBtn" disabled>Send</button>
                </div>
            </div>
        </div>
    </div>

    <script>
        const providers = {{ providers_json|safe }};
        const models = {{ models_json|safe }};

        let initialized = false;
        let currentProvider = null;
        let currentModel = null;
        let configuredProviders = [];

        const PROVIDER_LABELS = {
            openrouter: 'OpenRouter',
            anthropic: 'Anthropic',
            openai: 'OpenAI',
            google: 'Google',
            grok: 'Grok',
            nvidia: 'NVIDIA'
        };

        window.onload = function() {
            loadProviders();
        };

        function loadProviders() {
            const select = document.getElementById('providerSelect');
            const available = document.getElementById('availableProviders');
            available.innerHTML = '';
            select.innerHTML = '<option value="">Select Provider...</option>';

            Object.entries(providers).forEach(([name, hasKey]) => {
                const option = document.createElement('option');
                option.value = name;
                option.textContent = (hasKey ? '✓ ' : '✗ ') + (PROVIDER_LABELS[name] || name);
                option.disabled = !hasKey;
                select.appendChild(option);
            });

            Object.entries(providers).forEach(([name, hasKey]) => {
                const div = document.createElement('div');
                div.className = 'provider-status ' + (hasKey ? 'active' : 'inactive');
                div.textContent = (hasKey ? '✓' : '✗') + ' ' + (PROVIDER_LABELS[name] || name);
                available.appendChild(div);
            });

            const firstAvailable = Object.entries(providers).find(([_, hasKey]) => hasKey);
            if (firstAvailable) {
                select.value = firstAvailable[0];
                currentProvider = firstAvailable[0];
                onProviderChange();
            }
        }

        function onProviderChange() {
            const provider = document.getElementById('providerSelect').value;
            currentProvider = provider;
            const modelSelect = document.getElementById('modelSelect');
            modelSelect.innerHTML = '';

            if (provider && models[provider]) {
                models[provider].forEach(model => {
                    const option = document.createElement('option');
                    option.value = model;
                    option.textContent = model;
                    modelSelect.appendChild(option);
                });
                currentModel = models[provider][0];
            }
        }

        function onModelChange() {
            currentModel = document.getElementById('modelSelect').value;
        }

        function initializeAgent() {
            if (!currentProvider || !currentModel) {
                alert('Please select a provider and model');
                return;
            }

            fetch('/api/initialize', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({provider: currentProvider, model: currentModel})
            })
            .then(r => r.json())
            .then(data => {
                if (data.success) {
                    initialized = true;
                    document.getElementById('input').disabled = false;
                    document.getElementById('sendBtn').disabled = false;
                    document.getElementById('status').textContent = '✓ Ready: ' + data.provider + ' / ' + data.model;
                    document.getElementById('input').focus();
                } else {
                    alert('Error: ' + data.error);
                }
            });
        }

        function sendMessage() {
            const input = document.getElementById('input');
            const text = input.value.trim();
            if (!text || !initialized) return;

            input.value = '';
            addMessage('user', text);

            fetch('/api/chat', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({message: text})
            })
            .then(r => r.json())
            .then(data => {
                if (data.success) {
                    addMessage('assistant', data.response);
                } else {
                    addMessage('assistant', '❌ Error: ' + data.error);
                }
            });
        }

        function onKeyPress(e) {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        }

        function addMessage(role, content) {
            const messages = document.getElementById('messages');
            const div = document.createElement('div');
            div.className = 'message ' + role;
            div.innerHTML = '<div class="message-content">' + escapeHtml(content) + '</div>';
            messages.appendChild(div);
            messages.scrollTop = messages.scrollHeight;
        }

        function escapeHtml(text) {
            const map = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'};
            return text.replace(/[&<>"']/g, m => map[m]);
        }

        function switchTab(tab, btn) {
            // Hide all tabs
            document.getElementById('agent-tab').classList.remove('active');
            document.getElementById('keys-tab').classList.remove('active');

            // Remove active class from all buttons
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));

            // Show selected tab
            document.getElementById(tab + '-tab').classList.add('active');

            // Highlight selected button
            btn.classList.add('active');

            // Load API keys if switching to keys tab
            if (tab === 'keys') {
                loadApiKeys();
            }
        }

        function addProviderRow(provider = null) {
            const container = document.getElementById('providerEditor');
            const row = document.createElement('div');
            row.className = 'provider-editor-row';
            row.style.marginBottom = '10px';
            row.style.padding = '10px';
            row.style.border = '1px solid #eee';
            row.style.borderRadius = '4px';
            const defaultName = provider ? provider.provider : '';
            const defaultModel = provider ? provider.model : '';
            const defaultEnabled = provider ? provider.enabled : true;
            row.innerHTML = `
                <div style="display:flex; gap:8px; align-items:center; margin-bottom:8px;">
                    <label style="flex:1; font-size:0.85em;">Provider</label>
                    <input type="checkbox" id="toggle_${Date.now()}" ${defaultEnabled ? 'checked' : ''}>
                </div>
                <input type="text" class="api-key-input" placeholder="Provider slug (e.g. openrouter)" value="${defaultName}">
                <input type="text" class="api-key-input" placeholder="Model" value="${defaultModel}">
                <input type="text" class="api-key-input" placeholder="API key env var (e.g. OPENROUTER_API_KEY)">
                <button onclick="this.closest('.provider-editor-row').remove()" style="width:100%; margin-top:6px; background:#d9534f;">Remove</button>
            `;
            container.appendChild(row);
        }

        function loadApiKeys() {
            fetch('/api/get-providers')
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        const container = document.getElementById('providerEditor');
                        container.innerHTML = '';
                        configuredProviders = data.providers || [];
                        configuredProviders.forEach(provider => addProviderRow(provider));
                    }
                });
        }

        function saveApiKeys() {
            const container = document.getElementById('providerEditor');
            const rows = Array.from(container.querySelectorAll('.provider-editor-row'));
            const providers = [];
            rows.forEach(row => {
                const textInputs = row.querySelectorAll('input[type="text"]');
                const checkbox = row.querySelector('input[type="checkbox"]');
                if (!textInputs.length) return;
                const enabled = checkbox ? checkbox.checked : true;
                const providerName = textInputs[0].value.trim();
                const model = textInputs[1].value.trim();
                const keyEnv = textInputs[2].value.trim();
                if (providerName) {
                    providers.push({provider: providerName, model, enabled, key_env: keyEnv});
                }
            });
            const defaultProvider = document.getElementById('providerSelect')?.value || providers[0]?.provider || '';
            const defaultModel = document.getElementById('modelSelect')?.value || providers[0]?.model || '';
            fetch('/api/save-providers', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({providers, default_provider: defaultProvider, default_model: defaultModel})
            })
            .then(r => r.json())
            .then(data => {
                const status = document.getElementById('saveStatus');
                if (data.success) {
                    status.className = 'save-status success';
                    status.textContent = '✓ Providers saved successfully!';
                    loadProviders();
                    setTimeout(() => status.className = 'save-status', 3000);
                } else {
                    status.className = 'save-status error';
                    status.textContent = '✗ Error: ' + data.error;
                    setTimeout(() => status.className = 'save-status', 3000);
                }
            });
        }
    </script>
</body>
</html>
"""


@app.route("/")
def index():
    """Serve the web UI."""
    available = detect_available_providers()
    response = app.make_response(render_template_string(
        HTML_TEMPLATE,
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
