from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from drive_sync import DriveSync


class DriveSyncTests(unittest.TestCase):
    def test_expired_drive_token_is_cleared_and_reauth_message_is_returned(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            agent_dir = Path(temp_dir)
            token_path = agent_dir / "drive_token.json"
            token_path.write_text('{"refresh_token":"stale"}', encoding="utf-8")

            sync = DriveSync(agent_dir, {"google_drive": {"enabled": True, "root_folder_name": "MyPersonalAgent"}})

            with patch("drive_sync.Credentials.from_authorized_user_file") as from_file, patch("drive_sync.Request") as request_cls:
                creds = type("Creds", (), {
                    "expired": True,
                    "refresh_token": "stale",
                    "refresh": lambda self, req: (_ for _ in ()).throw(RuntimeError("invalid_grant: Token has been expired or revoked.")),
                    "to_json": lambda self: "{}",
                    "valid": False,
                })()
                from_file.return_value = creds

                with self.assertRaisesRegex(RuntimeError, "re-authorize"):
                    sync._get_service()

            self.assertFalse(token_path.exists())


if __name__ == "__main__":
    unittest.main()
