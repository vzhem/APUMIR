package com.vladimir.messenger.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePinPolicyTest {
    @Test
    fun allowsUpToTenPinsAndRejectsTheEleventh() {
        assertEquals(10, MessagePinPolicy.MAX_PINNED_PER_SCOPE)
        assertTrue(MessagePinPolicy.canAddPin(0))
        assertTrue(MessagePinPolicy.canAddPin(9))
        assertFalse(MessagePinPolicy.canAddPin(10))
        assertFalse(MessagePinPolicy.canAddPin(11))
    }

    @Test
    fun pinningAnAlreadyPinnedMessageDoesNotConsumeAnotherSlot() {
        assertTrue(MessagePinPolicy.canAddPin(10, alreadyPinned = true))
    }

    @Test
    fun negativeCountsAreTreatedAsEmpty() {
        assertTrue(MessagePinPolicy.canAddPin(-1))
    }
}
