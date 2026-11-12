#!/usr/bin/env python3
"""Execute Room's actual retry queries on SQLite, including adversarial legacy recipients."""
import ast
import random
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/MessageDao.kt"


TOKENS = re.compile(r'"(?:\\.|[^"\\])*"|\bRETRY_[A-Z_]+\b')
KOTLIN_WHITESPACE = {chr(n) for n in (
    9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195,
    8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288,
)}


def concatenate(expression, constants):
    # SQL annotations use only string literals and the small RETRY_* constants.
    # Unknown constants fail the gate instead of silently omitting a predicate.
    expression = "\n".join(line for line in expression.splitlines() if not line.lstrip().startswith("//"))
    return "".join(ast.literal_eval(token) if token.startswith('"') else constants[token]
                   for token in TOKENS.findall(expression))


def query(name):
    source = DAO.read_text(encoding="utf-8")
    constants = {}
    pattern = r"^private const val (RETRY_[A-Z_]+)\s*=\s*(.*?)(?=\n(?:private const val|@Dao))"
    for match in re.finditer(pattern, source, re.MULTILINE | re.DOTALL):
        constants[match.group(1)] = concatenate(match.group(2), constants)
    end = source.index(f"suspend fun {name}(")
    start = source.rindex("@Query(", 0, end)
    return concatenate(source[start:end], constants)


def intended(recipient, contact="pk_peer"):
    raw = recipient if any(c not in KOTLIN_WHITESPACE for c in recipient) else contact
    if "\0" in raw:
        return ""
    if raw.startswith("pk_"):
        return raw
    if "node=pk_" in raw:
        return "pk_" + raw.split("node=pk_", 1)[1].split("&", 1)[0]
    return raw


class OutboxRecipientSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE chats(id TEXT PRIMARY KEY, contactId TEXT NOT NULL);
            CREATE TABLE groups(id TEXT PRIMARY KEY);
            CREATE TABLE messages(id TEXT PRIMARY KEY, chatId TEXT NOT NULL, timestamp INTEGER NOT NULL,
              isFromMe INTEGER NOT NULL, status TEXT NOT NULL, recipientId TEXT NOT NULL,
              channel TEXT NOT NULL, content TEXT NOT NULL, topicId TEXT);
            CREATE INDEX index_messages_chatId_timestamp ON messages(chatId, timestamp);
            INSERT INTO chats VALUES('chat','pk_peer'),('other','pk_other'),
                ('link-chat','apu://add?node=pk_legacy&name=x');
        """)

    def tearDown(self):
        self.db.close()

    def add(self, id, recipient, timestamp, chat="chat", status="PENDING", mine=1,
            channel="LOCAL", content="body", topic=None):
        self.db.execute("INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?)", (
            id, chat, timestamp, mine, status, recipient, channel, content, topic,
        ))

    def selected(self, name, **params):
        return [row[0] for row in self.db.execute(query(name), params)]

    def test_pending_and_recent_match_recipient_policy_over_varied_links(self):
        recipients = [
            "", "  ", "pk_peer", "pk_other", "pk_peerExtra", "invalid", " pk_peer",
            "apu://add?node=pk_peer", "apu://add?node=pk_peer&name=x",
            "apu://add?node=pk_peerExtra&name=x", "node=pk_other&node=pk_peer",
            "node=pk_peer&node=pk_other", "pk_other&node=pk_peer", "node=pk_&x=1",
            "node=pk_peer\0suffix", "node=pk_peer&name=\0", "pk_peer\0", "\0node=pk_peer",
            "\u0085", "\t\u2003", "\u2003pk_peer",
        ]
        recipients.extend(sorted(KOTLIN_WHITESPACE))
        rng = random.Random(8675309)
        for _ in range(500):
            peer = rng.choice(["pk_peer", "pk_peerExtra", "pk_other", "pk_", "pk_Peer"])
            recipients.append(rng.choice([peer, "node=" + peer, "x?node=" + peer + "&n=ok",
                                           "node=" + peer + "&node=pk_peer"]))
        for n, recipient in enumerate(recipients):
            self.add(f"id{n:04}", recipient, n)
        self.add("other-chat", "pk_peer", -1, chat="other")
        self.add("incoming", "pk_peer", -2, mine=0)
        expected = {f"id{n:04}" for n, recipient in enumerate(recipients)
                    if intended(recipient) == "pk_peer"}
        pending = set(self.selected("getRetryableOutgoingForPeer", peerId="pk_peer", limit=10000))
        recent = set(self.selected("getUnconfirmedOutgoingMessages", chatId="chat", peerId="pk_peer", limit=10000))
        self.assertEqual(expected, pending)
        self.assertEqual(expected, recent)
        plan = " ".join(row[3] for row in self.db.execute(
            "EXPLAIN QUERY PLAN " + query("getRetryableOutgoingForPeer"), {"peerId": "pk_peer", "limit": 20},
        ))
        self.assertIn("index_messages_chatId_timestamp", plan)

    def test_unrelated_rows_do_not_fill_either_bounded_window(self):
        for n in range(100):
            self.add(f"old-wrong{n:03}", "pk_other", n)
            self.add(f"new-wrong{n:03}", "pk_other", 1000 + n, status="SENT")
        for n in range(30):
            self.add(f"right{n:03}", "node=pk_peer&name=x", 200 + n)
        pending = self.selected("getRetryableOutgoingForPeer", peerId="pk_peer", limit=20)
        recent = self.selected("getUnconfirmedOutgoingMessages", chatId="chat", peerId="pk_peer", limit=20)
        self.assertEqual([f"right{n:03}" for n in range(20)], pending)
        self.assertEqual([f"right{n:03}" for n in range(29, 9, -1)], recent)

    def test_discovery_keysets_rotate_equal_timestamps_without_losing_recipient_scope(self):
        for n in range(63):
            self.add(f"m{n:03}", "node=pk_peer&name=x", 42)
        for n in range(40):
            self.add(f"x{n:03}", "pk_other", 42)
        self.add("wrong-chat", "pk_peer", 42, chat="other")
        self.add("incoming", "pk_peer", 42, mine=0)
        ids = []
        after = None
        while True:
            if after is None:
                page = self.selected("getRetryableOutgoingForPeer", peerId="pk_peer", limit=20)
            else:
                page = self.selected("getRetryableOutgoingForPeerAfter", peerId="pk_peer", limit=20,
                                     afterTimestamp=42, afterId=after)
            if not page:
                break
            self.assertLessEqual(len(page), 20)
            ids.extend(page)
            after = page[-1]
        self.assertEqual([f"m{n:03}" for n in range(63)], ids)

        ids = []
        before = None
        while True:
            if before is None:
                page = self.selected("getUnconfirmedOutgoingMessages", chatId="chat", peerId="pk_peer", limit=17)
            else:
                page = self.selected("getUnconfirmedOutgoingMessagesBefore", chatId="chat", peerId="pk_peer",
                                     limit=17, beforeTimestamp=42, beforeId=before)
            if not page:
                break
            ids.extend(page)
            before = page[-1]
        self.assertEqual([f"m{n:03}" for n in reversed(range(63))], ids)

    def test_unconfirmed_count_and_keysets_support_links_without_changing_scope(self):
        self.db.execute("INSERT INTO groups VALUES('group-chat')")
        self.db.execute("INSERT INTO chats VALUES('group-chat','pk_peer')")
        self.add("direct", "pk_original", 1, status="SENT")
        self.add("link", "apu://add?node=pk_saved&n=x", 2, status="SENT")
        self.add("empty", "", 3, status="SENT")
        self.add("fallback-link", "\t\u2003", 4, chat="link-chat", status="SENT")
        self.add("invalid", "not-a-node", 5, status="SENT")
        self.add("nul-link", "node=pk_peer\0suffix", 5, status="SENT")
        self.add("nul-direct", "pk_peer\0suffix", 5, status="SENT")
        self.add("group", "pk_peer", 6, chat="group-chat", status="SENT")
        self.add("deferred_x", "pk_peer", 7, status="SENT")
        self.add("gif", "pk_peer", 8, status="SENT", content="APUGIFREF1|abc")
        self.add("topic", "pk_peer", 9, status="SENT", topic="topic")
        self.add("channel", "pk_peer", 10, status="SENT", channel="GROUP")
        self.add("incoming", "pk_peer", 11, status="SENT", mine=0)
        self.add("ack", "pk_peer", 12, status="DELIVERED")
        count = self.db.execute(query("countUnconfirmedPersonalSent")).fetchone()[0]
        first = self.selected("getUnconfirmedPersonalSent", limit=2)
        second = self.selected("getUnconfirmedPersonalSentAfter", afterTimestamp=2, afterId="link", limit=20)
        self.assertEqual(4, count)
        self.assertEqual(["direct", "link"], first)
        self.assertEqual(["empty", "fallback-link"], second)


if __name__ == "__main__":
    unittest.main(verbosity=2)
