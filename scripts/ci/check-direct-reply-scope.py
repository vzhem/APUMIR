#!/usr/bin/env python3
"""Execute the actual Room reply queries on SQLite and check ingress wiring.

These are SQL/source contracts, not transport or identity-authentication tests.
"""
import ast
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
DAO = BASE / "data/local/dao/MessageDao.kt"


def query(name):
    source = DAO.read_text(encoding="utf-8")
    end = source.index(f"suspend fun {name}(")
    start = source.rindex("@Query(", 0, end)
    return "".join(ast.literal_eval(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', source[start:end]))


class DirectReplyScopeTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE chats(id TEXT PRIMARY KEY, contactId TEXT NOT NULL);
            CREATE TABLE groups(id TEXT PRIMARY KEY);
            CREATE TABLE messages(id TEXT PRIMARY KEY, chatId TEXT NOT NULL, senderId TEXT NOT NULL,
                isFromMe INTEGER NOT NULL, channel TEXT NOT NULL, topicId TEXT,
                replyToId TEXT, replyAuthor TEXT NOT NULL, replyText TEXT NOT NULL);
            INSERT INTO chats VALUES ('chat', 'pk_friend123'), ('other', 'pk_other456'),
                                     ('group', 'pk_friend123');
            INSERT INTO groups VALUES ('group');
        """)

    def tearDown(self):
        self.db.close()

    def add(self, id, chat="chat", sender="pk_friend123", mine=0, channel="LOCAL", topic=None):
        self.db.execute("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, NULL, '', '')",
                        (id, chat, sender, mine, channel, topic))

    def update(self, id, peer="pk_friend123", q=None):
        return self.db.execute(q or query("applyDirectReplyFromPeer"), {
            "senderId": peer, "messageId": id, "replyToId": "quoted", "replyAuthor": "Alice", "replyText": "text",
        }).rowcount

    def reply(self, id):
        return self.db.execute("SELECT replyToId FROM messages WHERE id=?", (id,)).fetchone()[0]

    def test_matching_incoming_personal_row_and_duplicate_apply(self):
        self.add("parent")
        self.assertEqual(1, self.update("parent"))
        self.assertEqual(1, self.update("parent"))
        self.assertEqual("quoted", self.reply("parent"))

    def test_foreign_sender_changed_chat_outgoing_group_and_topic_are_excluded(self):
        rows = [
            ("foreign", "other", "pk_other456", 0, "LOCAL", None),
            ("moved", "other", "pk_friend123", 0, "LOCAL", None),
            ("mine", "chat", "pk_friend123", 1, "LOCAL", None),
            ("group-parent", "group", "pk_friend123", 0, "LOCAL", None),
            ("legacy-group", "chat", "pk_friend123", 0, "GROUP", None),
            ("topic", "chat", "pk_friend123", 0, "LOCAL", "t"),
            ("orphan", "missing", "pk_friend123", 0, "LOCAL", None),
        ]
        for row in rows:
            self.add(*row)
            self.assertEqual(0, self.update(row[0]), row[0])
            self.assertIsNone(self.reply(row[0]), row[0])
        self.assertEqual(0, self.update("missing-parent"))

    def test_scope_is_evaluated_at_update_not_just_at_an_earlier_lookup(self):
        self.add("parent")
        self.db.execute("UPDATE chats SET contactId='pk_other456' WHERE id='chat'")
        self.assertEqual(0, self.update("parent"))
        self.assertIsNone(self.reply("parent"))
        self.db.execute("UPDATE chats SET contactId='pk_friend123' WHERE id='chat'")
        self.assertEqual(1, self.update("parent"))

    def test_existing_group_query_is_unchanged_and_negative_control_was_id_only(self):
        self.add("foreign", chat="other", sender="pk_other456")
        self.assertEqual(0, self.update("foreign"))
        # GroupRepository still uses the historical ID-only query. This negative
        # control demonstrates its scope, not an attack or a group-path fix.
        self.assertEqual(1, self.update("foreign", q=query("applyReply")))
        self.assertEqual("quoted", self.reply("foreign"))
        group = (BASE / "data/group/GroupRepository.kt").read_text(encoding="utf-8")
        self.assertIn("messageDao.applyReply(", group)
        self.assertNotIn("applyDirectReplyFromPeer", group)

    def test_matching_uses_primary_key_lookup(self):
        plan = " ".join(row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + query("applyDirectReplyFromPeer"), {
            "senderId": "pk_friend123", "messageId": "parent", "replyToId": "x", "replyAuthor": "y", "replyText": "z",
        }))
        self.assertIn("INDEX", plan.upper())

    def test_direct_cf_and_mirror_routes_use_distinct_verified_boundaries(self):
        service = (BASE / "service/CoreServerService.kt").read_text(encoding="utf-8")
        direct = service[service.index('"message_received" ->'):]
        reply = direct[direct.index("DirectReplyWire.isPacket(text)"):]
        self.assertIn("directReplies.network(senderId, chatId, messageId, text, sealed && MessageSealer.openedByExpectedSender(rawText, senderId))", reply[:650])
        self.assertIn("if (!com.vladimir.messenger.data.reply.DirectReplyWire.isPacket(preopened))", direct)
        cf = service[service.index("is RelayEnvelope.Parsed.Message ->"):service.index("is RelayEnvelope.Parsed.Other ->")]
        self.assertLess(cf.index("DirectReplyWire.isPacket(cfContent)"), cf.index("else if (existingMsg != null)"))
        self.assertIn("MessageSealer.openedByExpectedSender(parsed.content, senderId)", cf)
        self.assertIn("== com.vladimir.messenger.data.reply.DirectReplyResult.APPLIED", cf)
        mirror = (BASE / "data/mirror/MirrorSync.kt").read_text(encoding="utf-8")
        self.assertIn('"env", "reply2" ->', mirror)
        self.assertIn('kind == "reply2" && MirrorReplyTrust.afterOpen', mirror)
        self.assertIn('&& !replySourceAuthenticated)) return', mirror)
        self.assertIn('"reply2" else "env"', mirror)
        self.assertIn("directReplies.mirror(senderId, messageId, text, replySourceAuthenticated)", service)

    def test_ack_and_forward_only_follow_verified_scope_checks(self):
        source = (BASE / "data/reply/DirectReplyIngress.kt").read_text(encoding="utf-8")
        self.assertLess(source.index("!authenticated"), source.index("apply(sender, packet)"))
        self.assertLess(source.index("isCleared(sender, parent)"), source.index("apply(sender, packet)"))
        self.assertIn("DirectReplyWire.transportMessageId(parent) != transportId", source)
        self.assertIn('if (applied) sideEffect("ack")', source)
        self.assertIn("catch (cancelled: CancellationException)", source)
        binding = (BASE / "service/DirectReplyRouting.kt").read_text(encoding="utf-8")
        self.assertIn("ChatClearRegistry.isCleared", binding)
        self.assertIn("replySourceAuthenticated = true", binding)
        self.assertIn("RustBridge.sendDeliveryAck", binding)


if __name__ == "__main__":
    unittest.main(verbosity=2)
