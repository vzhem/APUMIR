package com.vladimir.messenger.data.chatclear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClearPumpPolicyTest {
    @Test fun newRequestsAreImmediatelyDueEvenSoonAfterBoot() {
        assertTrue(ChatClearPumpPolicy.due(1, null, 600_000))
        assertFalse(ChatClearPumpPolicy.due(600_000, 1, 600_000))
        assertTrue(ChatClearPumpPolicy.due(600_001, 1, 600_000))
    }

    @Test fun mirrorFailureDoesNotBlockDirectDelivery() = runTest {
        val events = mutableListOf<String>()
        ChatClearPumpPolicy.mirrorThenDeliver(
            mirror = { events.add("mirror"); error("mirror unavailable") },
            recordFailure = { events.add("reported") },
            deliver = { events.add("peer") },
        )
        assertEquals(listOf("mirror", "reported", "peer"), events)
    }

    @Test fun cancellationIsNotSwallowed() = runTest {
        val events = mutableListOf<String>()
        var cancelled = false
        try {
            ChatClearPumpPolicy.mirrorThenDeliver(
                mirror = { throw CancellationException("stop") },
                recordFailure = { events.add("reported") },
                deliver = { events.add("peer") },
            )
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(events.isEmpty())
    }
}
