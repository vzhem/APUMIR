package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSignalPolicyTest {
    private val signal = mapOf("v" to "1", "route" to "ab".repeat(32))

    @Test fun onlyTheExactV1RouteSignalMayAskForAMailboxCheck() {
        assertEquals(PushSignalPolicy.Decision.LookInMailbox, PushSignalPolicy.inspect(signal, false))
        assertEquals("ab".repeat(32), PushSignalPolicy.route(signal))
    }

    @Test fun providerRenderedNotificationIsRejectedEvenWithValidData() {
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(signal, true))
        assertTrue(PushSignalPolicy.carriesForbiddenBlock(setOf("notification")))
        assertTrue(PushSignalPolicy.carriesForbiddenBlock(setOf("Android.Notification")))
        assertFalse(PushSignalPolicy.carriesForbiddenBlock(setOf("data", "v")))
    }

    @Test fun unknownVersionIsIgnoredButExtraOrMissingFieldsAreRejected() {
        assertEquals(
            PushSignalPolicy.Decision.Ignore,
            PushSignalPolicy.inspect(mapOf("v" to "2", "route" to "ab".repeat(32)), false),
        )
        // A future field must not silently change meaning on an old client.
        assertEquals(
            PushSignalPolicy.Decision.Reject,
            PushSignalPolicy.inspect(signal + ("t" to "x"), false),
        )
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(emptyMap(), false))
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(null, false))
    }

    @Test fun aSignalWithoutAUsableRouteIsRejectedAndNeverTriggersAScan() {
        // The deployed service always names the route it woke. A payload without
        // one is not our signal: treating it as "look in the mailbox" would let
        // anyone holding the token cost this device a bounded scan per post.
        assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(mapOf("v" to "1"), false))
        assertEquals(
            PushSignalPolicy.Decision.Reject,
            PushSignalPolicy.inspect(mapOf("v" to "1", "route" to "not-hex"), false),
        )
        assertEquals(
            PushSignalPolicy.Decision.Reject,
            PushSignalPolicy.inspect(mapOf("v" to "1", "route" to "AB".repeat(32)), false),
        )
        assertEquals(
            PushSignalPolicy.Decision.Reject,
            PushSignalPolicy.inspect(mapOf("v" to "1", "route" to "ab".repeat(31)), false),
        )
        assertNull(PushSignalPolicy.route(mapOf("v" to "1")))
        // A route carrying free text cannot be mistaken for a lookup key.
        assertNull(PushSignalPolicy.route(mapOf("v" to "1", "route" to "hello")))
    }

    @Test fun signalCarriesNoMessageTextSenderChatOrUnreadCount() {
        // The only accepted keys are a version marker and an opaque route.
        // Anything that could look like content is rejected, so a signal cannot
        // be shown as a message or used to fake an unread badge.
        val route = "ab".repeat(32)
        val attempts = listOf(
            mapOf("v" to "1", "route" to route, "text" to "hello"),
            mapOf("v" to "1", "route" to route, "sender" to "pk_" + "1".repeat(32)),
            mapOf("v" to "1", "route" to route, "chat" to "c1"),
            mapOf("v" to "1", "route" to route, "unread" to "3"),
            mapOf("v" to "1", "route" to route, "pin" to "cd".repeat(16)),
        )
        for (data in attempts) {
            assertEquals(PushSignalPolicy.Decision.Reject, PushSignalPolicy.inspect(data, false))
        }
    }

    @Test fun aProviderGapOnlyRequiresAFullCheck() {
        assertEquals(PushSignalPolicy.Decision.CheckMailboxAfterGap, PushSignalPolicy.afterGap())
    }
}
