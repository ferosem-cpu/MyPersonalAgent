const KEY = "crew.v1";
const SHAPES = ["round", "drop", "hex"];
const COLORS = ["#7C5CFF","#4DA3FF","#3DDC97","#FF7A59","#FFB020","#FF5C8A","#A78BFA","#22D3EE","#F97316","#84CC16"];
const PRESETS = [
  { name: "Chief of Staff", job: "Routes work and keeps one thread of status", color: "#7C5CFF", shape: "round",
    instructions: "You are Chief of Staff. Be concise. Route tasks, summarize status, create todos when work is committed. Ask only if a decision is blocked." },
  { name: "Todo Manager", job: "Keeps the list honest", color: "#3DDC97", shape: "hex",
    instructions: "You manage todos. Prefer tools over chatter. Confirm what you added or completed." },
  { name: "Memory", job: "Remembers people, prefs, and facts", color: "#4DA3FF", shape: "drop",
    instructions: "Save durable facts with remember. Recall before asking the user to repeat themselves." },
  { name: "Inbox Aide", job: "Drafts replies, never sends", color: "#FF7A59", shape: "round",
    instructions: "Draft messages in the user's voice. Never claim you sent anything. Queue work for approval." },
];
const DEFAULT_GATEWAY = {
  baseUrl: "https://freellmapi-ferose.duckdns.org/v1",
  model: "gpt-4o-mini",
};
const TOOLS = [
  { type: "function", function: { name: "add_todo", description: "Create a todo", parameters: { type: "object", properties: { title: { type: "string" }, due: { type: "string" } }, required: ["title"] } } },
  { type: "function", function: { name: "list_todos", description: "List open todos", parameters: { type: "object", properties: {} } } },
  { type: "function", function: { name: "complete_todo", description: "Mark a todo done by id or title", parameters: { type: "object", properties: { id: { type: "string" }, title: { type: "string" } } } } },
  { type: "function", function: { name: "remember", description: "Save a durable note", parameters: { type: "object", properties: { text: { type: "string" } }, required: ["text"] } } },
  { type: "function", function: { name: "recall", description: "Search saved notes", parameters: { type: "object", properties: { query: { type: "string" } } } } },
  { type: "function", function: { name: "add_contact", description: "Save a contact", parameters: { type: "object", properties: { name: { type: "string" }, phone: { type: "string" }, email: { type: "string" } }, required: ["name"] } } },
  { type: "function", function: { name: "list_contacts", description: "List contacts", parameters: { type: "object", properties: { query: { type: "string" } } } } },
  { type: "function", function: { name: "list_assistants", description: "List other assistants on this team", parameters: { type: "object", properties: {} } } },
];
function uid() { return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4); }
function now() { return Date.now(); }
function load() {
  const raw = localStorage.getItem(KEY);
  if (raw) return JSON.parse(raw);
  return { settings: { apiKey: "", baseUrl: DEFAULT_GATEWAY.baseUrl, model: DEFAULT_GATEWAY.model }, assistants: [], messages: {}, todos: [], notes: [], contacts: [] };
}
let state = load();
function save() { localStorage.setItem(KEY, JSON.stringify(state)); }
function seedIfNeeded() {
  if (state.assistants.length) return;
  state.assistants = PRESETS.map((p, i) => ({ id: uid(), ...p, created: now(), pinned: i === 0, last: now() }));
  state.assistants.forEach(a => { state.messages[a.id] = [{ role: "assistant", content: `I'm ${a.name}. ${a.job}.`, ts: now() }]; });
  save();
}
const $ = (id) => document.getElementById(id);
let currentId = null;
let draft = { color: COLORS[0], shape: "round" };
function show(name) { document.querySelectorAll(".screen").forEach(s => s.classList.remove("on")); $(name).classList.add("on"); }
function avatarEl(a, size) {
  const d = document.createElement("div");
  d.className = `avatar ${a.shape || "round"} ${size || ""}`;
  d.style.background = a.color || COLORS[0];
  d.textContent = (a.name || "?").slice(0, 1).toUpperCase();
  return d;
}
function fmtWhen(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  const diff = Date.now() - ts;
  if (diff < 86400000 && new Date().getDate() === d.getDate()) return d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  if (diff < 172800000) return "Yesterday";
  return d.toLocaleDateString([], { month: "short", day: "numeric" });
}
function lastMsg(id) {
  const list = state.messages[id] || [];
  const m = [...list].reverse().find(x => x.role !== "tool");
  return m ? String(m.content || "").replace(/\s+/g, " ") : "No messages yet";
}
function esc(s) { return String(s || "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
function renderHome() {
  const q = ($("search").value || "").toLowerCase();
  const bots = state.assistants.filter(a => !a.hidden);
  $("pills").innerHTML = "";
  bots.slice(0, 6).forEach(a => {
    const el = document.createElement("button"); el.className = "pill"; el.onclick = () => openChat(a.id);
    el.append(avatarEl(a)); const s = document.createElement("span"); s.textContent = a.name.split(" ")[0]; el.append(s); $("pills").append(el);
  });
  $("list").innerHTML = "";
  const rows = bots.filter(a => !q || a.name.toLowerCase().includes(q) || (a.job || "").toLowerCase().includes(q))
    .sort((a, b) => (b.pinned - a.pinned) || ((b.last || 0) - (a.last || 0)));
  if (!rows.length) { $("list").innerHTML = `<div class="empty">No assistants yet. Tap + to add one.</div>`; return; }
  rows.forEach(a => {
    const b = document.createElement("button"); b.className = "item"; b.onclick = () => openChat(a.id);
    b.append(avatarEl(a));
    const meta = document.createElement("div"); meta.className = "meta";
    meta.innerHTML = `<div class="name">${a.pinned ? "\uD83D\uDCCC " : ""}${esc(a.name)}</div><div class="preview">${esc(lastMsg(a.id))}</div>`;
    const when = document.createElement("div"); when.className = "when"; when.textContent = fmtWhen(a.last);
    b.append(meta, when); $("list").append(b);
  });
}
function openChat(id) {
  currentId = id; const a = state.assistants.find(x => x.id === id); if (!a) return;
  $("chatTitle").textContent = a.name; $("chatJob").textContent = a.job || "Assistant";
  $("chatAv").replaceWith(Object.assign(avatarEl(a, "sm"), { id: "chatAv" }));
  renderMsgs(); show("chat"); $("composer").value = ""; $("composer").focus();
}
function renderMsgs() {
  const box = $("msgs"); box.innerHTML = "";
  (state.messages[currentId] || []).forEach(m => {
    if (m.role === "tool") return;
    const d = document.createElement("div"); d.className = `bubble ${m.role === "user" ? "me" : ""} ${m.kind || ""}`; d.textContent = m.content; box.append(d);
  });
  box.scrollTop = box.scrollHeight;
}
function openNew() {
  draft = { color: COLORS[Math.floor(Math.random() * COLORS.length)], shape: SHAPES[0] };
  $("botName").value = ""; $("botJob").value = ""; $("botInstr").value = ""; paintPickers(); $("sheet").classList.add("on");
}
function paintPickers() {
  $("colors").innerHTML = ""; COLORS.forEach(c => { const b = document.createElement("button"); b.className = "swatch" + (draft.color === c ? " on" : ""); b.style.background = c; b.onclick = () => { draft.color = c; paintPickers(); }; $("colors").append(b); });
  $("shapes").innerHTML = ""; SHAPES.forEach(s => { const b = document.createElement("button"); b.className = "shape " + (draft.shape === s ? " on" : ""); b.textContent = s[0].toUpperCase(); b.onclick = () => { draft.shape = s; paintPickers(); }; $("shapes").append(b); });
}
function createBot() {
  const name = $("botName").value.trim(); if (!name) return;
  const a = { id: uid(), name, job: $("botJob").value.trim(), instructions: $("botInstr").value.trim(), color: draft.color, shape: draft.shape, created: now(), last: now(), pinned: false };
  state.assistants.unshift(a);
  state.messages[a.id] = [{ role: "assistant", content: `I'm ${a.name}. ${a.job || "Ready when you are."}`, ts: now() }];
  save(); $("sheet").classList.remove("on"); openChat(a.id);
}
function openSettings() {
  const s = state.settings;
  $("apiKey").value = s.apiKey || "";
  $("baseUrl").value = s.baseUrl || DEFAULT_GATEWAY.baseUrl;
  $("model").value = s.model || DEFAULT_GATEWAY.model;
  show("settings");
}
function completionsUrl(base) {
  const trimmed = String(base || "").trim().replace(/\/$/, "");
  return trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions";
}
function saveSettings() {
  state.settings = {
    apiKey: $("apiKey").value.trim(),
    baseUrl: ($("baseUrl").value.trim() || DEFAULT_GATEWAY.baseUrl).replace(/\/$/, ""),
    model: $("model").value.trim() || DEFAULT_GATEWAY.model,
  };
  save(); show("home"); renderHome();
}
function pushMsg(id, msg) {
  state.messages[id] = state.messages[id] || []; state.messages[id].push(msg);
  const a = state.assistants.find(x => x.id === id); if (a) a.last = now(); save();
}
async function send() {
  const text = $("composer").value.trim(); if (!text || !currentId) return;
  if (!state.settings.apiKey) { alert("Add your API key in Settings first."); openSettings(); return; }
  $("composer").value = ""; pushMsg(currentId, { role: "user", content: text, ts: now() }); renderMsgs(); $("send").disabled = true;
  try { const reply = await runTurn(currentId); pushMsg(currentId, { role: "assistant", content: reply || "(empty reply)", ts: now() }); }
  catch (e) { pushMsg(currentId, { role: "assistant", content: "Could not reach the model: " + (e.message || e), ts: now() }); }
  $("send").disabled = false; renderMsgs(); renderHome();
}
function workspaceBlurb() {
  const open = state.todos.filter(t => t.status !== "done");
  return [`Open todos (${open.length}): ` + (open.slice(0, 8).map(t => t.title).join("; ") || "none"), `Notes: ${state.notes.length}`, `Contacts: ${state.contacts.length}`, `Team: ` + state.assistants.map(a => a.name).join(", ")].join("\n");
}
async function runTurn(id) {
  const a = state.assistants.find(x => x.id === id);
  const history = (state.messages[id] || []).filter(m => m.role === "user" || m.role === "assistant" || m.role === "tool").slice(-16).map(m => {
    if (m.role === "tool") return { role: "tool", tool_call_id: m.tool_call_id, content: m.content };
    return { role: m.role, content: m.content };
  });
  const messages = [{ role: "system", content: `${a.instructions || "You are a helpful teammate."}\n\nShared workspace:\n${workspaceBlurb()}\nUse tools for todos, memory, and contacts. Be brief.` }, ...history];
  for (let i = 0; i < 5; i++) {
    const data = await chatApi(messages);
    const msg = data.choices?.[0]?.message || {};
    const calls = msg.tool_calls || [];
    if (!calls.length) return (msg.content || "").trim();
    messages.push({ role: "assistant", content: msg.content || null, tool_calls: calls });
    for (const call of calls) {
      let args = {}; try { args = JSON.parse(call.function?.arguments || "{}"); } catch {}
      const result = runTool(call.function?.name || "", args);
      pushMsg(id, { role: "assistant", kind: "tool", content: "Used " + (call.function?.name || "tool"), ts: now() });
      messages.push({ role: "tool", tool_call_id: call.id, content: result });
      pushMsg(id, { role: "tool", tool_call_id: call.id, content: result, ts: now() });
    }
  }
  return "Stopped after several tool steps.";
}
function runTool(name, args) {
  if (name === "add_todo") { const t = { id: uid(), title: args.title, due: args.due || "", status: "open", created: now() }; state.todos.unshift(t); save(); return `Added todo ${t.id}: ${t.title}`; }
  if (name === "list_todos") { const open = state.todos.filter(t => t.status !== "done"); return open.length ? open.map(t => `- [${t.id}] ${t.title}${t.due ? " due " + t.due : ""}`).join("\n") : "No open todos."; }
  if (name === "complete_todo") { const t = state.todos.find(x => x.id === args.id || (args.title && x.title.toLowerCase() === String(args.title).toLowerCase())); if (!t) return "Todo not found."; t.status = "done"; save(); return `Completed ${t.title}`; }
  if (name === "remember") { state.notes.unshift({ id: uid(), text: args.text, created: now() }); save(); return "Saved to memory."; }
  if (name === "recall") { const q = (args.query || "").toLowerCase(); const hits = state.notes.filter(n => !q || n.text.toLowerCase().includes(q)).slice(0, 8); return hits.length ? hits.map(n => "- " + n.text).join("\n") : "No matching notes."; }
  if (name === "add_contact") { state.contacts.unshift({ id: uid(), name: args.name, phone: args.phone || "", email: args.email || "" }); save(); return `Saved contact ${args.name}`; }
  if (name === "list_contacts") { const q = (args.query || "").toLowerCase(); const hits = state.contacts.filter(c => !q || c.name.toLowerCase().includes(q)); return hits.length ? hits.map(c => `- ${c.name} ${c.phone} ${c.email}`).join("\n") : "No contacts."; }
  if (name === "list_assistants") return state.assistants.map(a => `- ${a.name}: ${a.job || ""}`).join("\n");
  return "Unknown tool.";
}
async function chatApi(messages) {
  const s = state.settings;
  const res = await fetch(completionsUrl(s.baseUrl), { method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + s.apiKey }, body: JSON.stringify({ model: s.model || DEFAULT_GATEWAY.model, messages, tools: TOOLS, temperature: 0.4 }) });
  const text = await res.text();
  if (!res.ok) throw new Error(res.status + " " + text.slice(0, 280));
  return JSON.parse(text);
}
function deleteCurrent() {
  if (!currentId || !confirm("Delete this assistant?")) return;
  state.assistants = state.assistants.filter(a => a.id !== currentId); delete state.messages[currentId]; save(); currentId = null; show("home"); renderHome();
}
window.addEventListener("DOMContentLoaded", () => {
  seedIfNeeded();
  $("search").addEventListener("input", renderHome);
  $("addBtn").onclick = openNew; $("settingsBtn").onclick = openSettings;
  $("backBtn").onclick = () => { show("home"); renderHome(); };
  $("saveBot").onclick = createBot; $("cancelBot").onclick = () => $("sheet").classList.remove("on");
  $("sheet").addEventListener("click", (e) => { if (e.target.id === "sheet") e.target.classList.remove("on"); });
  $("saveSettings").onclick = saveSettings;
  $("send").onclick = send;
  $("composer").addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } });
  $("moreBtn").onclick = deleteCurrent; $("backSettings").onclick = () => { show("home"); renderHome(); };
  if (!state.settings.apiKey) openSettings(); else { show("home"); renderHome(); }
});
