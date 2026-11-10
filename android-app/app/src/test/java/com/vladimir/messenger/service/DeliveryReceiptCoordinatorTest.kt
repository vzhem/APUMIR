package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryReceiptCoordinatorTest {
    private fun row(
        mine: Boolean = false,
        sender: String = "pk_sender",
        chat: String = "chat",
        status: String = "DELIVERED",
    ) = MessageEntity(id = "m1", chatId = chat, senderId = sender, content = "text", timestamp = 1, isFromMe = mine, status = status)

    @Test fun textAckRequiresInsertedOrMatchingIncomingRow() = runBlocking {
        var lookups = 0
        assertTrue(storedIncomingText("m1", "pk_sender", "chat", true) { lookups++; null })
        assertEquals(0, lookups)
        assertTrue(storedIncomingText("m1", "pk_sender", "chat", false) { row() })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { null })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { row(mine = true) })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { row(sender = "pk_other") })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { row(chat = "other") })
    }

    @Test fun nativeCleanupFollowsCommitAndDuplicateAckRepairsCrashWindow() = runBlocking {
        val released = mutableListOf<String>()
        var lookups = 0
        val changed = applyOutgoingDeliveryAck("m1", { true }, { lookups++; null }, released::add)
        assertTrue(changed)
        assertEquals(0, lookups)
        // Room already has DELIVERED after a process died before native cleanup.
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row(mine = true) }, released::add))
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row(mine = true, status = "READ") }, released::add))
        assertEquals(listOf("m1", "m1", "m1"), released)
    }

    @Test fun uncommittedForeignOrFailedDatabaseNeverReleasesCustody() = runBlocking {
        val released = mutableListOf<String>()
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { null }, released::add))
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row() }, released::add))
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row(mine = true, status = "SENT") }, released::add))
        val error = runCatching {
            applyOutgoingDeliveryAck("m1", { throw IllegalStateException("database") }, { row(mine = true) }, released::add)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(released.isEmpty())
    }
}
