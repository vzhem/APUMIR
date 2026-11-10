package com.vladimir.messenger.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationArrivalPolicyTest {
    private val now = 9_000_000L
    private val fresh = 15L * 60_000L

    private fun pending(
        mine: Boolean = false,
        part: Boolean = false,
        relayed: Boolean = false,
        missed: Boolean = false,
        sent: Long = now,
    ) = NotificationArrivalPolicy.groupMessage(mine, part, relayed, missed, sent, now, fresh)

    @Test fun `direct group arrival and missed swarm message alert independent of sender time`() {
        assertTrue(pending(sent = 1))
        assertTrue(pending(relayed = true, missed = true, sent = 1))
    }

    @Test fun `old channel backfill and media parts never create notification work`() {
        assertFalse(pending(relayed = true, sent = now - fresh))
        assertFalse(pending(part = true))
        assertFalse(pending(part = true, relayed = true, missed = true, sent = 1))
        assertFalse(pending(mine = true, relayed = true, missed = true))
    }

    @Test fun `recent relayed channel post retains existing unread freshness boundary`() {
        assertTrue(pending(relayed = true, sent = now - fresh + 1))
        assertFalse(pending(relayed = true, sent = now - fresh))
    }
}
