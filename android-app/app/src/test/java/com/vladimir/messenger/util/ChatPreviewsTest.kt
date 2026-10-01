package com.vladimir.messenger.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPreviewsTest {
    @Test
    fun `delivery acknowledgements are service packets even with whitespace`() {
        assertTrue(ChatPreviews.isServicePacket("ack|c8f779c4-32ba-4bbd-a273-4f8a39cbeb90"))
        assertTrue(ChatPreviews.isServicePacket("  ack|c8f779c4-32ba-4bbd-a273-4f8a39cbeb90  "))
        // Any reserved ACK prefix is blocked from persistence, even if malformed;
        // only a parsed ACK is allowed to update an outgoing delivery status.
        assertTrue(ChatPreviews.isServicePacket("ack|"))
    }

    @Test
    fun `ordinary text mentioning ack is not classified as a service packet`() {
        assertFalse(ChatPreviews.isServicePacket("acknowledgement received"))
        assertFalse(ChatPreviews.isServicePacket("The prefix ack| is reserved"))
        assertFalse(ChatPreviews.isServicePacket("hello"))
    }
}
