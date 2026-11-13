package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSignalPolicyTest {
    private val signal = mapOf("v" to "1")

    @Test fun onlyTheExactV1DataOnlyShapeMayAskForAMailboxCheck() {
        assertEquals(PushSignalPolicy.Decision.LookInMailbox, PushSignalPolicy.inspect(signal, false))
    }

    @Test fun providerRenderedNotificationIsRejectedEvenWithValidData() {
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(signal, true))
        assertTrue(PushSignalPolicy.carriesForbiddenBlock(setOf("notification")))
        assertTrue(PushSignalPolicy.carriesForbiddenBlock(setOf("Android.Notification")))
        assertFalse(PushSignalPolicy.carriesForbiddenBlock(setOf("data", "v")))
    }

    @Test fun unknownVersionIsIgnoredButExtraOrMissingFieldsAreRejected() {
        assertEquals(PushSignalPolicy.Decision.Ignore, PushSignalPolicy.inspect(mapOf("v" to "2"), false))
        // A future field must not silently change meaning on an old client.
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(mapOf("v" to "1", "t" to "x"), false))
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(emptyMap(), false))
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(null, false))
    }

    @Test fun signalCarriesNoMessageTextSenderChatOrUnreadCount() {
        // The only accepted key set is a version marker. Anything that could
        // look like content is rejected, so a signal cannot be shown as a
        // message or used to fake an unread badge.
        val attempts = listOf(
            mapOf("v" to "1", "text" to "hello"),
            mapOf("v" to "1", "sender" to "pk_" + "1".repeat(32)),
            mapOf("v" to "1", "chat" to "c1"),
            mapOf("v" to "1", "unread" to "3"),
            mapOf("v" to "1", "route" to "ab".repeat(32)),
            mapOf("v" to "1", "pin" to "cd".repeat(16)),
        )
        for (data in attempts) {
            assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(data, false))
        }
    }

    @Test fun aProviderGapOnlyRequiresAFullCheck() {
        assertEquals(PushSignalPolicy.Decision.CheckMailboxAfterGap, PushSignalPolicy.afterGap())
    }
}
