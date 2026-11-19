package com.vladimir.messenger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main lane must treat presence exactly as urgently as messages.
 *
 * Why this test exists at all: the Rust bus and this predicate are one policy
 * written in two languages. When they disagree, the weaker half wins — an
 * observation the core ranked first can still be polled last, and by the time
 * it reaches [PresenceRecovery] it is older than the 200 s freshness window and
 * is counted as "stale". The person then sees a contact go offline while that
 * contact is still online, which is the owner's report from 2026-11-18.
 */
class MainLanePriorityTest {
    @Test
    fun messagesAndReceiptsAreInteractive() {
        assertTrue(MainLanePriority.isInteractive("message_received"))
        assertTrue(MainLanePriority.isInteractive("delivery_ack"))
    }

    @Test
    fun presenceIsAsUrgentAsMessages() {
        assertTrue(MainLanePriority.isInteractive("peer_discovered"))
        assertTrue(MainLanePriority.isInteractive("peer_lost"))
    }

    @Test
    fun brokerChatterYieldsToTheBurst() {
        // Names taken from the events that actually flooded the owner's queue.
        assertFalse(MainLanePriority.isInteractive("network_status_changed"))
        assertFalse(MainLanePriority.isInteractive("message_status_changed"))
        assertFalse(MainLanePriority.isInteractive("file_chunk_received"))
        assertFalse(MainLanePriority.isInteractive(""))
    }
}
