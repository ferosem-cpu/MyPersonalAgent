from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from storage import JsonStorage, _updated_dt


class SyncTimestampTests(unittest.TestCase):
    def test_mixed_offset_timestamps_compare_correctly(self) -> None:
        # Same instant, expressed with two different UTC offsets (e.g. server
        # in +05:30, phone client in +00:00) - must compare as EQUAL in UTC,
        # not by naive string comparison (which would treat these as different).
        server_time = "2026-08-01T14:42:42+05:30"
        client_time_utc = "2026-08-01T09:12:42+00:00"
        self.assertEqual(_updated_dt(server_time), _updated_dt(client_time_utc))

        # A client timestamp that is chronologically LATER but has a smaller
        # raw string value (earlier offset) must still be recognized as later.
        earlier_local = "2026-08-01T20:00:00+05:30"  # 14:30 UTC
        later_utc_but_smaller_string = "2026-08-01T15:00:00+00:00"  # 15:00 UTC, later
        self.assertLess(_updated_dt(earlier_local), _updated_dt(later_utc_but_smaller_string))

    def test_upsert_last_write_wins_across_offsets(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            config = {
                "tracker_json": "data/worklog.json",
                "todos_json": "data/todos.json",
                "memory_json": "data/memory.json",
                "contacts_json": "data/contacts.json",
            }
            s = JsonStorage(base, config)

            item_id = "fixed-id-1"
            # Server writes first at 09:00 UTC (as +05:30 local time).
            s.upsert_item("todos", {
                "id": item_id, "title": "v1", "updated": "2026-08-01T14:30:00+05:30",
            })
            # Client pushes an update that is chronologically EARLIER (08:00 UTC)
            # but its raw string representation (+00:00 offset) would sort
            # differently than the server's +05:30 string. Server copy must win.
            winner = s.upsert_item("todos", {
                "id": item_id, "title": "v2-stale", "updated": "2026-08-01T08:00:00+00:00",
            })
            self.assertEqual(winner["title"], "v1")

            # Now a genuinely later client update (10:00 UTC) must win.
            winner2 = s.upsert_item("todos", {
                "id": item_id, "title": "v3-newer", "updated": "2026-08-01T10:00:00+00:00",
            })
            self.assertEqual(winner2["title"], "v3-newer")


if __name__ == "__main__":
    unittest.main()
