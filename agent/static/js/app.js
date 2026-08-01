const providers = window.__PROVIDERS__ || {};
const models = window.__MODELS__ || {};

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

window.onload = function () {
    initTheme();
    loadProviders();
};

// ---- Theme (dark/light) ----

function initTheme() {
    const saved = localStorage.getItem('theme');
    if (saved === 'dark' || saved === 'light') {
        document.documentElement.setAttribute('data-theme', saved);
    }
    updateThemeButton();
}

function toggleTheme() {
    const current = document.documentElement.getAttribute('data-theme') ||
        (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    const next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('theme', next);
    updateThemeButton();
}

function updateThemeButton() {
    const btn = document.getElementById('themeToggle');
    if (!btn) return;
    const current = document.documentElement.getAttribute('data-theme') ||
        (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    btn.textContent = current === 'dark' ? '☀ Light' : '☾ Dark';
}

// ---- Providers / models ----

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
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: currentProvider, model: currentModel })
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

// ---- Chat ----

function sendMessage() {
    const input = document.getElementById('input');
    const text = input.value.trim();
    if (!text || !initialized) return;

    input.value = '';
    addMessage('user', text);
    const typingEl = showTyping();

    fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: text })
    })
        .then(r => r.json())
        .then(data => {
            typingEl.remove();
            if (data.success) {
                addMessage('assistant', data.response);
            } else {
                addMessage('assistant', '❌ Error: ' + data.error);
            }
        })
        .catch(err => {
            typingEl.remove();
            addMessage('assistant', '❌ Error: ' + err.message);
        });
}

function onKeyPress(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
}

function showTyping() {
    const messages = document.getElementById('messages');
    const div = document.createElement('div');
    div.className = 'message assistant';
    div.innerHTML = '<div class="message-content"><div class="typing-indicator"><span></span><span></span><span></span></div></div>';
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
    return div;
}

function addMessage(role, content) {
    const messages = document.getElementById('messages');
    const div = document.createElement('div');
    div.className = 'message ' + role;
    const bubble = document.createElement('div');
    bubble.className = 'message-content';
    if (role === 'assistant' && window.marked && window.DOMPurify) {
        bubble.innerHTML = window.DOMPurify.sanitize(window.marked.parse(content));
    } else {
        bubble.textContent = content;
    }
    div.appendChild(bubble);
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
}

// ---- Tabs ----

function switchTab(tab, btn) {
    document.getElementById('agent-tab').classList.remove('active');
    document.getElementById('keys-tab').classList.remove('active');
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.getElementById(tab + '-tab').classList.add('active');
    btn.classList.add('active');
    if (tab === 'keys') {
        loadApiKeys();
    }
}

// ---- API key / provider editor ----

function addProviderRow(provider = null) {
    const container = document.getElementById('providerEditor');
    const row = document.createElement('div');
    row.className = 'provider-editor-row';
    row.style.marginBottom = '10px';
    row.style.padding = '10px';
    row.style.border = '1px solid var(--color-border)';
    row.style.borderRadius = '8px';
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
    const providersOut = [];
    rows.forEach(row => {
        const textInputs = row.querySelectorAll('input[type="text"]');
        const checkbox = row.querySelector('input[type="checkbox"]');
        if (!textInputs.length) return;
        const enabled = checkbox ? checkbox.checked : true;
        const providerName = textInputs[0].value.trim();
        const model = textInputs[1].value.trim();
        const keyEnv = textInputs[2].value.trim();
        if (providerName) {
            providersOut.push({ provider: providerName, model, enabled, key_env: keyEnv });
        }
    });
    const defaultProvider = document.getElementById('providerSelect')?.value || providersOut[0]?.provider || '';
    const defaultModel = document.getElementById('modelSelect')?.value || providersOut[0]?.model || '';
    fetch('/api/save-providers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ providers: providersOut, default_provider: defaultProvider, default_model: defaultModel })
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
