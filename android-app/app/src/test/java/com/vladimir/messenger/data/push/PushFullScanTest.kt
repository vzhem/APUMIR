package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushFullScanTest {
    private val localId = "pk_" + "1".repeat(32)
    private val peerId = "pk_" + "2".repeat(32)
    private fun route(value: Int) = "%064x".format(value)

    @Test fun onlyRoutesMatchingTheLivePinAreVisited() = runBlocking {
        val live = route(0x11)
        val stale = route(0x22)
        val visited = mutableListOf<String>()
        val scan = PushFullScan(
            page = { _, _ -> pageOf(listOf(live, stale), next = null) },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> live },
            receive = { visited += it; PushReceiveWindow.Outcome.COMPLETED },
        )
        assertEquals(PushFullScan.Outcome.COMPLETED, scan.run())
        assertEquals(listOf(live), visited)
    }

    @Test fun everyPageIsVisitedAndTheCursorIsAContinuedHint() = runBlocking {
        val first = route(0x31)
        val second = route(0x32)
        val seenCursors = mutableListOf<String?>()
        val scan = PushFullScan(
            page = { after, _ ->
                seenCursors += after
                if (after == null) pageOf(listOf(first), next = "z") else pageOf(listOf(second), next = null)
            },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            // Nothing matches the live pin, so nothing is fetched.
            derive = { _, _, _ -> null },
            receive = { error("no route matches the live pin") },
        )
        assertEquals(PushFullScan.Outcome.COMPLETED, scan.run())
        assertEquals(listOf(null, "z"), seenCursors)
    }

    @Test fun aRetryingReceiveWindowKeepsTheWholeScanIncomplete() = runBlocking {
        val matching = route(0x41)
        val scan = PushFullScan(
            page = { _, _ -> pageOf(listOf(matching), next = null) },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> matching },
            receive = { PushReceiveWindow.Outcome.RETRY },
        )
        assertEquals(PushFullScan.Outcome.RETRY, scan.run())
    }

    @Test fun missingTrustIsARetryAndNeverASilentSkip() = runBlocking {
        val matching = route(0x42)
        val scan = PushFullScan(
            page = { _, _ -> pageOf(listOf(matching), next = null) },
            find = { hint -> found(hint) },
            trust = { _, _ -> null },
            derive = { _, _, _ -> matching },
            receive = { error("no receive without a trust snapshot") },
        )
        assertEquals(PushFullScan.Outcome.RETRY, scan.run())
    }

    @Test fun anUnavailableIndexIsReportedNotTreatedAsAnEmptyScan() = runBlocking {
        val unavailable = PushFullScan(
            page = { _, _ -> PushRegistrationStore.RecoveryPage.Unavailable },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> null },
            receive = { error("no receive after a failed index read") },
        )
        assertEquals(PushFullScan.Outcome.UNAVAILABLE, unavailable.run())

        val invalid = PushFullScan(
            page = { _, _ -> PushRegistrationStore.RecoveryPage.Invalid },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> null },
            receive = { error("no receive for an invalid page") },
        )
        assertEquals(PushFullScan.Outcome.UNAVAILABLE, invalid.run())
    }

    @Test fun aVanishedRecordIsSkippedButAnUnreadableOneRetries() = runBlocking {
        val skipped = route(0x51)
        val visited = mutableListOf<String>()
        val scan = PushFullScan(
            page = { _, _ -> pageOf(listOf(skipped), next = null) },
            find = { PushRegistrationStore.RouteResult.Missing },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> skipped },
            receive = { visited += it; PushReceiveWindow.Outcome.COMPLETED },
        )
        assertEquals(PushFullScan.Outcome.COMPLETED, scan.run())
        assertTrue(visited.isEmpty())

        val unreadable = PushFullScan(
            page = { _, _ -> pageOf(listOf(route(0x53)), next = null) },
            find = { PushRegistrationStore.RouteResult.Unavailable },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> route(0x53) },
            receive = { error("no receive when the hint cannot be resolved") },
        )
        assertEquals(PushFullScan.Outcome.RETRY, unreadable.run())
    }

    @Test fun aThrowingReceiveWindowIsARetryWithADiagnostic() = runBlocking {
        val matching = route(0x54)
        var failures = 0
        val scan = PushFullScan(
            page = { _, _ -> pageOf(listOf(matching), next = null) },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> matching },
            receive = { throw IllegalStateException("mailbox unavailable") },
            recordFailure = { failures++ },
        )
        assertEquals(PushFullScan.Outcome.RETRY, scan.run())
        assertEquals(1, failures)
    }

    @Test fun thePageBudgetIsNotAVisibleSuccess() = runBlocking {
        val scan = PushFullScan(
            page = { _, _ -> pageOf(emptyList(), next = "again") },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> null },
            receive = { error("no routes") },
        )
        assertEquals(PushFullScan.Outcome.RETRY, scan.run(maxPages = 2))

        val outOfRange = PushFullScan(
            page = { _, _ -> error("no page read for an invalid budget") },
            find = { hint -> found(hint) },
            trust = { _, _ -> snapshot() },
            derive = { _, _, _ -> null },
            receive = { error("no routes") },
        )
        assertEquals(PushFullScan.Outcome.RETRY, outOfRange.run(maxPages = 0))
    }

    @Test fun cancellationPropagates() = runBlocking {
        var cancelled = false
        try {
            PushFullScan(
                page = { _, _ -> pageOf(listOf(route(0x61)), next = null) },
                find = { hint -> found(hint) },
                trust = { _, _ -> throw CancellationException("cancelled") },
                derive = { _, _, _ -> null },
                receive = { PushReceiveWindow.Outcome.COMPLETED },
            ).run()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun pageOf(routes: List<String>, next: String?) =
        PushRegistrationStore.RecoveryPage.Ready(routes, next)

    private fun found(@Suppress("UNUSED_PARAMETER") hint: String) =
        PushRegistrationStore.RouteResult.Found(localId, peerId, 1)

    private fun snapshot() =
        PushDrainTrustGate.Snapshot(localId, peerId, ByteArray(3) { 1 }, ByteArray(3) { 2 }, ByteArray(32) { 3 })
}
