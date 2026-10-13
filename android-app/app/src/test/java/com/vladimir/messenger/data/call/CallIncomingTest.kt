package com.vladimir.messenger.data.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallIncomingTest {

    @Test
    fun incomingLaunchWhenCallIdOrAnswer() {
        assertTrue(CallIncoming.isIncomingLaunch("abc", false))
        assertTrue(CallIncoming.isIncomingLaunch(null, true))
        assertTrue(CallIncoming.isIncomingLaunch("", true))
        assertFalse(CallIncoming.isIncomingLaunch(null, false))
        assertFalse(CallIncoming.isIncomingLaunch("", false))
        assertFalse(CallIncoming.isIncomingLaunch("   ", false))
    }

    @Test
    fun wakeLockRefreshBeforeTenMinuteExpiry() {
        assertTrue(CallIncoming.shouldRefreshWakeLock(held = false, elapsedSinceAcquireMs = 0))
        assertFalse(
            CallIncoming.shouldRefreshWakeLock(
                held = true,
                elapsedSinceAcquireMs = CallIncoming.CORE_WAKE_REFRESH_MS - 1,
            ),
        )
        assertTrue(
            CallIncoming.shouldRefreshWakeLock(
                held = true,
                elapsedSinceAcquireMs = CallIncoming.CORE_WAKE_REFRESH_MS,
            ),
        )
    }
}
