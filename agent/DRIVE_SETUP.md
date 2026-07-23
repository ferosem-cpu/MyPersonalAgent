# Google Drive Mirror - Setup

Your local files (`tracker/data/*.json`) stay the source of truth. Once this is
set up, every save and every Telegram file upload also gets mirrored into a
Google Drive folder named `MyPersonalAgent` (configurable), organized like:

```
MyPersonalAgent/
├── worklog.json          (mirrored)
├── todos.json             (mirrored)
├── memory.json             (mirrored)
├── Pictures/               (Telegram photo/image uploads)
├── Documents/              (pdf, doc, xls, ppt, txt, md, csv)
├── Code/                   (py, js, html, json, sql, ...)
└── Others/                 (anything else)
```

## 1. Create a Google Cloud project

1. Go to https://console.cloud.google.com/ and sign in with the Google
   account whose Drive you want to use.
2. Click the project dropdown (top left) → **New Project**. Give it any name
   (e.g. "MyPersonalAgent") → **Create**.

## 2. Enable the Google Drive API

1. With your new project selected, go to
   https://console.cloud.google.com/apis/library/drive.googleapis.com
2. Click **Enable**.

## 3. Configure the OAuth consent screen

1. Go to https://console.cloud.google.com/apis/credentials/consent
2. Choose **External**, click **Create**.
3. Fill in an app name (anything), your email for support/contact, **Save and Continue** through the Scopes and Test users screens.
4. On the **Test users** step, add your own Google account's email address. (Since this app won't be verified by Google, only accounts listed here can authorize it.)

## 4. Create OAuth credentials

1. Go to https://console.cloud.google.com/apis/credentials
2. Click **Create Credentials → OAuth client ID**.
3. Application type: **Desktop app**. Name: anything.
4. Click **Create**, then **Download JSON**.
5. Save that downloaded file as exactly:
   ```
   D:\Projects\MyPersonalAgent\agent\drive_credentials.json
   ```

## 5. Authorize it

```powershell
cd D:\Projects\MyPersonalAgent\agent
.\.venv\Scripts\python.exe drive_setup.py
```

This opens your browser for a one-time consent screen (you'll see an "unverified app" warning since this is your own personal app - click **Advanced → Go to MyPersonalAgent (unsafe)** to proceed, this is expected and safe since it's your own credential). After you approve, it saves a token file and turns on Drive sync in `config.json` automatically.

## 6. Restart

Restart the web UI and/or the Telegram bot (`run_web.bat` / `run_telegram.bat`) so they pick up the change. From then on:
- Every `todo`, `log`, `remember`, and to-do completion mirrors to Drive automatically.
- Any file (photo, PDF, code file, etc.) sent to the Telegram bot uploads to the matching Drive subfolder, and the bot replies confirming where it went.

## Notes

- If Drive is unreachable (offline, quota, revoked access) the app keeps working normally on local files - it just skips that sync and logs a message, it never blocks or crashes.
- `drive_credentials.json` and `drive_token.json` are secrets - don't share them or commit them to any repository.
- To turn Drive sync off again, set `"google_drive": {"enabled": false}` in `config.json`.
