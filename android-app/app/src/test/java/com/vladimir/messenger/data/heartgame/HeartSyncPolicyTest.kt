package com.vladimir.messenger.data.heartgame

import com.vladimir.messenger.data.diagnostics.DiagnosticOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartSyncPolicyTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun globalSyncIsRareSixHours() {
        assertEquals(6 * hour, HeartSyncPolicy.SYNC_INTERVAL_MS)
        assertTrue(HeartSyncPolicy.isDue(0L, 1_000L))
        assertFalse(HeartSyncPolicy.isDue(1_000L, 1_000L + 6 * hour - 1))
        assertTrue(HeartSyncPolicy.isDue(1_000L, 1_000L + 6 * hour))
    }

    @Test
    fun clockMovedBackwardsCountsAsDue() {
        assertTrue(HeartSyncPolicy.isDue(10 * hour, 1 * hour))
    }

    @Test
    fun phoneMustBeFree() {
        assertTrue(HeartSyncPolicy.isPhoneFree(powerSaveOn = false, busyInFlight = 0))
        assertFalse(HeartSyncPolicy.isPhoneFree(powerSaveOn = true, busyInFlight = 0))
        assertFalse(HeartSyncPolicy.isPhoneFree(powerSaveOn = false, busyInFlight = 1))
    }

    @Test
    fun onlyHeavyOperationsCountAsBusy() {
        val busy = HeartSyncPolicy.busyInFlight(
            mapOf(
                DiagnosticOperation.FILE_PUMP to 1,
                DiagnosticOperation.CORE_EVENTS to 5,
                DiagnosticOperation.STORY_PUMP to 2,
            ),
        )
        assertEquals(3, busy)
        assertEquals(0, HeartSyncPolicy.busyInFlight(mapOf(DiagnosticOperation.CORE_EVENTS to 9)))
    }

    @Test
    fun newcomerRespectsGapAndPerPeerInterval() {
        val now = 100 * hour
        // Всё свежо: антиспам-зазор ещё не прошёл.
        assertFalse(HeartSyncPolicy.newcomerAllowed(0L, now - 60_000, now))
        // Зазор прошёл, но этому контакту уже слали недавно.
        assertFalse(HeartSyncPolicy.newcomerAllowed(now - hour, 0L, now))
        // Зазор прошёл и контакту давно не слали.
        assertTrue(HeartSyncPolicy.newcomerAllowed(now - 7 * hour, 0L, now))
    }
}
