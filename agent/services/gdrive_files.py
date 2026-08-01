"""Google Drive file tools beyond the existing mirror sync (PLAN_V2 Task 6.1).

Reuses the same OAuth credentials/token as drive_sync.py (agent/drive_credentials.json,
agent/drive_token.json) and the same drive.file scope - meaning search/download only see
files this app has itself created or opened, not the user's whole Drive. That's a
deliberate, safer default: switching to the broader "drive" scope would require a fresh
consent screen and would let the agent see/search every file in the account. Documented
here rather than done, per the task's own guidance to keep drive.file unless the user
specifically wants search-all.

Uploads/downloads default to agent/uploads/ when given a relative path - never an
arbitrary filesystem path outside what LocalTools' allowed_dirs would already permit.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload, MediaIoBaseDownload

from drive_sync import SCOPES  # same drive.file scope as the existing mirror sync

AGENT_DIR = Path(__file__).resolve().parent.parent


def _service(config: dict[str, Any]):
    drive_config = config.get("google_drive", {})
    token_path = AGENT_DIR / drive_config.get("token_file", "drive_token.json")
    if not token_path.exists():
        raise RuntimeError("Google Drive is not connected yet. Run 'python drive_setup.py' once to authorize it.")
    creds = Credentials.from_authorized_user_file(str(token_path), SCOPES)
    if creds.expired and creds.refresh_token:
        creds.refresh(Request())
        token_path.write_text(creds.to_json(), encoding="utf-8")
    if not creds.valid:
        raise RuntimeError("Google Drive credentials are invalid. Re-run 'python drive_setup.py'.")
    return build("drive", "v3", credentials=creds, cache_discovery=False)


def _looks_like_file_id(value: str) -> bool:
    # Drive file IDs are alphanumeric-ish (+ -_), no spaces or dots, generally 25+ chars.
    return " " not in value and "." not in value and len(value) >= 20


def drive_search(config: dict[str, Any], query: str) -> list[dict[str, Any]]:
    service = _service(config)
    safe_query = query.replace("'", "\\'").replace("\\", "\\\\")
    q = f"name contains '{safe_query}' and trashed = false"
    result = service.files().list(
        q=q, spaces="drive", fields="files(id, name, mimeType, webViewLink, modifiedTime)"
    ).execute()
    return result.get("files", [])


def drive_upload(config: dict[str, Any], local_path: str, folder_id: str | None = None) -> dict[str, Any]:
    path = Path(local_path)
    if not path.is_absolute():
        path = AGENT_DIR / "uploads" / local_path
    if not path.is_file():
        raise RuntimeError(f"File not found: {path}")
    service = _service(config)
    metadata: dict[str, Any] = {"name": path.name}
    if folder_id:
        metadata["parents"] = [folder_id]
    media = MediaFileUpload(str(path), resumable=False)
    return service.files().create(body=metadata, media_body=media, fields="id, name, webViewLink").execute()


def drive_download(config: dict[str, Any], file_id_or_name: str, dest_path: str) -> dict[str, Any]:
    service = _service(config)
    file_id = file_id_or_name
    if not _looks_like_file_id(file_id_or_name):
        matches = drive_search(config, file_id_or_name)
        if not matches:
            raise RuntimeError(f"No Drive file found matching '{file_id_or_name}'.")
        if len(matches) > 1:
            names = [m["name"] for m in matches]
            raise RuntimeError(f"Multiple Drive files match '{file_id_or_name}': {names}. Use the file id to disambiguate.")
        file_id = matches[0]["id"]

    dest = Path(dest_path)
    if not dest.is_absolute():
        dest = AGENT_DIR / "uploads" / dest_path
    dest.parent.mkdir(parents=True, exist_ok=True)

    request = service.files().get_media(fileId=file_id)
    with open(dest, "wb") as f:
        downloader = MediaIoBaseDownload(f, request)
        done = False
        while not done:
            _, done = downloader.next_chunk()
    return {"path": str(dest)}


def drive_share_link(config: dict[str, Any], file_id: str) -> dict[str, Any]:
    """Makes the file readable by anyone with the link. Caller (LocalTools.drive_share_link)
    gates this behind a confirm step - creating a public link is not undo-safe."""
    service = _service(config)
    service.permissions().create(fileId=file_id, body={"role": "reader", "type": "anyone"}).execute()
    return service.files().get(fileId=file_id, fields="id, name, webViewLink").execute()
