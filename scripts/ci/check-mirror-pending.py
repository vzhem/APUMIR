#!/usr/bin/env python3
"""Exercise the actual pending-mirror SQL and guard its integration seams.

SQLite and source checks are not a substitute for Room, device identity or a
paired-network test. A negative control documents the old group starvation.
"""
import importlib.util
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
TEST = ROOT / "android-app/app/src/androidTest/java/com/vladimir/messenger/data/repository/MirrorPendingOutgoingInstrumentedTest.kt"


def source(path):
    return (MAIN / path).read_text(encoding="utf-8")


def pending_sql(method="pendingMirrorOutgoing"):
    dao = source("data/local/dao/MessageDao.kt")
    before = dao.split(f"suspend fun {method}(", 1)[0]
    annotation = before.rsplit("@Query(", 1)[1].rsplit(")", 1)[0]
    return "".join(re.findall(r'"([^"]*)"', annotation))


class MirrorPendingContract(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE messages (
                id TEXT PRIMARY KEY, chatId TEXT NOT NULL, isFromMe INTEGER NOT NULL,
                status TEXT NOT NULL, channel TEXT NOT NULL, topicId TEXT, timestamp INTEGER NOT NULL
            );
            CREATE TABLE groups (id TEXT PRIMARY KEY);
            CREATE INDEX index_messages_outbox_status_order ON messages(isFromMe, status, timestamp, id);
            CREATE INDEX index_messages_isFromMe_timestamp ON messages(isFromMe, timestamp);
        """)

    def insert(self, message_id, chat="chat", mine=1, status="PENDING", channel="UNKNOWN", topic=None, ts=42):
        self.db.execute("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?)",
                        (message_id, chat, mine, status, channel, topic, ts))

    def select(self, limit):
        return [row[0] for row in self.db.execute(pending_sql(), {"lim": limit})]

    def test_personal_scope_is_applied_before_the_limit(self):
        self.db.execute("INSERT INTO groups VALUES ('group')")
        for i in range(55):
            self.insert(f"group-{i:02}", chat="group", ts=1)
        self.insert("legacy-group", channel="GROUP", ts=2)
        self.insert("topic", topic="thread", ts=3)
        self.insert("incoming", mine=0, ts=4)
        self.insert("delivered", status="DELIVERED", ts=5)
        for message_id in ("c", "a", "b"):
            self.insert(message_id)
        self.assertEqual(["a", "b"], self.select(2))
        self.assertEqual(["a", "b", "c"], self.select(50))
        self.assertEqual(3, self.db.execute(pending_sql("countPendingMirrorOutgoing")).fetchone()[0])
        after = self.db.execute(pending_sql("pendingMirrorOutgoingAfter"),
                                {"afterTimestamp": 42, "afterId": "a", "lim": 2})
        self.assertEqual(["b", "c"], [row[0] for row in after])
        # The previous production query consumed the first page with groups.
        old = "SELECT id FROM messages WHERE isFromMe = 1 AND status IN ('PENDING', 'QUEUED_OFFLINE') ORDER BY timestamp ASC LIMIT :lim"
        self.assertTrue(all(row[0].startswith("group-") for row in self.db.execute(old, {"lim": 2})))

    def test_pending_and_queued_are_both_stable_and_other_statuses_are_not(self):
        self.insert("z", status="PENDING")
        self.insert("a", status="QUEUED_OFFLINE")
        self.insert("d", status="SENT", ts=1)
        self.insert("r", status="READ", ts=1)
        self.assertEqual(["a", "z"], self.select(50))
        self.assertEqual([], self.select(0))
        self.assertEqual(2, self.db.execute(pending_sql("countPendingMirrorOutgoing")).fetchone()[0])

    def test_keyset_visits_equal_timestamps_in_sqlite_binary_order(self):
        for message_id in ("z", "😀", "я", "a", "б"):
            self.insert(message_id)
        ids = self.select(50)
        visited = []
        after_id = None
        while True:
            if after_id is None:
                page = self.select(2)
            else:
                page = [row[0] for row in self.db.execute(pending_sql("pendingMirrorOutgoingAfter"),
                        {"afterTimestamp": 42, "afterId": after_id, "lim": 2})]
            if not page:
                break
            visited.extend(page)
            after_id = page[-1]
        self.assertEqual(ids, visited)
        self.assertEqual(len(ids), len(set(visited)))

    def test_pending_pages_use_the_migrated_status_index_in_a_large_sent_history(self):
        self.db.executemany("INSERT INTO messages VALUES (?, ?, 1, 'SENT', 'UNKNOWN', NULL, ?)",
                            ((f"sent-{n:05}", "chat", n) for n in range(20_000)))
        self.insert("pending", ts=30_000)
        for method, params in (("pendingMirrorOutgoing", {"lim": 50}),
                               ("pendingMirrorOutgoingAfter", {"afterTimestamp": 10_000, "afterId": "x", "lim": 50}),
                               ("countPendingMirrorOutgoing", {})):
            sql = pending_sql(method)
            plan = self.db.execute("EXPLAIN QUERY PLAN " + sql, params).fetchall()
            self.assertTrue(any("index_messages_outbox_status_order" in row[3] for row in plan), plan)
            steps = [0]
            def tick():
                steps[0] += 100
                return 0
            self.db.set_progress_handler(tick, 100)
            try:
                rows = self.db.execute(sql, params).fetchall()
            finally:
                self.db.set_progress_handler(None, 0)
            self.assertEqual(1, len(rows))
            self.assertEqual(1 if method == "countPendingMirrorOutgoing" else "pending", rows[0][0])
            self.assertLess(steps[0], 5_000, (method, steps[0]))
        # Negative control: the timestamp-only plan scans the retained SENT rows.
        slower = pending_sql().replace("index_messages_outbox_status_order", "index_messages_isFromMe_timestamp")
        steps = [0]
        def tick_slow():
            steps[0] += 100
            return 0
        self.db.set_progress_handler(tick_slow, 100)
        try:
            self.assertEqual("pending", self.db.execute(slower, {"lim": 50}).fetchone()[0])
        finally:
            self.db.set_progress_handler(None, 0)
        self.assertGreaterEqual(steps[0], 5_000)
        entity = source("data/local/entity/MessageEntity.kt")
        database = source("data/local/AppDatabase.kt")
        module = source("di/AppModule.kt")
        self.assertIn('name = "index_messages_outbox_status_order"', entity)
        self.assertIn("MIGRATION_41_42", database)
        self.assertIn("CREATE INDEX IF NOT EXISTS `index_messages_outbox_status_order`", database)
        self.assertIn("AppDatabase.MIGRATION_41_42", module)

    def test_repository_uses_rechecked_scoped_mapper_and_the_receiver_uses_reply_fields(self):
        repo = source("data/repository/ChatRepository.kt")
        helper = source("data/repository/MirrorPendingOutgoing.kt")
        dispatcher = source("data/repository/MirrorOutgoingDispatch.kt")
        mirror = source("data/mirror/MirrorSync.kt")
        self.assertIn("roomMirrorPendingOutgoing(context, messageDao, chatDao, groupDao, automaticOutboxSender)", repo)
        self.assertIn("mirrorPendingFrames.page(limit)", repo)
        for required in ("FairOutboxScan<MessageEntity>(maxAttemptsPerCycle = MAX_ROWS)",
                         "countAtStart = messages::countPendingMirrorOutgoing",
                         "messages.pendingMirrorOutgoingAfter(after.timestamp, after.id, limit)",
                         "takeIf { it == snapshot }", "!isPersonalChat(row.chatId)",
                         "mirrorPeerPermitted(frame.recipientId, frame.id, isCleared)",
                         "preflight(frame.id, frame.chatId, OutboxRecipient.normalize(frame.recipientId))",
                         "replyToId = target", "replyAuthor = if (target.isNotEmpty()) replyAuthor else",
                         "replyText = if (target.isNotEmpty()) replyText else", "textColor = textColor",
                         "senderNameColor = senderNameColor", "limit.coerceAtMost(MAX_ROWS)"):
            self.assertIn(required, helper)
        self.assertIn("reply = frame.replyToId.takeIf", dispatcher)
        self.assertIn("DirectReplyWire.Target(it, frame.replyAuthor, frame.replyText)", dispatcher)
        for field in ("reply_to", "reply_author", "reply_text", "text_color", "sender_name_color"):
            self.assertIn(f'.put("{field}",', mirror)
            self.assertIn(f'optString("{field}")', mirror)

    def test_maintenance_invokes_a_single_flight_throttled_flusher(self):
        mirror = source("data/mirror/MirrorSync.kt")
        flusher = source("data/mirror/MirrorPendingFlusher.kt")
        loop = mirror.split("private suspend fun maintenanceLoop()", 1)[1].split("private fun onPartnerFrame(", 1)[0]
        self.assertIn("pendingFlusher.request()", loop)
        self.assertGreaterEqual(mirror.count("pendingFlusher.wake()"), 2)
        for required in ("available = { mirrorPendingAvailable(canClaimEngine(), closedByUs, wsOpen, ::pairingTransportAvailable) }",
                         "mirrorPendingPartnerWake(partnerFresh(), partnerEng, obj.optInt(\"eng\") == 1)",
                         "if (wakePending) pendingFlusher.wake()", "@Volatile private var closedByUs = false",
                         "load = bridge::pendingOutgoing", "offer = ::publishPendingOutgoing",
                         "pendingFlusher.noteNewWork()"):
            self.assertIn(required, mirror)
        for required in ("canClaim && !closed && (wsOpen || pairingAvailable())",
                         "!wasFresh || (!wasActive && isActive)",
                         "if (!available()) return", "if (inFlight ||", "synchronized(gate)",
                         "job.invokeOnCompletion", "currentCoroutineContext().ensureActive()",
                         "if (!available()) { completed = false; break }", "const val PAGE_SIZE = 50",
                         "const val PAGE_DELAY_MS = 20_000L", "const val MAX_SWEEP_DELAY_MS = 10 * 60_000L",
                         "workGeneration != generation", "moreToScan"):
            self.assertIn(required, flusher)
        request = flusher.split("fun request()", 1)[1].split("val job =", 1)[0]
        self.assertLess(request.index("synchronized(gate)"), request.index("val now = nowMs()"))

    def test_all_pending_jvm_cases_are_required_by_the_report_gate(self):
        checker_path = ROOT / "scripts/ci/check-push-mailbox-jvm-results.py"
        spec = importlib.util.spec_from_file_location("jvm_report_gate", checker_path)
        checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checker)
        self.assertEqual(3, len(checker.MIRROR_PENDING_EXPECTED))
        self.assertEqual(28, sum(map(len, checker.MIRROR_PENDING_EXPECTED.values())))
        test_root = ROOT / "android-app/app/src/test/java"
        compile_script = (ROOT / "scripts/ci/check-android-compile.sh").read_text(encoding="utf-8")
        for classname, expected in checker.MIRROR_PENDING_EXPECTED.items():
            kotlin = (test_root / (classname.replace(".", "/") + ".kt")).read_text(encoding="utf-8")
            actual = set(re.findall(r"@Test\s+fun\s+(\w+)\(", kotlin))
            self.assertEqual(expected, actual, classname)
            self.assertIn(f"--tests '*{classname.rsplit('.', 1)[1]}*'", compile_script)
        self.assertIn("check-push-mailbox-jvm-results.py", compile_script)
        for workflow in ("build-release.yml", "unit-tests.yml"):
            self.assertIn("check-push-mailbox-jvm-results.py", (ROOT / ".github/workflows" / workflow).read_text())

    def test_outgoing_frames_require_paired_trust_after_open_before_dispatch(self):
        mirror = source("data/mirror/MirrorSync.kt")
        outgoing = mirror.split('"out" -> {', 1)[1].split('"batch" ->', 1)[0]
        self.assertIn("MirrorOutgoingFrame.open(context, nodeId, wire, ::openPayload)", outgoing)
        self.assertNotIn("openPayload(wire)?.let", outgoing)
        self.assertIn("bridge.onOutgoingFromPartner(row)", outgoing)
        guard = source("data/mirror/MirrorOutgoingFrame.kt")
        self.assertIn("MirrorReplyTrust.pairedAfterOpen(context, nodeId, it)", guard)
        self.assertLess(guard.index("val body = openPayload(wire)"), guard.index("pairedAfterOpen(wire)"))
        trust = source("data/mirror/MirrorReplyTrust.kt")
        self.assertIn("verified(true, MirrorRoomToken.loadSyncKey(context), wire", trust)
        self.assertIn("MessageDigest.isEqual(local, signer)", trust)
        checker_path = ROOT / "scripts/ci/check-push-mailbox-jvm-results.py"
        spec = importlib.util.spec_from_file_location("mirror_trust_report_gate", checker_path)
        checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checker)
        kotlin = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/mirror/MirrorOutgoingFrameTest.kt").read_text(encoding="utf-8")
        self.assertEqual(checker.MIRROR_OUTGOING_FRAME_EXPECTED, set(re.findall(r"@Test\s+fun\s+(\w+)\(", kotlin)))
        compile_script = (ROOT / "scripts/ci/check-android-compile.sh").read_text()
        self.assertIn("--tests '*MirrorOutgoingFrameTest*'", compile_script)
        dispatch_test = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/repository/MirrorOutgoingDispatcherTest.kt").read_text(encoding="utf-8")
        self.assertEqual(checker.MIRROR_OUTGOING_DISPATCH_EXPECTED, set(re.findall(r"@Test\s+fun\s+(\w+)\(", dispatch_test)))
        self.assertIn("--tests '*MirrorOutgoingDispatcherTest*'", compile_script)
        sent_echo = mirror.split("fun publishSentEcho(", 1)[1].split("fun publishOutgoing(", 1)[0]
        self.assertIn('"sent"', sent_echo)
        self.assertIn('senderId = ""', sent_echo)
        policy = source("data/repository/MirrorOutgoingPolicy.kt")
        self.assertIn('row.senderId != "self"', policy)
        ffi = (ROOT / "android-app/app/src/androidTest/java/com/vladimir/messenger/data/reply/DirectReplyRealFfiInstrumentedTest.kt").read_text(encoding="utf-8")
        for seam in ("MirrorOutgoingFrame.open(isolatedContext, recipientId, wire)",
                     "routeOutgoing(forgedOutgoing)", "routeOutgoing(realOutgoing)",
                     "relabeled-echo", "routeOutgoing(MirrorFrameCipher.seal(roomKey", "store.stageNew"):
            self.assertIn(seam, ffi)
        report = (ROOT / "scripts/ci/check-android-notification-results.py").read_text(encoding="utf-8")
        self.assertIn('"selfClaimedIdCannotPassThePinnedReplyOrLegacyMirrorBinding"', report)

    def test_migrated_and_reopened_database_exercises_all_pending_queries(self):
        test = (ROOT / "android-app/app/src/androidTest/java/com/vladimir/messenger/data/notification/NotificationArrivalInstrumentedTest.kt").read_text(encoding="utf-8")
        case = test.split("fun migrationFortyOneToFortyTwoPreservesRowsAndIndexesSentRetries()", 1)[1].split("@Test", 1)[0]
        self.assertIn(".addMigrations(AppDatabase.MIGRATION_41_42)", case)
        self.assertIn('DROP INDEX index_messages_outbox_status_order', case)
        self.assertIn("db.version = 41", case)
        self.assertIn("migrated.close()", case)
        for query in ("countPendingMirrorOutgoing()", "pendingMirrorOutgoing(50)",
                      'pendingMirrorOutgoingAfter(125L, "pending", 50)'):
            self.assertEqual(2, case.count(query), query)
        report = (ROOT / "scripts/ci/check-android-notification-results.py").read_text(encoding="utf-8")
        self.assertIn('"migrationFortyOneToFortyTwoPreservesRowsAndIndexesSentRetries"', report)

    def test_real_room_cases_are_required_by_the_android_report_gate(self):
        test = TEST.read_text(encoding="utf-8")
        runner = (ROOT / "scripts/ci/run-android-navigation-tests.sh").read_text(encoding="utf-8")
        report = (ROOT / "scripts/ci/check-android-notification-results.py").read_text(encoding="utf-8")
        self.assertIn("MirrorPendingOutgoingInstrumentedTest", runner)
        self.assertIn("MirrorPendingOutgoingInstrumentedTest", report)
        for name in ("queuedQuoteAndPaletteSurviveReopenJsonAndActiveStaging",
                     "personalQueryExcludesGroupsAndTopicsBeforeTheLimit",
                     "durableTombstoneSuppressesPendingFrameAcrossReopen",
                     "pendingPagesPassBlockedRowsAndWrapWithRealRoom",
                     "rowOrGroupChangingAfterSelectionDoesNotProduceAFrame"):
            self.assertIn(f"fun {name}(", test)
            self.assertIn(f'"{name}"', report)


if __name__ == "__main__":
    unittest.main(verbosity=2)
