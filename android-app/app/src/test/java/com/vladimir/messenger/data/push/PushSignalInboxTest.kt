package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushSignalInboxTest {
    private val now = 1_800_000_000_000L
    private val signal = mapOf("v" to "1", "route" to "ab".repeat(32))

    @Test fun aValidSignalOnlyWritesAGenericRecoveryMarker() {
        val inbox = FakeInbox()
        val signalInbox = PushSignalInbox(inbox::enqueueRecovery, nowMs = { now })
        assertEquals(PushSignalInbox.Result.Queued, signalInbox.onSignal(signal, false))
        assertEquals(1, inbox.recoveries)
        // A marker must never carry a route, a peer or a message identifier.
        assertEquals(listOf(now), inbox.times)
    }

    @Test fun deletedMessagesAndGapsUseTheSameMarker() {
        val inbox = FakeInbox()
        val signalInbox = PushSignalInbox(inbox::enqueueRecovery, nowMs = { now })
        assertEquals(PushSignalInbox.Result.Queued, signalInbox.onDeletedMessages())
        assertEquals(1, inbox.recoveries)
    }

    @Test fun rejectedOrIgnoredPayloadsWriteNothing() {
        val inbox = FakeInbox()
        val signalInbox = PushSignalInbox(inbox::enqueueRecovery, nowMs = { now })
        assertEquals(PushSignalInbox.Result.Rejected, signalInbox.onSignal(null, false))
        assertEquals(PushSignalInbox.Result.Rejected, signalInbox.onSignal(signal, hasNotificationBlock = true))
        assertEquals(PushSignalInbox.Result.Rejected, signalInbox.onSignal(mapOf("v" to "2", "route" to "ab".repeat(32)), false))
        assertEquals(PushSignalInbox.Result.Rejected, signalInbox.onSignal(mapOf("v" to "1", "route" to "ab".repeat(32), "text" to "hi"), false))
        assertEquals(0, inbox.recoveries)
        assertEquals(0, inbox.calls)
    }

    @Test fun storageRefusalAndBadClocksAreReportedNotSwallowed() {
        val refusing = FakeInbox(result = PushWakeInbox.EnqueueResult.UNAVAILABLE)
        assertEquals(
            PushSignalInbox.Result.Unavailable,
            PushSignalInbox(refusing::enqueueRecovery, nowMs = { now }).onSignal(signal, false),
        )
        var failures = 0
        val throwing = FakeInbox(throws = true)
        assertEquals(
            PushSignalInbox.Result.Unavailable,
            PushSignalInbox(throwing::enqueueRecovery, nowMs = { now }, recordFailure = { failures++ })
                .onDeletedMessages(),
        )
        assertEquals("a storage exception must be counted", 1, failures)
        val badClock = FakeInbox()
        assertEquals(
            PushSignalInbox.Result.Rejected,
            PushSignalInbox(badClock::enqueueRecovery, nowMs = { 0 }).onSignal(signal, false),
        )
        assertEquals(0, badClock.calls)
    }

    @Test fun anExistingMarkerIsReportedAsAlreadyQueued() {
        val inbox = FakeInbox(result = PushWakeInbox.EnqueueResult.QUEUED)
        assertEquals(
            PushSignalInbox.Result.AlreadyQueued,
            PushSignalInbox(inbox::enqueueRecovery, nowMs = { now }).onSignal(signal, false),
        )
    }

    private class FakeInbox(
        private val result: PushWakeInbox.EnqueueResult = PushWakeInbox.EnqueueResult.RECOVERY_QUEUED,
        private val throws: Boolean = false,
    ) {
        var recoveries = 0
        var calls = 0
        val times = mutableListOf<Long>()

        @Suppress("UNUSED_PARAMETER")
        fun enqueueRecovery(nowMs: Long): PushWakeInbox.EnqueueResult {
            calls++
            times += nowMs
            if (throws) throw IllegalStateException("inbox unavailable")
            recoveries++
            return result
        }
    }
}
