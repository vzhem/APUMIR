#!/usr/bin/env python3
"""Execute the notification/read DAO SQL on host SQLite, without pretending it is Android Room."""
import ast
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/local/dao"


def query(file, name):
    text = (DAO / file).read_text(encoding="utf-8")
    end = text.index(f"suspend fun {name}(")
    start = text.rindex("@Query(", 0, end)
    return "".join(ast.literal_eval(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', text[start:end]))


class NotificationSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
          CREATE TABLE chats(id TEXT PRIMARY KEY, unreadCount INTEGER NOT NULL);
          CREATE TABLE groups(id TEXT PRIMARY KEY, unreadCount INTEGER NOT NULL);
          CREATE TABLE group_topics(id TEXT PRIMARY KEY, groupId TEXT NOT NULL, unreadCount INTEGER NOT NULL);
          CREATE TABLE messages(id TEXT PRIMARY KEY, chatId TEXT NOT NULL, senderId TEXT NOT NULL,
            recipientId TEXT NOT NULL, content TEXT NOT NULL, timestamp INTEGER NOT NULL, topicId TEXT,
            channel TEXT NOT NULL, isFromMe INTEGER NOT NULL, notificationPending INTEGER NOT NULL);
          CREATE INDEX index_messages_notification_pending ON messages(isFromMe, notificationPending);
          INSERT INTO chats VALUES('chat',1),('other',1);
          INSERT INTO groups VALUES('group',2),('other-group',1);
          INSERT INTO group_topics VALUES('topic-a','group',1),('topic-b','group',1);
        """)

    def tearDown(self):
        self.db.close()

    def add(self, id, *, rowid=None, chat="chat", sender="friend", recipient="me", content="text",
            timestamp=1, topic=None, channel="LOCAL", mine=0, pending=1):
        self.db.execute("INSERT INTO messages(rowid,id,chatId,senderId,recipientId,content,timestamp,topicId,channel,isFromMe,notificationPending) "
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?)", (rowid, id, chat, sender, recipient, content, timestamp, topic, channel, mine, pending))

    def run_dao(self, file, name, **args):
        return self.db.execute(query(file, name), args)

    def pending(self, id):
        return self.db.execute("SELECT notificationPending FROM messages WHERE id=?", (id,)).fetchone()[0]

    def complete(self, **change):
        args = dict(id="id", chatId="chat", senderId="friend", recipientId="me", content="text", timestamp=1, topicId=None, channel="LOCAL")
        args.update(change)
        return self.run_dao("MessageDao.kt", "completePendingNotification", **args).rowcount

    def test_snapshot_completion_requires_every_shown_field_and_never_rearms(self):
        self.add("id")
        for changed in (dict(id="wrong"), dict(chatId="other"), dict(senderId="other"), dict(recipientId="other"),
                        dict(content="changed"), dict(timestamp=2), dict(topicId=""), dict(topicId="topic"), dict(channel="GROUP")):
            self.assertEqual(0, self.complete(**changed))
            self.assertEqual(1, self.pending("id"))
        self.assertEqual(1, self.complete())
        self.assertEqual(0, self.complete())
        self.db.execute("UPDATE messages SET notificationPending=1,isFromMe=1 WHERE id='id'")
        self.assertEqual(0, self.complete())
        self.assertEqual(1, self.pending("id"))
        self.db.execute("UPDATE messages SET isFromMe=0,topicId='a' WHERE id='id'")
        self.assertEqual(1, self.complete(topicId="a"))

    def test_bounded_pages_include_all_rowid_signs_and_exclude_other_recipients(self):
        self.add("negative", rowid=-5, timestamp=999)
        self.add("zero", rowid=0, timestamp=-500)
        self.add("other-recipient", rowid=1, recipient="other")
        self.add("outgoing", rowid=2, mine=1)
        self.add("legacy-recipient", rowid=3, recipient="")
        self.add("last", rowid=4, timestamp=0)
        bound = self.run_dao("MessageDao.kt", "maxPendingNotificationRowId", recipientId="me").fetchone()[0]
        self.assertEqual(4, bound)
        page = lambda after, end, limit: self.run_dao("MessageDao.kt", "pendingNotificationPage", recipientId="me",
                                                       after=after, through=end, limit=limit).fetchall()
        self.assertEqual([(-5, "negative"), (0, "zero")], page(None, bound, 2))
        self.assertEqual([(3, "legacy-recipient"), (4, "last")], page(0, bound, 10))
        self.add("new", rowid=5)
        self.assertEqual([], page(4, bound, 10))
        self.assertEqual(5, self.run_dao("MessageDao.kt", "maxPendingNotificationRowId", recipientId="me").fetchone()[0])
        self.assertEqual([(5, "new")], page(4, 5, 10))
        plan = " ".join(row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + query("MessageDao.kt", "pendingNotificationPage"),
                                                           dict(recipientId="me", after=None, through=5, limit=10)))
        self.assertIn("index_messages_notification_pending", plan)

    def test_direct_read_does_not_clear_other_chat_group_topic_or_outgoing(self):
        self.add("direct")
        self.add("other", chat="other")
        self.add("outgoing", mine=1)
        self.add("group", chat="group", channel="GROUP", topic="topic-a")
        self.add("collision", chat="group", channel="LOCAL")
        self.add("topic", topic="not-direct")
        count = self.run_dao("MessageDao.kt", "clearPendingForReadDirectChat", chatId="chat").rowcount
        self.assertEqual(1, count)
        self.assertEqual(0, self.pending("direct"))
        for id in ("other", "outgoing", "group", "collision", "topic"):
            self.assertEqual(1, self.pending(id))
        self.assertEqual(0, self.run_dao("MessageDao.kt", "clearPendingForReadDirectChat", chatId="group").rowcount)

    def test_group_and_topic_reads_never_touch_other_scopes(self):
        self.add("a", chat="group", channel="GROUP", topic="topic-a")
        self.add("b", chat="group", channel="GROUP", topic="topic-b")
        self.add("foreign", chat="other-group", channel="GROUP", topic="topic-a")
        self.add("personal", chat="group", channel="LOCAL", topic="topic-a")
        self.add("outgoing", chat="group", channel="GROUP", topic="topic-a", mine=1)
        self.assertEqual(0, self.run_dao("GroupDao.kt", "clearPendingTopicAlerts", groupId="other-group", topicId="topic-a").rowcount)
        self.assertEqual(1, self.run_dao("GroupDao.kt", "clearPendingTopicAlerts", groupId="group", topicId="topic-a").rowcount)
        self.assertEqual(0, self.pending("a"))
        for id in ("b", "foreign", "personal", "outgoing"):
            self.assertEqual(1, self.pending(id))
        self.assertEqual(1, self.run_dao("GroupDao.kt", "clearPendingGroupAlerts", groupId="group").rowcount)
        self.assertEqual(0, self.pending("b"))
        for id in ("foreign", "personal", "outgoing"):
            self.assertEqual(1, self.pending(id))
        self.db.execute("DELETE FROM groups WHERE id='other-group'")
        self.assertEqual(0, self.run_dao("GroupDao.kt", "clearPendingGroupAlerts", groupId="other-group").rowcount)
        self.assertEqual(1, self.pending("foreign"))

    def test_read_updates_roll_back_with_the_pending_work_on_sql_failure(self):
        self.add("id")
        self.db.commit()
        self.db.execute("CREATE TRIGGER fail_pending BEFORE UPDATE OF notificationPending ON messages BEGIN SELECT RAISE(ABORT, 'fail'); END")
        with self.assertRaises(sqlite3.DatabaseError):
            with self.db:
                self.run_dao("ChatDao.kt", "markAsRead", chatId="chat")
                self.run_dao("MessageDao.kt", "clearPendingForReadDirectChat", chatId="chat")
        self.assertEqual(1, self.db.execute("SELECT unreadCount FROM chats WHERE id='chat'").fetchone()[0])
        self.assertEqual(1, self.pending("id"))

    def test_new_arrival_after_read_transaction_keeps_its_flag(self):
        self.add("old")
        with self.db:
            self.run_dao("ChatDao.kt", "markAsRead", chatId="chat")
            self.run_dao("MessageDao.kt", "clearPendingForReadDirectChat", chatId="chat")
        self.add("new")
        self.assertEqual(0, self.pending("old"))
        self.assertEqual(1, self.pending("new"))
        self.assertEqual(0, self.pending("old"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
