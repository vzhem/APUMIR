package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWakeSyncTest {
    private val route = "ab".repeat(32)

    private fun claim(route: String?, revision: Long = 1) = PushWakeInbox.Claim(
        route = route, revision = revision, lease = 1, leaseNonce = "cd".repeat(16),
        recoveryNonce = if (route == null) "ef".repeat(16) else null,
    )

    private fun batch(vararg claims: PushWakeInbox.Claim) = PushWakeDrain.SyncBatch(claims.toList())

    @Test fun oneResultIsReturnedPerLeaseAndOnlyAFinishedWindowCompletes() = runBlocking {
        val completed = mutableListOf<String?>()
        val sync = PushWakeSync(
            gate = { true },
            inspect = { PushWakeDrain.Inspection.ALLOW },
            receive = { hint -> completed += hint; PushReceiveWindow.Outcome.COMPLETED },
        )
        val results = sync.sync(batch(claim(route), claim(route)))
        assertEquals(2, results.size)
        assertEquals(listOf(route, route), completed)
        assertTrue(results.all { outcomeOf(it) == PushWakeDrain.SyncOutcome.COMPLETED_WINDOW })
    }

    @Test fun aRetryingWindowKeepsTheLeaseInsteadOfCompletingIt() = runBlocking {
        val sync = PushWakeSync(
            gate = { true },
            inspect = { PushWakeDrain.Inspection.ALLOW },
            receive = { PushReceiveWindow.Outcome.RETRY },
        )
        val results = sync.sync(batch(claim(route)))
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, outcomeOf(results.single()))
    }

    @Test fun aClosedGateOrDroppedHintNeverCompletesAWindow() = runBlocking {
        var inspected = 0
        val closed = PushWakeSync(
            gate = { false },
            inspect = { inspected++; PushWakeDrain.Inspection.ALLOW },
            receive = { error("no window may run while the gate is closed") },
        )
        assertTrue(closed.sync(batch(claim(route), claim(null))).all { outcomeOf(it) == PushWakeDrain.SyncOutcome.RETRY })
        assertEquals(0, inspected)

        val dropped = PushWakeSync(
            gate = { true },
            inspect = { PushWakeDrain.Inspection.DROP },
            receive = { error("a dropped hint must not open a window") },
        )
        // A hint that is no longer trusted is a completed local decision, not a
        // network result; nothing was received and no ACK exists.
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, outcomeOf(dropped.sync(batch(claim(route))).single()))
    }

    @Test fun inspectionFailuresRetryTheLease() = runBlocking {
        val failing = PushWakeSync(
            gate = { true },
            inspect = { throw IllegalStateException("store unavailable") },
            receive = { error("no window after a failed inspection") },
        )
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, outcomeOf(failing.sync(batch(claim(route))).single()))

        val retrying = PushWakeSync(
            gate = { true },
            inspect = { PushWakeDrain.Inspection.RETRY },
            receive = { error("no window while trust is unresolved") },
        )
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, outcomeOf(retrying.sync(batch(claim(route))).single()))
    }

    @Test fun aGenericMarkerIsNotAFullScanAndWithoutOneAlwaysRetries() = runBlocking {
        var scanned = 0
        val noScan = PushWakeSync(
            gate = { true },
            inspect = { error("a marker has no route to inspect") },
            receive = { error("a marker has no route to receive") },
        )
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, outcomeOf(noScan.sync(batch(claim(null))).single()))

        val withScan = PushWakeSync(
            gate = { true },
            inspect = { error("a marker has no route to inspect") },
            receive = { error("a marker has no route to receive") },
            fullScan = { scanned++; true },
        )
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, outcomeOf(withScan.sync(batch(claim(null))).single()))
        assertEquals(1, scanned)

        val failedScan = PushWakeSync(
            gate = { true }, inspect = { PushWakeDrain.Inspection.ALLOW }, receive = { PushReceiveWindow.Outcome.RETRY },
            fullScan = { throw IllegalStateException("index unavailable") },
        )
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, outcomeOf(failedScan.sync(batch(claim(null))).single()))
    }

    @Test fun cancellationPropagatesFromInspectionAndReceive() = runBlocking {
        var cancelled = false
        try {
            PushWakeSync(
                gate = { true },
                inspect = { throw CancellationException("cancelled") },
                receive = { PushReceiveWindow.Outcome.COMPLETED },
            ).sync(batch(claim(route)))
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)

        cancelled = false
        try {
            PushWakeSync(
                gate = { true },
                inspect = { PushWakeDrain.Inspection.ALLOW },
                receive = { throw CancellationException("cancelled") },
            ).sync(batch(claim(route)))
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    /** Unit tests share the module's friend path, so the internal field is readable. */
    private fun outcomeOf(result: PushWakeDrain.SyncResult): PushWakeDrain.SyncOutcome = result.outcome
}
