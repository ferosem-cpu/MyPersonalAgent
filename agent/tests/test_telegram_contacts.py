from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from storage import JsonStorage
from telegram_bot import TelegramBot

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class TelegramContactTests(unittest.TestCase):
    def test_add_contact_persists_contact(self) -> None:
        config = {
            "tracker_json": "../tracker/data/worklog.json",
            "todos_json": "../tracker/data/todos.json",
            "memory_json": "../tracker/data/memory.json",
            "contacts_json": "contacts.json",
        }
        storage = JsonStorage(Path("."), config)

        contact = storage.add_contact("Jane Doe", phone_number="+123456789", telegram_user_id="42")

        self.assertEqual(contact["name"], "Jane Doe")
        self.assertEqual(storage.contacts()["contacts"][0]["phone_number"], "+123456789")

    def test_telegram_contact_message_updates_storage(self) -> None:
        config = {
            "tracker_json": "../tracker/data/worklog.json",
            "todos_json": "../tracker/data/todos.json",
            "memory_json": "../tracker/data/memory.json",
            "contacts_json": "contacts.json",
        }
        storage = JsonStorage(Path("."), config)
        bot = TelegramBot(storage)
        bot.token = "fake-token"
        bot.send = lambda chat_id, text: None

        update = {
            "message": {
                "chat": {"id": "123"},
                "contact": {
                    "first_name": "Jane",
                    "last_name": "Doe",
                    "phone_number": "+123456789",
                    "user_id": 42,
                },
            }
        }

        bot.handle_update(update)

        contacts = storage.contacts()["contacts"]
        self.assertTrue(any(c["first_name"] == "Jane" and c["phone_number"] == "+123456789" for c in contacts))

    def test_telegram_contact_sends_vcf_file(self) -> None:
        config = {
            "tracker_json": "../tracker/data/worklog.json",
            "todos_json": "../tracker/data/todos.json",
            "memory_json": "../tracker/data/memory.json",
            "contacts_json": "contacts.json",
        }
        storage = JsonStorage(Path("."), config)
        bot = TelegramBot(storage)
        bot.token = "fake-token"
        bot.send = lambda chat_id, text: None

        with patch("telegram_bot.requests.post") as post_mock:
            bot._handle_text_contact("123", "Name: Jane Doe\nPhone: +123456789")

        self.assertTrue(post_mock.called)
        args, kwargs = post_mock.call_args
        self.assertIn("sendDocument", args[0])
        self.assertIn("files", kwargs)
        self.assertIn("document", kwargs["files"])

    def test_contact_vcf_is_stored_in_tracker_data_contacts_folder(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp_dir:
            base_dir = Path(temp_dir)
            config = {
                "tracker_json": "data/worklog.json",
                "todos_json": "data/todos.json",
                "memory_json": "data/memory.json",
                "contacts_json": "data/contacts.json",
            }
            storage = JsonStorage(base_dir, config)

            contact = storage.add_contact("Mina", phone_number="+987654321")
            vcf_path = storage.save_contact_vcf(contact)

            self.assertTrue(vcf_path.exists())
            self.assertEqual(vcf_path.parent, base_dir / "data" / "contacts")
            self.assertFalse((base_dir / "uploads" / "Contacts").exists())


if __name__ == "__main__":
    unittest.main()
