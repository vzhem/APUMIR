package com.vladimir.messenger.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfSeedRetryPolicyTest {
    @Test
    fun `preparation failure is throttled across pump cycles and process restarts`() {
        val now = 1_700_000_000_000L
        val delay = SelfSeedRetryPolicy.RETRY_AFTER_FAILURE_MS
        assertTrue(SelfSeedRetryPolicy.mayAttempt("11.74.354", null, 0L, now))
        assertFalse(SelfSeedRetryPolicy.mayAttempt("11.74.354", "11.74.354", now, now + 5 * 60_000L))
        assertTrue(SelfSeedRetryPolicy.mayAttempt("11.74.354", "11.74.354", now, now + delay))
        assertTrue(SelfSeedRetryPolicy.mayAttempt("11.74.355", "11.74.354", now, now + 1_000L))
        // Manual clock rollback should not lock the installed version indefinitely.
        assertTrue(SelfSeedRetryPolicy.mayAttempt("11.74.354", "11.74.354", now, now - 1_000L))
    }
}
