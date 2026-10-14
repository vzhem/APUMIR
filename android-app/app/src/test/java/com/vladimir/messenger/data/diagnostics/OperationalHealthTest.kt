package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class OperationalHealthTest {
    @Test fun persistenceAttemptsAndAckUseUptimeAndDoNotExportIds() {
        var now = 100L
        val tracker = OperationalHealthTracker { now }
        tracker.persisted("private-message-id")
        tracker.persisted("private-message-id")
        tracker.attempt("private-message-id"); tracker.transportResult("private-message-id", false)
        now = 300
        tracker.attempt("private-message-id"); tracker.transportResult("private-message-id", true)
        now = 600
        tracker.ack("private-message-id")
        val snapshot = tracker.snapshot()
        assertEquals(1L, snapshot.counters[OperationalCounter.MESSAGE_PERSISTED])
        assertEquals(1L, snapshot.counters[OperationalCounter.MESSAGE_RETRIES])
        assertEquals(500L, snapshot.timings[OperationalTiming.MESSAGE_ACK_UPTIME]!!.maxMs)
        assertEquals(200L, snapshot.timings[OperationalTiming.MESSAGE_FIRST_TRANSPORT_UPTIME]!!.maxMs)
        assertEquals(0, snapshot.pendingMessages)
        assertFalse(snapshot.render().contains("private-message-id"))
    }
    @Test fun restartAckDoesNotInventALatencySample() {
        val tracker = OperationalHealthTracker { 0L }
        tracker.ack("old-id")
        assertEquals(1L, tracker.snapshot().counters[OperationalCounter.MESSAGE_ACK_UNTRACKED])
        assertFalse(tracker.snapshot().timings.containsKey(OperationalTiming.MESSAGE_ACK_UPTIME))
    }
    @Test fun correlationIsBoundedAndDoesNotExportPeerRoutes() {
        val tracker = OperationalHealthTracker { 10L }
        repeat(1000) { tracker.persisted("id-$it"); tracker.routeResult("secret-peer-$it", TransferRoute.LAN, true) }
        assertEquals(256, tracker.snapshot().pendingMessages)
        assertEquals(744L, tracker.snapshot().counters[OperationalCounter.CORRELATION_EVICTED])
        assertFalse(tracker.snapshot().render().contains("secret-peer"))
    }
    @Test fun onlySuccessfulRouteChangesAreCounted() {
        val tracker = OperationalHealthTracker { 0L }
        tracker.routeResult("peer", TransferRoute.LAN, true)
        tracker.routeResult("peer", TransferRoute.UDP, false)
        tracker.routeResult("peer", TransferRoute.LAN, true)
        tracker.routeResult("peer", TransferRoute.QUIC, true)
        assertEquals(1L, tracker.snapshot().counters[OperationalCounter.FILE_ROUTE_CHANGES])
        assertEquals(1L to 1L, tracker.snapshot().routeResults[TransferRoute.UDP])
    }
    @Test fun percentileBucketsAreBoundedAndOpenEndedIsUnknown() {
        val latency = BoundedLatency()
        repeat(95) { latency.add(250) }; repeat(5) { latency.add(9000) }
        assertEquals(250L, latency.snapshot().p95UpperMs)
        val huge = BoundedLatency(); huge.add(4_000_000)
        assertNull(huge.snapshot().p95UpperMs)
        assertEquals(4_000_000L, huge.snapshot().maxMs)
    }
    @Test fun foregroundAndResumeMeasurementsAreIdempotent() {
        var now = 0L
        val tracker = OperationalHealthTracker { now }
        tracker.coreStarting(); tracker.visible(true); tracker.visible(true)
        now = 250
        tracker.coreReady(); tracker.coreReady()
        now = 300
        tracker.visible(false); tracker.visible(false)
        now = 500
        val snapshot = tracker.snapshot()
        assertEquals(300L, snapshot.foregroundUptimeMs)
        assertEquals(200L, snapshot.backgroundUptimeMs)
        assertEquals(1L, snapshot.timings[OperationalTiming.CORE_READY_UPTIME]!!.count)
        assertEquals(250L, snapshot.timings[OperationalTiming.RESUME_READY_UPTIME]!!.maxMs)
    }
    @Test fun goodputUsesConfirmedPayloadAndDoesNotInventZeroDurationSpeed() {
        val tracker = OperationalHealthTracker { 0L }
        tracker.fileComplete(true, 10000, 2000)
        assertEquals(5000L, tracker.snapshot().lastFileGoodputInBps)
        tracker.fileComplete(false, 10000, 0)
        assertNull(tracker.snapshot().lastFileGoodputOutBps)
        assertEquals(10000L, tracker.snapshot().counters[OperationalCounter.FILE_CONFIRMED_BYTES_OUT])
    }
    @Test fun wakelockOverlapsAndTimeoutAreNotDoubleCounted() {
        val wake = WakeHoldTracker()
        wake.acquire(0, 100)
        wake.acquire(50, 100)
        assertEquals(150L, wake.snapshot(1000).second)
        wake.release(1000)
        assertEquals(2L to 150L, wake.snapshot(2000))
        wake.acquire(2000, 100); wake.release(2010)
        assertEquals(160L, wake.snapshot(3000).second)
    }
    @Test fun cleanupWindowDoesNotConfuseRamAckAndSqlDeletes() {
        val window = RelayCleanupWindow.parse(listOf(
            "MESH durable: purged 12 expired relay record(s) (+2 quarantined) at startup",
            "MESH gossip: purged 3 expired durable relay(s) (+1 quarantined)",
            "MESH gossip: durable purge failed: secret reason",
            "Relay-очередь: своих=0 снято-по-ACK=8",
            "Relay-очередь: своих=0 снято-по-ACK=10",
        ))
        assertEquals(15L, window.expiredRemoved)
        assertEquals(3L, window.quarantineRemoved)
        assertEquals(1L, window.failures)
        assertEquals(10L, window.latestRamAckRemoved)
        assertFalse(window.render().contains("secret reason"))
        assertEquals(window, RelayCleanupWindow.parse(listOf(
            "MESH durable: purged 12 expired relay record(s) (+2 quarantined) at startup",
            "MESH gossip: purged 3 expired durable relay(s) (+1 quarantined)",
            "MESH gossip: durable purge failed: other reason",
            "Relay-очередь: снято-по-ACK=10",
        )))
    }
}
