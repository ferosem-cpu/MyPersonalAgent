"""One-time interactive Google Drive authorization.

Before running this:
  1. Create a Google Cloud project and enable the Google Drive API.
  2. Create an OAuth 2.0 Client ID of type "Desktop app".
  3. Download its JSON and save it as agent/drive_credentials.json.

See DRIVE_SETUP.md for the full click-by-click walkthrough.

Running this script opens your browser for a one-time consent screen,
then caches a refresh token at agent/drive_token.json and turns on
google_drive.enabled in config.json.
"""

from __future__ import annotations

import json
from pathlib import Path

from google_auth_oauthlib.flow import InstalledAppFlow

from drive_sync import SCOPES

AGENT_DIR = Path(__file__).resolve().parent
CREDENTIALS_PATH = AGENT_DIR / "drive_credentials.json"
TOKEN_PATH = AGENT_DIR / "drive_token.json"
CONFIG_PATH = AGENT_DIR / "config.json"


def main() -> None:
    if not CREDENTIALS_PATH.exists():
        print(f"Missing {CREDENTIALS_PATH}.")
        print("Download an OAuth 'Desktop app' client secret from Google Cloud Console")
        print("and save it at that exact path, then run this script again.")
        print("See DRIVE_SETUP.md for the full steps.")
        return

    flow = InstalledAppFlow.from_client_secrets_file(str(CREDENTIALS_PATH), SCOPES)
    creds = flow.run_local_server(port=0)
    TOKEN_PATH.write_text(creds.to_json(), encoding="utf-8")
    print(f"Authorized. Token saved to {TOKEN_PATH}")

    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    config.setdefault("google_drive", {})["enabled"] = True
    CONFIG_PATH.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("google_drive.enabled set to true in config.json.")
    print("Restart the web UI / Telegram bot so they pick up the change.")


if __name__ == "__main__":
    main()
