"""Google Drive mirror sync.

Local JSON files (worklog/todos/memory) and Telegram uploads stay the
source of truth on disk - this module best-effort mirrors them to a
Google Drive folder. Any failure here (no credentials yet, offline,
API error) is caught by the caller and logged, never raised, so Drive
being unavailable never breaks the local-first app.

One-time setup: run `python drive_setup.py` after placing an OAuth
"Desktop app" client secret at agent/drive_credentials.json (see
DRIVE_SETUP.md for the Google Cloud Console steps).
"""

from __future__ import annotations

import io
import mimetypes
from pathlib import Path
from typing import Any

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload, MediaIoBaseUpload

SCOPES = ["https://www.googleapis.com/auth/drive.file"]
FOLDER_MIME = "application/vnd.google-apps.folder"

CATEGORY_EXTENSIONS = {
    "Pictures": {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".svg", ".tiff"},
    "Code": {
        ".py", ".js", ".ts", ".tsx", ".jsx", ".html", ".css", ".json", ".java", ".c", ".cpp",
        ".h", ".cs", ".go", ".rs", ".sh", ".ps1", ".sql", ".rb", ".php", ".yaml", ".yml", ".xml",
    },
    "Documents": {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".csv"},
}


def classify_category(filename: str) -> str:
    ext = Path(filename).suffix.lower()
    for category, extensions in CATEGORY_EXTENSIONS.items():
        if ext in extensions:
            return category
    return "Others"


class DriveSync:
    """Lazily-authenticated Drive client. Construct once, reuse for the app's lifetime."""

    def __init__(self, agent_dir: Path, config: dict[str, Any]):
        self.agent_dir = agent_dir
        drive_config = config.get("google_drive", {})
        self.enabled = bool(drive_config.get("enabled", False))
        self.root_folder_name = drive_config.get("root_folder_name", "MyPersonalAgent")
        self.sync_data_files = bool(drive_config.get("sync_data_files", True))
        self.sync_uploads = bool(drive_config.get("sync_uploads", True))
        self.credentials_path = agent_dir / drive_config.get("credentials_file", "drive_credentials.json")
        self.token_path = agent_dir / drive_config.get("token_file", "drive_token.json")

        self._service = None
        self._folder_ids: dict[str, str] = {}

    def _get_service(self):
        if self._service is not None:
            return self._service
        if not self.token_path.exists():
            raise RuntimeError(
                "Google Drive is not connected yet. Run 'python drive_setup.py' once to authorize it."
            )
        creds = Credentials.from_authorized_user_file(str(self.token_path), SCOPES)
        if creds.expired and creds.refresh_token:
            creds.refresh(Request())
            self.token_path.write_text(creds.to_json(), encoding="utf-8")
        self._service = build("drive", "v3", credentials=creds, cache_discovery=False)
        return self._service

    def _get_or_create_folder(self, name: str, parent_id: str | None) -> str:
        cache_key = f"{parent_id}/{name}"
        if cache_key in self._folder_ids:
            return self._folder_ids[cache_key]

        service = self._get_service()
        parent_clause = f"and '{parent_id}' in parents" if parent_id else "and 'root' in parents"
        query = f"name = '{name}' and mimeType = '{FOLDER_MIME}' and trashed = false {parent_clause}"
        result = service.files().list(q=query, spaces="drive", fields="files(id, name)").execute()
        files = result.get("files", [])
        if files:
            folder_id = files[0]["id"]
        else:
            metadata = {"name": name, "mimeType": FOLDER_MIME}
            if parent_id:
                metadata["parents"] = [parent_id]
            folder = service.files().create(body=metadata, fields="id").execute()
            folder_id = folder["id"]

        self._folder_ids[cache_key] = folder_id
        return folder_id

    def _root_folder_id(self) -> str:
        return self._get_or_create_folder(self.root_folder_name, None)

    def category_folder_id(self, category: str) -> str:
        return self._get_or_create_folder(category, self._root_folder_id())

    def upload_or_replace(self, local_path: Path, folder_id: str, drive_filename: str | None = None) -> str:
        """Create the file in Drive, or overwrite an existing same-named file in that folder."""
        service = self._get_service()
        name = drive_filename or local_path.name
        query = f"name = '{name}' and '{folder_id}' in parents and trashed = false"
        existing = service.files().list(q=query, spaces="drive", fields="files(id)").execute().get("files", [])

        mime_type = mimetypes.guess_type(name)[0] or "application/octet-stream"
        media = MediaFileUpload(str(local_path), mimetype=mime_type, resumable=False)

        if existing:
            file_id = existing[0]["id"]
            service.files().update(fileId=file_id, media_body=media).execute()
            return file_id
        metadata = {"name": name, "parents": [folder_id]}
        created = service.files().create(body=metadata, media_body=media, fields="id").execute()
        return created["id"]

    def upload_bytes(self, data: bytes, filename: str, folder_id: str) -> str:
        service = self._get_service()
        mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        media = MediaIoBaseUpload(io.BytesIO(data), mimetype=mime_type, resumable=False)
        metadata = {"name": filename, "parents": [folder_id]}
        created = service.files().create(body=metadata, media_body=media, fields="id").execute()
        return created["id"]

    def sync_data_file(self, local_path: Path) -> None:
        """Mirror a worklog/todos/memory JSON file into the root Drive folder."""
        if not (self.enabled and self.sync_data_files):
            return
        self.upload_or_replace(local_path, self._root_folder_id())

    def upload_attachment(self, data: bytes, filename: str) -> tuple[str, str]:
        """Upload Telegram attachment bytes, routed into its category subfolder.
        Returns (category, drive_file_id)."""
        category = classify_category(filename)
        folder_id = self.category_folder_id(category)
        file_id = self.upload_bytes(data, filename, folder_id)
        return category, file_id
