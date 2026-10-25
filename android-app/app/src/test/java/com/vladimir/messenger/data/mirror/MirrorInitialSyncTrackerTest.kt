package com.vladimir.messenger.data.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorInitialSyncTrackerTest {

    @Test
    fun beginAfterPairingEntersTransferLifecycleAndCompletes() {
        val tracker = MirrorInitialSyncTracker()

        assertEquals(MirrorInitialSyncPhase.STARTING, tracker.begin(10L).phase)
        assertEquals(MirrorInitialSyncPhase.CONNECTING, tracker.connected(20L).phase)
        assertEquals(MirrorInitialSyncPhase.TRANSFERRING, tracker.transferring(30L).phase)
        tracker.part("messages", hasData = true, nowMs = 40L)
        tracker.part("contacts", hasData = false, nowMs = 50L)
        tracker.part("profile", hasData = true, nowMs = 60L)
        val done = tracker.part("files", hasData = false, nowMs = 70L)

        assertEquals(MirrorInitialSyncPhase.COMPLETE, done.phase)
        assertTrue(done.detail.contains("завершена"))
    }

    @Test
    fun emptySourceIsVisibleAsNoDataInsteadOfConnected() {
        val tracker = MirrorInitialSyncTracker()
        tracker.begin(1L)
        tracker.connected(2L)
        tracker.transferring(3L)

        tracker.part("messages", false, 4L)
        tracker.part("contacts", false, 5L)
        tracker.part("profile", false, 6L)
        val result = tracker.part("files", false, 7L)

        assertEquals(MirrorInitialSyncPhase.NO_DATA, result.phase)
        assertTrue(result.detail.contains("нет данных"))
    }

    @Test
    fun failureKeepsDiagnosticMessage() {
        val tracker = MirrorInitialSyncTracker()
        tracker.begin(1L)

        val result = tracker.fail("Канал зеркала не запустился", 2L)

        assertEquals(MirrorInitialSyncPhase.ERROR, result.phase)
        assertEquals("Канал зеркала не запустился", result.detail)
    }
}
