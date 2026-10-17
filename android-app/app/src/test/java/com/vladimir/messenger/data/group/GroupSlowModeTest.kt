package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupSlowModeTest {
    @Test fun everyIntervalExpiresAndNextMessageStartsANewCountdown() {
        listOf(10, 30, 60, 300, 900, 3600).forEach { seconds ->
            val sent = 1_000_000L
            val duration = seconds * 1000L
            assertEquals(duration, GroupSlowMode.remainingMs(seconds, sent, sent))
            assertEquals(1L, GroupSlowMode.remainingMs(seconds, sent, sent + duration - 1))
            assertEquals(0L, GroupSlowMode.remainingMs(seconds, sent, sent + duration))
            assertEquals(0L, GroupSlowMode.remainingMs(seconds, sent, sent + duration + 500))
            assertEquals(duration, GroupSlowMode.remainingMs(seconds, sent + duration, sent + duration))
        }
    }
    @Test fun disabledMissingAndFutureDatesDoNotLockComposer() {
        assertEquals(0L, GroupSlowMode.remainingMs(0, 1000, 1000))
        assertEquals(0L, GroupSlowMode.remainingMs(30, null, 1000))
        assertEquals(0L, GroupSlowMode.remainingMs(30, Long.MAX_VALUE, 1000))
    }
    @Test fun shorteningOrDisablingAppliesImmediately() {
        assertEquals(280_000L, GroupSlowMode.remainingMs(300, 1000, 21000))
        assertEquals(0L, GroupSlowMode.remainingMs(10, 1000, 21000))
        assertEquals(0L, GroupSlowMode.remainingMs(0, 1000, 21000))
    }
}
