/**
 * Local WhatsApp Web bridge (PLAN_V2 Task 5.2). Drives a headless Chromium logged
 * into WhatsApp Web with the user's own account via whatsapp-web.js. One-time QR
 * pairing; LocalAuth persists the session across restarts. No Business API, no
 * per-message cost, no automated bulk sending - this exists to let the agent send
 * ONE message at a time, always after the user has explicitly confirmed it.
 *
 * Binds 127.0.0.1 only. Every request must carry X-Bridge-Key matching WA_BRIDGE_KEY.
 */

const express = require("express");
const qrcode = require("qrcode");
const qrcodeTerminal = require("qrcode-terminal");
const { Client, LocalAuth } = require("whatsapp-web.js");

const PORT = process.env.WA_BRIDGE_PORT || 8600;
const BRIDGE_KEY = process.env.WA_BRIDGE_KEY;

if (!BRIDGE_KEY) {
  console.error(
    "WA_BRIDGE_KEY is not set. Add it to agent/.env and make sure the process " +
      "that launches this bridge exports it into the environment before starting."
  );
  process.exit(1);
}

let ready = false;
let lastQr = null;

const client = new Client({
  authStrategy: new LocalAuth({ dataPath: __dirname + "/.wwebjs_auth" }),
  puppeteer: { headless: true, args: ["--no-sandbox", "--disable-setuid-sandbox"] },
});

client.on("qr", (qr) => {
  lastQr = qr;
  console.log("Scan this QR with WhatsApp (Settings > Linked Devices) to pair:");
  qrcodeTerminal.generate(qr, { small: true });
});

client.on("ready", () => {
  ready = true;
  lastQr = null;
  console.log("WhatsApp bridge ready - paired and connected.");
});

client.on("disconnected", (reason) => {
  ready = false;
  console.log("WhatsApp client disconnected:", reason);
});

client.initialize();

const app = express();
app.use(express.json());

function requireKey(req, res, next) {
  const key = req.get("X-Bridge-Key");
  if (!key || key !== BRIDGE_KEY) {
    return res.status(401).json({ error: "invalid or missing X-Bridge-Key" });
  }
  next();
}

app.get("/status", requireKey, (req, res) => {
  res.json({ ready, qr_pending: !!lastQr });
});

app.get("/qr", requireKey, async (req, res) => {
  if (!lastQr) {
    return res.status(404).json({ error: "no QR pending (already paired, or not generated yet)" });
  }
  try {
    const dataUrl = await qrcode.toDataURL(lastQr);
    res.json({ qr: dataUrl });
  } catch (err) {
    res.status(500).json({ error: String((err && err.message) || err) });
  }
});

app.post("/send", requireKey, async (req, res) => {
  if (!ready) {
    return res.status(503).json({ error: "WhatsApp client not ready - pair via /qr first" });
  }
  const { to, message } = req.body || {};
  if (!to || !message) {
    return res.status(400).json({ error: "'to' and 'message' are required" });
  }
  const digits = String(to).replace(/\D/g, "");
  if (!digits) {
    return res.status(400).json({ error: "invalid phone number" });
  }
  const chatId = `${digits}@c.us`;
  try {
    const sent = await client.sendMessage(chatId, message);
    res.json({ id: (sent.id && sent.id._serialized) || String(sent.id), to: chatId });
  } catch (err) {
    res.status(500).json({ error: String((err && err.message) || err) });
  }
});

app.get("/chats", requireKey, async (req, res) => {
  if (!ready) {
    return res.status(503).json({ error: "WhatsApp client not ready" });
  }
  const limit = parseInt(req.query.limit, 10) || 20;
  try {
    const chats = await client.getChats();
    res.json(
      chats.slice(0, limit).map((c) => ({
        name: c.name || (c.id && c.id.user) || "unknown",
        unread: c.unreadCount || 0,
      }))
    );
  } catch (err) {
    res.status(500).json({ error: String((err && err.message) || err) });
  }
});

app.listen(PORT, "127.0.0.1", () => {
  console.log(`WhatsApp bridge listening on http://127.0.0.1:${PORT}`);
});
