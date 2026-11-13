#!/usr/bin/env python3
"""Execute the mailbox Room DAO queries on host SQLite, not fake SQL copies.

This tests the SQL only. It is not Android Room, authentication, notification
posting, lifecycle coordination, FCM or end-to-end delivery.
"""
import ast
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/local/dao"


def query(file, name):
    source = (DAO / file).read_text(encoding="utf-8")
    end = source.index(f"suspend fun {name}(")
    start = source.rindex("@Query(", 0, end)
    return "".join(ast.literal_eval(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', source[start:end]))


class MailboxSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE chats(id TEXT PRIMARY KEY, contactId TEXT NOT NULL, lastMessage TEXT,
                               lastMessageTime INTEGER, unreadCount INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE messages(id TEXT PRIMARY KEY, chatId TEXT NOT NULL, content TEXT NOT NULL);
            CREATE TABLE file_transfers(transferId TEXT PRIMARY KEY, chatId TEXT NOT NULL, messageId TEXT NOT NULL);
            CREATE TABLE direct_polls(pollId TEXT PRIMARY KEY, chatId TEXT NOT NULL, messageId TEXT NOT NULL);
            INSERT INTO chats(id, contactId) VALUES('chat', 'peer');
        """)

    def tearDown(self):
        self.db.close()

    def update(self, message, time, chat="chat", peer="peer"):
        return self.db.execute(query("ChatDao.kt", "recordMailboxIncoming"), {
            "chatId": chat, "peer": peer, "message": message, "time": time,
        }).rowcount

    def state(self):
        return self.db.execute("SELECT lastMessage,lastMessageTime,unreadCount FROM chats WHERE id='chat'").fetchone()

    def test_delayed_text_does_not_replace_newer_preview_but_counts_unread(self):
        self.assertEqual(1, self.update("first", 100))
        self.assertEqual(("first", 100, 1), self.state())
        self.assertEqual(1, self.update("older", 20))
        self.assertEqual(("first", 100, 2), self.state())
        self.assertEqual(1, self.update("equal time", 100))
        self.assertEqual(("equal time", 100, 3), self.state())
        self.assertEqual(1, self.update("newer", 101))
        self.assertEqual(("newer", 101, 4), self.state())

    def test_changed_peer_or_missing_chat_never_mutates_another_conversation(self):
        self.assertEqual(0, self.update("wrong", 10, peer="other"))
        self.assertEqual(0, self.update("missing", 10, chat="missing"))
        self.assertEqual((None, None, 0), self.state())
        count = query("ChatDao.kt", "countChatsForContact")
        self.assertEqual(1, self.db.execute(count, {"contactId": "peer"}).fetchone()[0])
        self.db.execute("INSERT INTO chats(id, contactId) VALUES('duplicate', 'peer')")
        self.assertEqual(2, self.db.execute(count, {"contactId": "peer"}).fetchone()[0])
        self.assertEqual(0, self.db.execute(count, {"contactId": "other"}).fetchone()[0])

    def test_preview_failure_rolls_back_the_row_and_unread_transaction(self):
        self.db.execute("CREATE TRIGGER refuse_preview BEFORE UPDATE ON chats BEGIN SELECT RAISE(ABORT, 'test'); END")
        self.db.commit()
        self.db.execute("BEGIN")
        self.db.execute("INSERT INTO messages VALUES('id', 'chat', 'text')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.update("text", 100)
        self.db.rollback()
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM messages").fetchone()[0])
        self.assertEqual((None, None, 0), self.state())

    def test_file_and_poll_ids_are_checked_globally_not_only_in_the_current_chat(self):
        file_query = query("FileTransferDao.kt", "hasMessageTransfer")
        poll_query = query("DirectPollDao.kt", "hasMessagePoll")
        params = {"messageId": "same-id"}
        self.assertEqual(0, self.db.execute(file_query, params).fetchone()[0])
        self.assertEqual(0, self.db.execute(poll_query, params).fetchone()[0])
        self.db.execute("INSERT INTO file_transfers VALUES('file', 'another-chat', 'same-id')")
        self.db.execute("INSERT INTO direct_polls VALUES('poll', 'another-chat', 'same-id')")
        self.assertEqual(1, self.db.execute(file_query, params).fetchone()[0])
        self.assertEqual(1, self.db.execute(poll_query, params).fetchone()[0])
        self.assertEqual(0, self.db.execute(file_query, {"messageId": "other"}).fetchone()[0])
        self.assertEqual(0, self.db.execute(poll_query, {"messageId": "other"}).fetchone()[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
