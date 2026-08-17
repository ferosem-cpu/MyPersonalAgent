"""One-off helper: look up the Drive folder ID for ZanF_DropBox using the
already-saved zan_drive_token.json, and print the refresh token for
copying into Vercel env vars.
"""
from __future__ import annotations

import json
from pathlib import Path

from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

HERE = Path(__file__).resolve().parent
TOKEN_PATH = HERE / "zan_drive_token.json"

FOLDER_NAME = "ZanF_DropBox"


def main() -> None:
    data = json.loads(TOKEN_PATH.read_text(encoding="utf-8"))
    creds = Credentials.from_authorized_user_info(data)

    service = build("drive", "v3", credentials=creds)
    results = service.files().list(
        q=f"name='{FOLDER_NAME}' and mimeType='application/vnd.google-apps.folder' and trashed=false",
        fields="files(id, name, parents)",
        spaces="drive",
    ).execute()

    files = results.get("files", [])
    if not files:
        print(f"No folder named '{FOLDER_NAME}' found. Create it in the company Drive first.")
    else:
        for f in files:
            print(f"Folder: {f['name']}  ID: {f['id']}")

    print()
    print("Refresh token (for Vercel env var):")
    print(data.get("refresh_token"))


if __name__ == "__main__":
    main()
