package com.vladimir.messenger.data.swarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SwarmContributionStatsTest {
    @Before
    fun resetBefore() = SwarmContributionStats.resetLocal()

    @After
    fun resetAfter() = SwarmContributionStats.resetLocal()

    @Test
    fun peerSampleStaysBoundedAndEvictsLeastRecentlyUsed() {
        repeat(SwarmContributionStats.MAX_OBSERVED_PEERS) { index ->
            assertTrue(SwarmContributionStats.notePeer("peer-$index"))
        }
        assertEquals(SwarmContributionStats.MAX_OBSERVED_PEERS, SwarmContributionStats.observedPeerCount())
        assertTrue(SwarmContributionStats.observedPeerLimitReached())

        // Touch peer-0 so peer-1, not the refreshed entry, is least recently used.
        assertTrue(SwarmContributionStats.notePeer("peer-0"))
        assertTrue(SwarmContributionStats.notePeer("peer-${SwarmContributionStats.MAX_OBSERVED_PEERS}"))

        val snapshot = SwarmContributionStats.peerCountsSnapshot()
        assertEquals(SwarmContributionStats.MAX_OBSERVED_PEERS, snapshot.size)
        assertEquals(2L, snapshot["peer-0"])
        assertFalse(snapshot.containsKey("peer-1"))
        assertTrue(snapshot.containsKey("peer-${SwarmContributionStats.MAX_OBSERVED_PEERS}"))
    }

    @Test
    fun invalidPeerIdsDoNotConsumeTheBoundedSample() {
        assertFalse(SwarmContributionStats.notePeer(""))
        assertFalse(SwarmContributionStats.notePeer("x".repeat(257)))
        assertEquals(0, SwarmContributionStats.observedPeerCount())
        assertFalse(SwarmContributionStats.observedPeerLimitReached())
    }

    @Test
    fun refusalWindowEnforcesTenAndDoesNotRetainRejectedExcess() {
        val now = 1_000_000L
        repeat(SwarmContributionStats.MAX_REFUSALS_PER_WINDOW) {
            assertTrue(SwarmContributionStats.recordRefusal(now))
        }
        assertFalse(SwarmContributionStats.recordRefusal(now + 100L))
        assertFalse(SwarmContributionStats.refusalRateOk(now + 100L))
        assertEquals(11L, SwarmContributionStats.refusedCount())

        // The denied event was counted, but not added to the recent-window deque.
        // It has not aged out here; only the ten admitted events have.
        assertTrue(SwarmContributionStats.refusalRateOk(now + SwarmContributionStats.REFUSAL_WINDOW_MS + 1L))
        assertTrue(SwarmContributionStats.recordRefusal(now + SwarmContributionStats.REFUSAL_WINDOW_MS + 1L))
        assertEquals(12L, SwarmContributionStats.refusedCount())
    }

    @Test
    fun clockRollbackCannotResetWindowAndNegativeTimeFailsClosed() {
        repeat(SwarmContributionStats.MAX_REFUSALS_PER_WINDOW) {
            assertTrue(SwarmContributionStats.recordRefusal(1_000_000L))
        }
        assertFalse(SwarmContributionStats.recordRefusal(-1L))
        assertFalse(SwarmContributionStats.recordRefusal(5_000L))
        assertFalse(SwarmContributionStats.refusalRateOk(5_000L))
        assertTrue(SwarmContributionStats.refusalRateOk(1_030_001L))
    }

    @Test
    fun resetClearsCountersPeersAndClockWindow() {
        SwarmContributionStats.noteDelivered()
        SwarmContributionStats.notePeer("peer-a")
        SwarmContributionStats.recordRefusal(100L)

        SwarmContributionStats.resetLocal()

        assertEquals(0L, SwarmContributionStats.deliveredCount())
        assertEquals(0L, SwarmContributionStats.refusedCount())
        assertEquals(0, SwarmContributionStats.observedPeerCount())
        assertTrue(SwarmContributionStats.refusalRateOk(0L))
    }

    @Test
    fun concurrentUpdatesRemainConsistentAndBounded() {
        val workers = 8
        val operationsPerWorker = 250
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { worker ->
                pool.submit {
                    check(start.await(2, TimeUnit.SECONDS))
                    repeat(operationsPerWorker) { operation ->
                        SwarmContributionStats.noteDelivered()
                        SwarmContributionStats.notePeer("peer-$worker-$operation")
                        SwarmContributionStats.recordRefusal(1_000_000L)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            val total = (workers * operationsPerWorker).toLong()
            assertEquals(total, SwarmContributionStats.deliveredCount())
            assertEquals(total, SwarmContributionStats.refusedCount())
            assertEquals(SwarmContributionStats.MAX_OBSERVED_PEERS, SwarmContributionStats.observedPeerCount())
            assertFalse(SwarmContributionStats.refusalRateOk(1_000_000L))
        } finally {
            pool.shutdownNow()
        }
    }
}
