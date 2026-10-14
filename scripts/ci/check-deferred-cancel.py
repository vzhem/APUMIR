#!/usr/bin/env python3
"""Source wiring checks; not a substitute for JVM/device or two-phone tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"


def source(path):
    return (BASE / path).read_text()


class DeferredCancelWiringTest(unittest.TestCase):
    def test_cancel_remains_available_and_does_not_claim_success(self):
        ui = source("ui/components/SendOptions.kt")
        self.assertIn("if (!item.cancelRequested)", ui)
        self.assertNotIn("if (!item.started)", ui)
        self.assertIn("send_cancel_requested", ui)
        self.assertIn("send_cancel_best_effort", ui)
        for lang in ("values", "values-en"):
            xml = (ROOT / f"android-app/app/src/main/res/{lang}/strings.xml").read_text()
            self.assertNotIn('name="send_predelivery_no_cancel"', xml)
            self.assertIn('name="send_cancel_best_effort"', xml)

    def test_request_is_persisted_before_worker_cancellation(self):
        worker = source("worker/ScheduledSendWorker.kt")
        block = worker[worker.index("fun cancel(context:"):]
        self.assertLess(block.index("ScheduledSendStore.requestCancel"), block.index("cancelUniqueWork"))
        self.assertIn("enqueueCancellation(context, id)", block)
        self.assertIn('"scheduled_cancel_$id"', block)
        store = source("data/schedule/ScheduledSendStore.kt")
        self.assertIn('.put("cancelRequested", item.cancelRequested)', store)
        self.assertIn('.put("cancelAcked", JSONArray(item.cancelAcked))', store)
        self.assertIn("fun completeDelivery", store)
        self.assertIn("if (item.cancelRequested) return@synchronized false", store)

    def test_native_handoff_is_not_cancellation_confirmation(self):
        delivery = source("data/schedule/DeferredDelivery.kt")
        block = delivery[delivery.index("private suspend fun pumpCancellation"):delivery.index("/** Ordinary delivery ACKs")]
        self.assertIn("it in latest.cancelAcked", block)
        self.assertNotIn("if (send(", block)
        self.assertIn('"cancel"', block)
        self.assertIn("renewRetention = true", block)
        self.assertIn("if (renewRetention) RustBridge.retryMessage", delivery)
        self.assertIn("cancellations.canUseLegacyGroupDelete", block)
        self.assertIn("DeferredWire.parseCancelAck", delivery)
        self.assertIn("DeferredDeliveryPolicy.acceptsReceipt(cancelAck", delivery)
        self.assertIn("if (item.cancelRequested) return@withLock pumpCancellation(item)", delivery)
        self.assertIn("if (!active(id)) return@withLock false", delivery)

    def test_cleanup_is_durable_and_serialized_with_reveal(self):
        delivery = source("data/schedule/DeferredDelivery.kt")
        self.assertIn("val applied = revealMutex.withLock", delivery)
        block = delivery[delivery.index("val applied = revealMutex.withLock"):delivery.index("if (cancelAck != null)")]
        self.assertLess(block.index("DeferredInboxStore.cancel"), block.index("cancellations.deleteVisible"))
        self.assertLess(block.index("cancellations.deleteVisible"), block.index("DeferredWire.cancelAck"))
        self.assertIn("DeferredInboxStore.cancellation(context, key)", delivery)
        store = source("data/schedule/DeferredInboxStore.kt")
        self.assertIn("done IN (0,2)", store)
        self.assertIn("it.getInt(1) < 2", store)
        self.assertIn("AND done=0", store)

    def test_late_normal_fallback_and_mirrors_are_guarded(self):
        self.assertIn("DeferredInboxStore.isCancelled", source("data/repository/ChatRepository.kt"))
        self.assertIn("ChatRepository(context, chatDao, messageDao, groupDao, referralAttribution)", source("di/AppModule.kt"))
        self.assertIn("isDeferredCancelled(authorId, localId)", source("data/group/GroupRepository.kt"))
        self.assertIn("DeferredInboxStore.isCancelled", source("di/GroupsModule.kt"))
        self.assertIn("DeferredInboxStore.isCancelled", source("service/NotificationHelper.kt"))
        self.assertIn("cancelMessageNotification(messageId)", source("data/schedule/DeferredCancellationRepository.kt"))

    def test_cleanup_never_deletes_whole_topic_or_another_author(self):
        cleanup = source("data/schedule/DeferredCancellationRepository.kt")
        self.assertIn("it.senderId == sender", cleanup)
        self.assertIn("db.withTransaction", cleanup)
        self.assertNotIn("deleteTopic(", cleanup)
        self.assertNotIn("deleteTopicMessages(", cleanup)


if __name__ == "__main__":
    unittest.main(verbosity=2)
