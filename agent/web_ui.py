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

app = Flask(__name__)
app.secret_key = "myagent-secret"
app.jinja_env.cache = None
app.config['SEND_FILE_MAX_AGE_DEFAULT'] = 0

# Global state
current_llm_client = None
current_provider = None
current_model = None
tools = None


def detect_available_providers():
    """Detect which providers have API keys configured."""
    env_keys = {
        "openrouter": os.getenv("OPENROUTER_API_KEY"),
        "anthropic": os.getenv("ANTHROPIC_API_KEY"),
        "openai": os.getenv("OPENAI_API_KEY"),
        "google": os.getenv("GOOGLE_API_KEY"),
        "grok": os.getenv("GROK_API_KEY"),
        "nvidia": os.getenv("NVIDIA_API_KEY") or os.getenv("NVIDIA_API_KEY_2"),
    }
    return {k: bool(v) for k, v in env_keys.items()}


def initialize_llm(provider, model):
    """Initialize LLM client with selected provider/model."""
    global current_llm_client, current_provider, current_model, tools

    config = load_config(AGENT_DIR)

    # Override provider in config
    config["llm_providers"] = [
        {
            "provider": provider,
            "model": model,
            "key_env": {
                "openrouter": "OPENROUTER_API_KEY",
                "anthropic": "ANTHROPIC_API_KEY",
                "openai": "OPENAI_API_KEY",
                "google": "GOOGLE_API_KEY",
                "grok": "GROK_API_KEY",
                "nvidia": "NVIDIA_API_KEY",
            }.get(provider, "")
        }
    ]

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
                        <h3>🔑 API Keys</h3>
                        <label>OpenRouter</label>
                        <input type="password" class="api-key-input" id="openrouter_key" placeholder="sk-or-...">

                        <label>Anthropic</label>
                        <input type="password" class="api-key-input" id="anthropic_key" placeholder="sk-ant-...">

                        <label>OpenAI</label>
                        <input type="password" class="api-key-input" id="openai_key" placeholder="sk-...">

                        <label>Google</label>
                        <input type="password" class="api-key-input" id="google_key" placeholder="API key...">

                        <label>Grok</label>
                        <input type="password" class="api-key-input" id="grok_key" placeholder="API key...">

                        <label>NVIDIA</label>
                        <input type="password" class="api-key-input" id="nvidia_key" placeholder="nvapi-...">

                        <button onclick="saveApiKeys()" style="width: 100%; margin-top: 15px;">Save API Keys</button>
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

            // Populate the select dropdown with provider options
            Object.entries(providers).forEach(([name, hasKey]) => {
                const option = document.createElement('option');
                option.value = name;
                option.textContent = (hasKey ? '✓ ' : '✗ ') + (PROVIDER_LABELS[name] || name);
                option.disabled = !hasKey;
                select.appendChild(option);
            });

            // Add status indicators
            Object.entries(providers).forEach(([name, hasKey]) => {
                const div = document.createElement('div');
                div.className = 'provider-status ' + (hasKey ? 'active' : 'inactive');
                div.textContent = (hasKey ? '✓' : '✗') + ' ' + name;
                available.appendChild(div);
            });

            // Pre-select first available
            const firstAvailable = Object.entries(providers).find(([_, hasKey]) => hasKey);
            if (firstAvailable) {
                select.value = firstAvailable[0];
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

        const DEFAULT_KEY_PLACEHOLDERS = {
            openrouter_key: 'sk-or-...',
            anthropic_key: 'sk-ant-...',
            openai_key: 'sk-...',
            google_key: 'API key...',
            grok_key: 'API key...',
            nvidia_key: 'nvapi-...'
        };

        function setKeyPlaceholder(inputId, maskedValue) {
            const el = document.getElementById(inputId);
            // Never write the masked preview into the field's value - it is not
            // a usable key, and resubmitting it would overwrite the real key.
            el.value = '';
            el.placeholder = maskedValue
                ? maskedValue + ' (saved - leave blank to keep)'
                : DEFAULT_KEY_PLACEHOLDERS[inputId];
        }

        function loadApiKeys() {
            fetch('/api/get-keys')
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        setKeyPlaceholder('openrouter_key', data.keys.OPENROUTER_API_KEY);
                        setKeyPlaceholder('anthropic_key', data.keys.ANTHROPIC_API_KEY);
                        setKeyPlaceholder('openai_key', data.keys.OPENAI_API_KEY);
                        setKeyPlaceholder('google_key', data.keys.GOOGLE_API_KEY);
                        setKeyPlaceholder('grok_key', data.keys.GROK_API_KEY);
                        setKeyPlaceholder('nvidia_key', data.keys.NVIDIA_API_KEY);
                    }
                });
        }

        function saveApiKeys() {
            const keys = {
                OPENROUTER_API_KEY: document.getElementById('openrouter_key').value,
                ANTHROPIC_API_KEY: document.getElementById('anthropic_key').value,
                OPENAI_API_KEY: document.getElementById('openai_key').value,
                GOOGLE_API_KEY: document.getElementById('google_key').value,
                GROK_API_KEY: document.getElementById('grok_key').value,
                NVIDIA_API_KEY: document.getElementById('nvidia_key').value
            };

            fetch('/api/save-keys', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({keys: keys})
            })
            .then(r => r.json())
            .then(data => {
                const status = document.getElementById('saveStatus');
                if (data.success) {
                    status.className = 'save-status success';
                    status.textContent = '✓ API keys saved successfully!';
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


@app.route("/api/save-keys", methods=["POST"])
def api_save_keys():
    """Save API keys to .env file."""
    try:
        data = request.json
        keys = data.get("keys", {})

        env_path = AGENT_DIR / ".env"

        # Read existing .env
        existing_content = ""
        if env_path.exists():
            existing_content = env_path.read_text(encoding="utf-8")

        # Update or add keys
        lines = existing_content.split("\n")
        updated_lines = []
        updated_keys = set()

        for line in lines:
            if line.strip() and not line.startswith("#"):
                key = line.split("=")[0].strip()
                if key in keys:
                    if keys[key]:  # New value provided - update it
                        updated_lines.append(f"{key}={keys[key]}")
                    else:  # Blank field - keep the existing value untouched
                        updated_lines.append(line)
                    updated_keys.add(key)
                else:
                    updated_lines.append(line)
            else:
                updated_lines.append(line)

        # Add any new keys that weren't in the file
        for key, value in keys.items():
            if key not in updated_keys and value:
                updated_lines.append(f"{key}={value}")

        # Write back to file
        new_content = "\n".join(updated_lines).strip() + "\n"
        env_path.write_text(new_content, encoding="utf-8")

        # Reload environment
        from dotenv import load_dotenv
        load_dotenv(env_path, override=True)

        return jsonify({"success": True, "message": "API keys saved successfully"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})


def run_web_ui(debug=False, port=5000):
    """Run the web UI server."""
    print(f"\nMyPersonalAgent Web UI running at http://localhost:{port}")
    print("   Open this URL in your browser to get started")
    app.run(debug=debug, port=port, use_reloader=False)


if __name__ == "__main__":
    run_web_ui(debug=True, port=5000)
