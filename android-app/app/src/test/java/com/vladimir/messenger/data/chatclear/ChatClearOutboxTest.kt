package com.vladimir.messenger.data.chatclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ChatClearOutboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun persistsAndOnlyExpectedAuthenticatedPeerCanAcknowledge() {
        val file = temporary.root.resolve("outbox")
        val entry = ChatClearOutbox.Entry("friend", "local-chat", ChatClearWire.Batch(UUID.randomUUID(), listOf("a", "b")))
        ChatClearOutbox(file).enqueue(listOf(entry))
        val reloaded = ChatClearOutbox(file)
        assertEquals(listOf(entry), reloaded.pending())
        assertFalse(reloaded.acknowledge(false, "friend", entry.batch.operation))
        assertFalse(reloaded.acknowledge(true, "other", entry.batch.operation))
        assertEquals(listOf(entry), ChatClearOutbox(file).pending())
        assertTrue(reloaded.acknowledge(true, "friend", entry.batch.operation))
        assertTrue(ChatClearOutbox(file).pending().isEmpty())
    }

    @Test fun separateHandlesDoNotLoseConcurrentEnqueues() {
        val file = temporary.root.resolve("outbox")
        val first = ChatClearOutbox(file)
        val second = ChatClearOutbox(file)
        val tasks = (1..40).map { n ->
            Thread {
                val queue = if (n % 2 == 0) first else second
                queue.enqueue(listOf(ChatClearOutbox.Entry("peer", "chat", ChatClearWire.Batch(UUID.randomUUID(), listOf("id-$n")))))
            }
        }
        tasks.forEach { it.start() }
        tasks.forEach { it.join() }
        assertEquals(40, first.pending().size)
    }

    @Test fun corruptQueueFailsClosedRatherThanLosingRequests() {
        val file = temporary.root.resolve("outbox")
        file.writeText("corrupt")
        val outbox = ChatClearOutbox(file)
        val entry = ChatClearOutbox.Entry("peer", "chat", ChatClearWire.Batch(UUID.randomUUID(), listOf("id")))
        try { outbox.enqueue(listOf(entry)); throw AssertionError("corrupt queue accepted") }
        catch (_: IllegalStateException) { /* invalid header is rejected */ }
        assertEquals("corrupt", file.readText())
    }
}
