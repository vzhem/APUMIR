#!/usr/bin/env python3
"""Read-state regression: real DAO SQL in SQLite + source wiring, not device/network tests."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


def source(path):
    return (BASE / path).read_text()


def dao_query(name):
    for match in re.finditer(r'@Query\((.*?)\)\s*suspend fun (\w+)\(', source("data/local/dao/MessageDao.kt"), re.S):
        if match.group(2) == name:
            return "".join(re.findall(r'"([^"\n]*)"', match.group(1)))
    raise AssertionError(f"DAO query missing: {name}")


def method(text, name):
    start = text.index(f"fun {name}(")
    end = re.search(r'\n    (?:private |public |internal )?(?:suspend )?fun ', text[start + 1:])
    return text[start:start + 1 + end.start()] if end else text[start:]


class DeleteReadTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("CREATE TABLE messages(id TEXT PRIMARY KEY, chatId TEXT, senderId TEXT, status TEXT, isFromMe INTEGER)")

    def insert(self, status, own=False):
        self.db.execute("INSERT INTO messages VALUES ('message', 'chat', 'author', ?, ?)", (status, int(own)))

    def test_remote_delete_removes_read_and_unread_rows(self):
        sql = dao_query("deleteByIdChatAndSender")
        for status in ("READ", "DELIVERED", "RECEIVED", "SENT", "PENDING"):
            with self.subTest(status=status):
                self.insert(status)
                self.assertEqual(1, self.db.execute(sql, dict(messageId="message", chatId="chat", senderId="author")).rowcount)
                self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM messages").fetchone()[0])

    def test_read_does_not_weaken_sender_or_chat_authorization(self):
        self.insert("READ")
        sql = dao_query("deleteByIdChatAndSender")
        for chat, sender in (("another-chat", "author"), ("chat", "another-author")):
            self.assertEqual(0, self.db.execute(sql, dict(messageId="message", chatId=chat, senderId=sender)).rowcount)
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM messages").fetchone()[0])

    def test_local_group_and_cancel_cleanup_can_delete_read_rows(self):
        for name, params in (("deleteById", dict(messageId="message")),
                             ("deleteByIdAndChat", dict(messageId="message", chatId="chat"))):
            with self.subTest(query=name):
                self.insert("READ", own=True)
                self.assertEqual(1, self.db.execute(dao_query(name), params).rowcount)

    def test_handlers_do_not_gate_deletion_on_read_status(self):
        paths = (
            ("data/repository/MessageDeletionRepository.kt", "deleteForAllDirect"),
            ("data/repository/MessageDeletionRepository.kt", "routeIncoming"),
            ("data/group/GroupRepository.kt", "deleteMessageForAll"),
            ("data/group/GroupRepository.kt", "deleteMessageForMe"),
            ("data/schedule/DeferredCancellationRepository.kt", "deleteVisible"),
        )
        for path, name in paths:
            with self.subTest(handler=name):
                body = method(source(path), name)
                self.assertNotRegex(body, r'\.(?:status|isRead|readAt)\b')

        group = source("data/group/GroupRepository.kt")
        incoming = group[group.index("is GroupWire.Packet.MessageDelete -> {"):group.index("is GroupWire.Packet.PostsRequest -> {")]
        self.assertNotRegex(incoming, r'\.(?:status|isRead|readAt)')

    def test_delete_action_is_based_on_ownership_or_moderation_not_read(self):
        ui = source("ui/screens/chat/ChatDetailScreen.kt")
        block = ui[ui.index("// Раунд 135: удаление своего сообщения"):]
        block = block[:block.index("confirmButton =")]
        self.assertIn("if (message.isFromMe)", block)
        self.assertIn("R.string.chat_delete_for_all", block)
        self.assertNotRegex(block, r'\.(?:status|isRead|readAt)\b')
        for path in ("ui/screens/groups/GroupChatScreen.kt", "ui/screens/channels/ChannelScreen.kt"):
            self.assertIn("canDeleteForAll = uiState.canModerate || isAuthorMe", source(path))


if __name__ == "__main__":
    unittest.main(verbosity=2)
