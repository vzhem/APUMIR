#!/usr/bin/env python3
"""SQLite regression checks for the actual Room @Query strings; no Android SDK needed.
Run: python3 scripts/ci/check-group-statistics.py
Kotlin period/author calculations have companion JUnit tests in GroupStatsCalculatorTest.
"""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/GroupDao.kt"


def query_for(method):
    source = DAO.read_text()
    end = source.index("suspend fun " + method + "(")
    start = source.rfind("@Query(", 0, end)
    literals = re.findall(r'"(?:[^"\\]|\\.)*"', source[start:end])
    return "".join(json.loads(literal) for literal in literals)


class GroupStatisticsQueriesTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE group_topics(id TEXT PRIMARY KEY, groupId TEXT, name TEXT);
            CREATE TABLE messages(id TEXT PRIMARY KEY, chatId TEXT, topicId TEXT,
                                  timestamp INTEGER, senderId TEXT, content TEXT);
            CREATE TABLE post_views(topicId TEXT, viewerId TEXT, atMs INTEGER,
                                    PRIMARY KEY(topicId, viewerId));
            CREATE TABLE group_message_stats(groupId TEXT, topicId TEXT, dayKey TEXT,
                                            messageCount INTEGER, senderCount INTEGER, sendersCsv TEXT,
                                            PRIMARY KEY(groupId, topicId, dayKey));
        """)
        self.db.executemany("INSERT INTO group_topics VALUES(?,?,?)", [
            ("p1", "a", "First"), ("p2", "a", "Second"), ("empty", "a", "Empty"),
            ("parts", "a", "Parts only"), ("tie", "a", "Tie"), ("other", "b", "Other channel"),
        ])
        self.db.executemany("INSERT INTO messages VALUES(?,?,?,?,?,?)", [
            ("p1-part", "a", "p1", 50, "author1", "APUIMGP1:photo"),
            ("p1-head", "a", "p1", 100, "author1", "Publication"),
            ("p1-comment", "a", "p1", 120, "reader", "Comment"),
            ("p2-head", "a", "p2", 200, "author2", "Publication with file"),
            ("p2-comment1", "a", "p2", 201, "reader", "First comment"),
            ("p2-comment2", "a", "p2", 202, "reader", "Second comment"),
            ("part-only", "a", "parts", 10, "author", "APUIMGP1:text tail"),
            ("a-tie-head", "a", "tie", 300, "author3", "Tie head"),
            ("z-tie-comment", "a", "tie", 300, "reader", "Tie comment"),
            ("b-post", "b", "other", 1, "outsider", "Other channel publication"),
            ("wrong-chat", "b", "p1", 1, "outsider", "Not from this channel"),
            ("orphan", "a", "removed-topic", 1, "author", "Removed topic"),
        ])
        self.db.executemany("INSERT INTO post_views VALUES(?,?,?)", [
            ("p1", "reader1", 150), ("p1", "reader2", 160), ("tie", "reader1", 350),
            ("other", "reader", 5), ("removed-topic", "reader", 5),
        ])

    def tearDown(self):
        self.db.close()

    def posts(self, group_id="a"):
        return [dict(row) for row in self.db.execute(query_for("getChannelPostStats"), {"groupId": group_id})]

    def test_posts_are_separate_from_comments_and_parts(self):
        rows = {row["topicId"]: row for row in self.posts()}
        self.assertEqual({"p1", "p2", "tie"}, set(rows))
        self.assertEqual((100, "author1", 1, 2), tuple(rows["p1"][key] for key in ("publishedAtMs", "authorId", "commentCount", "viewCount")))
        self.assertEqual(2, rows["p2"]["commentCount"])
        self.assertEqual(0, rows["p2"]["viewCount"])
        self.assertEqual(4, sum(row["commentCount"] for row in rows.values()))
        self.assertEqual(3, sum(row["viewCount"] for row in rows.values()))

    def test_tied_timestamps_choose_same_first_message_as_feed(self):
        row = next(row for row in self.posts() if row["topicId"] == "tie")
        self.assertEqual("author3", row["authorId"])
        self.assertEqual(1, row["commentCount"])

    def test_other_channels_and_empty_history_do_not_leak(self):
        self.assertEqual(["other"], [row["topicId"] for row in self.posts("b")])
        self.assertEqual([], self.posts("not-existing"))

    def test_repeated_view_does_not_inflate_total(self):
        self.db.execute("INSERT OR REPLACE INTO post_views VALUES('p1','reader1',999)")
        row = next(row for row in self.posts() if row["topicId"] == "p1")
        self.assertEqual(2, row["viewCount"])

    def test_period_includes_boundaries_but_not_future_other_group_or_topic(self):
        self.db.executemany("INSERT INTO group_message_stats VALUES(?,?,?,?,?,?)", [
            ("a", "", "2026-09-25", 100, 1, "x"),
            ("a", "", "2026-09-26", 1, 1, "x"),
            ("a", "", "2026-09-30", 2, 1, "x"),
            ("a", "", "2026-10-02", 3, 1, "x"),
            ("a", "", "2026-10-03", 200, 1, "x"),
            ("a", "p1", "2026-09-26", 100, 1, "x"),
            ("b", "", "2026-09-26", 100, 1, "x"),
        ])
        rows = list(self.db.execute(query_for("getGroupStatsInRange"), {
            "groupId": "a", "fromDayKey": "2026-09-26", "toDayKey": "2026-10-02",
        }))
        self.assertEqual(["2026-09-26", "2026-09-30", "2026-10-02"], [row["dayKey"] for row in rows])
        self.assertEqual(6, sum(row["messageCount"] for row in rows))


if __name__ == "__main__":
    unittest.main(verbosity=2)
