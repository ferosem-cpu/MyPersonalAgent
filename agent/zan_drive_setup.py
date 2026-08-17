"""One-time OAuth consent for the Zan-APP in-app agent's Google Drive search feature.

Separate from MyPersonalAgent's own drive_credentials.json/drive_token.json (different
OAuth client, broader drive.readonly scope, different Google account - the dedicated
company account, not ferosem@gmail.com). Keeping these fully separate so this doesn't
touch or widen scope on the existing personal Drive-mirror integration.
"""
from __future__ import annotations

from pathlib import Path

from google_auth_oauthlib.flow import InstalledAppFlow

HERE = Path(__file__).resolve().parent
CREDENTIALS_PATH = HERE / "zan_drive_credentials.json"
TOKEN_PATH = HERE / "zan_drive_token.json"

SCOPES = ["https://www.googleapis.com/auth/drive.readonly"]


def main() -> None:
    if not CREDENTIALS_PATH.exists():
        print(f"Missing {CREDENTIALS_PATH}")
        return
    flow = InstalledAppFlow.from_client_secrets_file(str(CREDENTIALS_PATH), SCOPES)
    creds = flow.run_local_server(port=0)
    TOKEN_PATH.write_text(creds.to_json(), encoding="utf-8")
    print(f"Authorized. Token saved to {TOKEN_PATH}")
    print(f"Refresh token present: {bool(creds.refresh_token)}")


if __name__ == "__main__":
    main()
