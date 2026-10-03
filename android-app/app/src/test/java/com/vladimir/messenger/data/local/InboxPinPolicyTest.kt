package com.vladimir.messenger.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxPinPolicyTest {
    @Test
    fun allowsPinsUntilTheSharedTenConversationLimit() {
        for (count in 0 until InboxPinPolicy.MAX_PINNED) {
            assertTrue("count=$count", InboxPinPolicy.canAddPin(count))
        }
        assertFalse(InboxPinPolicy.canAddPin(InboxPinPolicy.MAX_PINNED))
        assertFalse(InboxPinPolicy.canAddPin(InboxPinPolicy.MAX_PINNED + 1))
    }
}
