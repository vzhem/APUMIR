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

    def update(self, id, peer="pk_friend123", q=None, group="group", target="quoted", author="Alice", text="text"):
        return self.db.execute(q or query("applyDirectReplyFromPeer"), {
            "senderId": peer, "messageId": id, "groupId": group,
            "replyToId": target, "replyAuthor": author, "replyText": text,
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

    def test_group_query_and_early_buffer_are_scoped_and_old_query_was_id_only(self):
        self.add("foreign", chat="other", sender="pk_other456")
        self.assertEqual(0, self.update("foreign"))
        old_query = "UPDATE messages SET replyToId=:replyToId, replyAuthor=:replyAuthor, replyText=:replyText WHERE id=:messageId"
        # Reconstruct the former SQL as a negative control; no transport is executed.
        self.assertEqual(1, self.update("foreign", q=old_query))
        self.assertEqual(0, self.update("foreign", q=query("applyGroupReply")))
        self.add("group-parent", chat="group", channel="GROUP")
        self.assertEqual(0, self.update("group-parent", q=query("applyGroupReply"), group="other"))
        self.assertEqual(1, self.update("group-parent", q=query("applyGroupReply"), group="group"))
        group = (BASE / "data/group/GroupRepository.kt").read_text(encoding="utf-8")
        self.assertIn("messageDao.applyGroupReply(", group)
        self.assertIn("pendingReplies.put(packet.groupId, packet.messageId, packet)", group)
        self.assertIn("pendingReplies.take(packet.groupId, localId)", group)
        self.assertNotIn("applyDirectReplyFromPeer", group)

    def test_conflicting_reply_cannot_replace_the_first_but_exact_duplicate_can_ack(self):
        self.add("parent")
        self.assertEqual(1, self.update("parent"))
        self.assertEqual(0, self.update("parent", target="changed"))
        self.assertEqual(0, self.update("parent", author="changed"))
        self.assertEqual(0, self.update("parent", text="changed"))
        self.assertEqual("quoted", self.reply("parent"))
        self.assertEqual(1, self.update("parent"))

    def test_matching_uses_primary_key_lookup(self):
        plan = " ".join(row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + query("applyDirectReplyFromPeer"), {
            "senderId": "pk_friend123", "messageId": "parent", "replyToId": "x", "replyAuthor": "y", "replyText": "z",
        }))
        self.assertIn("INDEX", plan.upper())

    def test_direct_cf_and_mirror_routes_use_distinct_verified_boundaries(self):
        service = (BASE / "service/CoreServerService.kt").read_text(encoding="utf-8")
        direct = service[service.index('"message_received" ->'):]
        reply = direct[direct.index("DirectReplyWire.isPacket(text)"):]
        self.assertIn("directReplies.network(senderId, chatId, messageId, text, sealed && MessageSealer.openedByExpectedSender(rawText, senderId), rawText)", reply[:650])
        self.assertIn("if (!com.vladimir.messenger.data.reply.DirectReplyWire.isPacket(preopened))", direct)
        self.assertIn("if (!com.vladimir.messenger.data.reply.DirectReplyWire.isPacket(opened)) try", direct)
        cf = service[service.index("is RelayEnvelope.Parsed.Message ->"):service.index("is RelayEnvelope.Parsed.Other ->")]
        self.assertLess(cf.index("DirectReplyWire.isPacket(cfContent)"), cf.index("else if (existingMsg != null && cfContent != null)"))
        self.assertIn("MessageSealer.openedByExpectedSender(parsed.content, senderId)", cf)
        self.assertIn("cfContent, authenticated, parsed.content)", cf)
        self.assertIn("== com.vladimir.messenger.data.reply.DirectReplyResult.APPLIED", cf)
        mirror = (BASE / "data/mirror/MirrorSync.kt").read_text(encoding="utf-8")
        self.assertIn('"env", "reply2" ->', mirror)
        self.assertIn('kind == "reply2" && MirrorReplyTrust.afterOpen', mirror)
        self.assertIn('MirrorReplyPayload.forSend(text, replySourceAuthenticated)', mirror)
        self.assertIn('MirrorReplyPayload.forReceive(kind, body.optString("t"), body.optString("r"))', mirror)
        self.assertIn('"t", fields.legacyText', mirror)
        self.assertIn("directReplies.mirror(senderId, messageId, text, replySourceAuthenticated)", service)
        payload = (BASE / "data/mirror/MirrorReplyPayload.kt").read_text(encoding="utf-8")
        self.assertIn('Fields("reply2", "", text, true)', payload)
        trust = (BASE / "data/mirror/MirrorReplyTrust.kt").read_text(encoding="utf-8")
        self.assertIn("MessageDigest.isEqual(local, signer)", trust)
        self.assertIn("FileExchangeKeyStore.publicBinding(context)", trust)

    def test_ack_and_forward_only_follow_verified_scope_checks(self):
        source = (BASE / "data/reply/DirectReplyIngress.kt").read_text(encoding="utf-8")
        self.assertLess(source.index("!authenticated"), source.index("apply(sender, packet)"))
        self.assertLess(source.index("isCleared(sender, parent)"), source.index("apply(sender, packet)"))
        self.assertIn("DirectReplyWire.transportMessageId(parent) != transportId", source)
        self.assertIn('if (applied) sideEffect("ack")', source)
        self.assertIn("catch (cancelled: CancellationException)", source)
        binding = (BASE / "service/DirectReplyRouting.kt").read_text(encoding="utf-8")
        self.assertIn("ChatClearRegistry.isCleared", binding)
        self.assertIn("pins.withPinnedBinding(sender, action)", binding)
        self.assertIn("applyNetwork = pinnedApply::apply", binding)
        self.assertIn("replySourceAuthenticated = true", binding)
        self.assertIn("RustBridge.sendDeliveryAck", binding)
        pinned = (BASE / "data/reply/DirectReplyPinnedApply.kt").read_text(encoding="utf-8")
        self.assertIn("MessageDigest.isEqual(pin, candidate)", pinned)
        self.assertLess(pinned.index("withPin(sender)"), pinned.index("apply(sender, packet)"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
