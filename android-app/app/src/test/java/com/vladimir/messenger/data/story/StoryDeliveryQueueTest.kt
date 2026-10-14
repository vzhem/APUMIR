package com.vladimir.messenger.data.story

import org.junit.Assert.*
import org.junit.Test

class StoryDeliveryQueueTest {
    @Test fun textRemainsQueuedWhenSealingOrNetworkIsUnavailable() {
        val queue = StoryDeliveryQueue()
        assertTrue(queue.enqueue("s", "owner", listOf("peer"), 1000, false))
        val first = queue.due(1000).single()
        queue.result(first, false, 1, 1000)
        assertTrue(queue.due(60999).isEmpty())
        assertEquals(0, queue.due(61000).single().nextPart)
    }
    @Test fun restartRestoresPendingPartsAndStableStoryIdentity() {
        val queue = StoryDeliveryQueue()
        queue.enqueue("s", "owner", listOf("p1", "p2"), 1000, false)
        queue.result(queue.due(1000).first(), true, 3, 1000)
        val restored = StoryDeliveryQueue(queue.snapshot())
        assertEquals(queue.snapshot(), restored.snapshot())
        assertEquals(1, restored.snapshot().first { it.peer == "p1" }.nextPart)
        assertEquals(0, restored.snapshot().first { it.peer == "p2" }.nextPart)
    }
    @Test fun revokeReplacesPublishAndStaleResultCannotEraseIt() {
        val queue = StoryDeliveryQueue()
        queue.enqueue("s", "owner", listOf("peer"), 1000, false)
        val stale = queue.due(1000).single()
        assertTrue(queue.enqueue("s", "owner", listOf("peer"), 1000, true))
        queue.result(stale, true, 1, 1000)
        assertTrue(queue.snapshot().single().revoke)
        assertFalse(queue.enqueue("s", "owner", listOf("peer"), 1000, false))
    }
    @Test fun noSilentEvictionOnCapacityAndNoExpiredSend() {
        val queue = StoryDeliveryQueue()
        assertTrue(queue.enqueue("s", "owner", (1..256).map { "p$it" }, 1000, false))
        assertFalse(queue.enqueue("new", "owner", listOf("extra"), 1000, false))
        assertEquals(256, queue.snapshot().size)
        assertEquals(16, queue.due(1000).size)
        assertEquals(256, queue.purge(1000 + StoryDeliveryQueue.TTL_MS))
        assertTrue(queue.snapshot().isEmpty())
    }
    @Test fun successfulWholeWaveRemovesOnlyThatRecipient() {
        val queue = StoryDeliveryQueue()
        queue.enqueue("s", "owner", listOf("p1", "p2"), 1000, false)
        queue.result(queue.due(1000).first(), true, 1, 1000)
        assertEquals("p2", queue.snapshot().single().peer)
    }
    @Test fun malformedLocalMetadataIsRejectedAndBounded() {
        val bad = StoryDeliveryEntry("s", "owner", "peer", 1000, nextPart = -1)
        assertTrue(StoryDeliveryQueue(listOf(bad)).snapshot().isEmpty())
        assertEquals(256, StoryDeliveryQueue((1..1000).map { StoryDeliveryEntry("s", "owner", "p$it", 1000) }).snapshot().size)
    }
    @Test fun fullQueueRevokeCannotLeaveTheOldPublishGenerationActive() {
        val queue = StoryDeliveryQueue()
        queue.enqueue("s", "owner", listOf("p"), 1000, false)
        queue.enqueue("other", "owner", (1..255).map { "p$it" }, 1000, false)
        assertFalse(queue.enqueue("s", "owner", listOf("p", "new-peer"), 1000, true))
        assertFalse(queue.snapshot().any { it.storyId == "s" && !it.revoke })
        assertTrue(queue.snapshot().any { it.storyId == "s" && it.revoke })
    }
    @Test fun previousOwnerJobsCannotStarveCurrentOwnerBeforeBatchLimit() {
        val queue = StoryDeliveryQueue()
        queue.enqueue("old", "previous-owner", (1..20).map { "old-$it" }, 1000, false)
        queue.enqueue("new", "current-owner", listOf("friend"), 1000, false)
        assertEquals("new", queue.due(1000, "current-owner").single().storyId)
        assertEquals(21, queue.snapshot().size) // No silent deletion of old-owner metadata.
    }
}
