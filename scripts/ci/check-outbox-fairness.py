#!/usr/bin/env python3
"""Prevent a permanently unavailable oldest chat from starving other recipients."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
TEST = ROOT / "android-app/app/src/androidTest/java/com/vladimir/messenger/data/repository/OutboxFairnessInstrumentedTest.kt"


class OutboxFairnessContract(unittest.TestCase):
    def text(self, relative):
        return (MAIN / relative).read_text(encoding="utf-8")

    def test_room_uses_ordered_indexed_keyset(self):
        dao = self.text("data/local/dao/MessageDao.kt")
        self.assertIn("getRetryableOutgoingMessagesAfter", dao)
        self.assertIn("timestamp >= :afterTimestamp", dao)
        self.assertIn("timestamp = :afterTimestamp AND id > :afterId", dao)
        self.assertIn("ORDER BY timestamp ASC, id ASC LIMIT :limit", dao)

    def test_scan_advances_after_failure_and_is_bounded(self):
        scan = self.text("data/repository/FairOutboxScan.kt")
        self.assertIn("position = candidate.position", scan)
        self.assertIn("failed[candidate.destination] = candidate.position", scan)
        self.assertIn("resumeAfter = failed.filterKeys", scan)
        self.assertIn("maxAttemptsPerCycle: Int = 4", scan)
        self.assertIn("countAtStart().coerceAtLeast(0)", scan)
        self.assertIn("remaining = remaining!! - 1", scan)
        self.assertIn("remaining!! > 0", scan)
        self.assertIn("position = null", scan)
        self.assertIn("failed.clear()", scan)
        self.assertIn("lock.withLock", scan)
        adapter = self.text("data/repository/QueuedOutboxPump.kt")
        self.assertIn("countAtStart = { messages.countPendingOutgoing() }", adapter)
        self.assertIn("updateOutgoingRetryResult", adapter)
        self.assertIn("getRetryableOutgoingMessagesAfter", adapter)

    def test_peer_retry_is_bounded_and_does_not_log_message_content(self):
        repo = self.text("data/repository/ChatRepository.kt")
        dao = self.text("data/local/dao/MessageDao.kt")
        peer = self.text("data/repository/PeerOutboxRetry.kt")
        adapter = self.text("data/repository/RoomPeerOutboxRetry.kt")
        self.assertNotIn("getPendingOutgoingMessages()", repo)
        self.assertNotIn("content=${msg.content.take", repo)
        self.assertIn("roomPeerOutboxRetry(", repo)
        self.assertIn("getRetryableOutgoingForPeer(peer, limit)", adapter)
        self.assertIn("getRetryableOutgoingForPeerAfter(peer, after.timestamp, after.id, limit)", adapter)
        self.assertIn("getUnconfirmedOutgoingMessagesBefore(chat.id, peer, before.timestamp, before.id, limit)", adapter)
        self.assertIn("state.pending = OutboxPosition(row.timestamp, row.id)", peer)
        self.assertIn("state.recent = OutboxPosition(row.timestamp, row.id)", peer)
        self.assertIn("loadCurrent = messages::getMessageById", adapter)
        self.assertIn("OutboxRecipient.forMessage(current, contact) != peerId", peer)
        self.assertIn("QueuedOutboxPump(messageDao, chatDao, automaticOutboxSender::send)", repo)
        self.assertIn("roomPeerOutboxRetry(messageDao, chatDao, automaticOutboxSender::send)", repo)
        self.assertIn("automaticOutboxSender::send", self.text("service/ChatOutboxPump.kt"))
        self.assertIn("ChatClearRegistry.isCleared(context, peer, id)", self.text("data/repository/RoomAutomaticOutboxSender.kt"))
        self.assertIn("c.contactId = :peerId", dao)
        self.assertIn("m.timestamp ASC, m.id ASC LIMIT :limit", dao)
        self.assertIn("PENDING_LIMIT = 20", peer)
        self.assertIn("TOTAL_LIMIT = 50", peer)
        self.assertIn("catch (cancelled: CancellationException)", peer)
        self.assertIn("queueAfterInitialSendFailure(messageId)", repo)
        self.assertIn("status IN ('PENDING', 'QUEUED_OFFLINE')", dao)

    def test_clear_lookup_requires_identity_even_before_the_first_database(self):
        registry = self.text("data/chatclear/ChatClearRegistry.kt")
        lookup = self.text("data/chatclear/ChatClearLookup.kt")
        self.assertNotIn(".exists()", registry)
        self.assertIn("store(context).contains(owner, currentPeer, id)", registry)
        self.assertIn("check(identity.isNotBlank())", lookup)
        runner = (ROOT / "scripts/ci/run-android-navigation-tests.sh").read_text()
        report = (ROOT / "scripts/ci/check-android-notification-results.py").read_text()
        self.assertIn("ChatClearTombstonesInstrumentedTest", runner)
        for case in ("firstLookupRequiresIdentityAndUsesRealStorageFromAnAbsentFile",
                     "lookupWaitsForAConcurrentFirstTombstoneTransaction"):
            self.assertIn(f'"{case}"', report)

    def test_unfinished_scan_does_not_wait_for_failure_backoff(self):
        cadence = self.text("service/DeliveryCadence.kt")
        pump = self.text("service/ChatOutboxPump.kt")
        self.assertIn("if (moreToScan)", cadence)
        self.assertIn("moreToScan = progress.moreToScan", pump)
        self.assertIn("nextFailures(failures, sent, moreToScan)", pump)

    def test_real_room_acceptance_must_run(self):
        self.assertTrue(TEST.is_file())
        test = TEST.read_text(encoding="utf-8")
        runner = (ROOT / "scripts/ci/run-android-navigation-tests.sh").read_text()
        report = (ROOT / "scripts/ci/check-android-notification-results.py").read_text()
        self.assertIn("OutboxFairnessInstrumentedTest", runner)
        self.assertIn("OutboxFairnessInstrumentedTest", report)
        for name in ("actualRoomQueueReachesLaterRecipientPastManyBlockedRows", "aBadOldestMessageDoesNotHideLaterMessagesInTheSameRoomChat", "growingRoomQueueEndsScanAndRetriesBlockedChat", "discoveryQueryIsContactScopedAndBoundedAcrossChats", "initialFailureCannotUndoConcurrentSuccessOrAck", "roomKeysetPaginationKeepsAllEqualTimestampIds", "unconfirmedPersonalTextRetriesSameIdAndStopsAfterRoomAck", "unconfirmedPumpSkipsGroupsDeferredMediaAndServicesAndRespectsMirror", "roomReconciliationRepairsCommittedAckAfterRestartWithoutTouchingOtherRows", "ackDuringLateNativeSendReconcilesTheNewlyRetainedCopy", "queuedPageRereadsAcknowledgedDeletedAndEditedRows", "queuedRetriesKeepSavedRecipientAndParseLegacyLinksWithoutUnsafeFallback", "discoveryRoomQueryAndRetryRespectSavedRecipientsAndLiveRows", "unconfirmedRoomRetriesLegacyRecipientInsteadOfChangedChat", "durableClearTombstonesSuppressAllAutomaticRetriesWithoutBlockingOtherRows", "discoveryRotatesPastClearedRoomWindowsWithoutSendingThem", "failedClearPreflightSkipsOnlyThatRowAndNeverCountsAsNetwork", "cancelledClearPreflightPropagatesAndEachRetryCanResume"):
            self.assertIn(f"fun {name}(", test)
            self.assertIn(f'"{name}"', report)


if __name__ == "__main__":
    unittest.main(verbosity=2)
