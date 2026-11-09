package com.vladimir.messenger.data.chatclear

import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatClearAcknowledgementsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun receiptNeverDropsUnfinishedLocalWork() = runTest {
        val outbox = ChatClearOutbox(temporary.root.resolve("outbox"))
        val entry = ChatClearOutbox.Entry("owner", "friend", "chat", ChatClearWire.Batch(UUID.randomUUID(), listOf("old")))
        outbox.enqueue(listOf(entry))
        var applied = 0
        var localReady = false
        val receipts = ChatClearAcknowledgements(outbox, { "owner" }) { candidate ->
            assertEquals(entry, candidate)
            applied++
            localReady
        }
        assertFalse(receipts.receive("friend", false, entry.batch.operation))
        assertFalse(receipts.receive("other", true, entry.batch.operation))
        assertFalse(receipts.receive("friend", true, UUID.randomUUID()))
        assertEquals(0, applied)
        assertFalse(receipts.receive("friend", true, entry.batch.operation))
        assertEquals(listOf(entry), ChatClearOutbox(temporary.root.resolve("outbox")).pending())
        localReady = true
        assertTrue(receipts.receive("friend", true, entry.batch.operation))
        assertEquals(2, applied)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test fun cleanupExceptionLeavesReceiptPendingAcrossRestart() = runTest {
        val file = temporary.root.resolve("outbox")
        val outbox = ChatClearOutbox(file)
        val entry = ChatClearOutbox.Entry("owner", "friend", "chat", ChatClearWire.Batch(UUID.randomUUID(), listOf("old")))
        outbox.enqueue(listOf(entry))
        val receipts = ChatClearAcknowledgements(outbox, { "owner" }) { error("storage unavailable") }
        var failed = false
        try { receipts.receive("friend", true, entry.batch.operation) } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf(entry), ChatClearOutbox(file).pending())
    }

    @Test fun identityChangeDuringCleanupKeepsReceiptPending() = runTest {
        val outbox = ChatClearOutbox(temporary.root.resolve("outbox"))
        val entry = ChatClearOutbox.Entry("owner", "friend", "chat", ChatClearWire.Batch(UUID.randomUUID(), listOf("old")))
        outbox.enqueue(listOf(entry))
        var owner = "owner"
        val receipts = ChatClearAcknowledgements(outbox, { owner }) { owner = "another-owner"; true }
        assertFalse(receipts.receive("friend", true, entry.batch.operation))
        assertEquals(listOf(entry), outbox.pending())
    }
}
