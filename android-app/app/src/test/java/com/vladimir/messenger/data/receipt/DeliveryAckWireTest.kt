package com.vladimir.messenger.data.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryAckWireTest {
    private val id = "c8f779c4-32ba-4bbd-a273-4f8a39cbeb90"

    @Test
    fun `parses delivery ack and tolerates surrounding whitespace`() {
        assertEquals(id, DeliveryAckWire.messageId("ack|$id"))
        assertEquals(id, DeliveryAckWire.messageId("  ack|$id  \n"))
        assertTrue(DeliveryAckWire.isPacket("  ack|$id  "))
    }

    @Test
    fun `malformed reserved ack is dropped but never updates a message`() {
        assertTrue(DeliveryAckWire.isPacket("ack|"))
        assertNull(DeliveryAckWire.messageId("ack|"))
        assertNull(DeliveryAckWire.messageId("ack|" + "x".repeat(100)))
        assertFalse(DeliveryAckWire.isPacket("acknowledgement: $id"))
    }

    @Test
    fun `history cleanup matcher only accepts canonical uuid ack rows`() {
        assertTrue(DeliveryAckWire.isPersistedUuidAck("ack|$id"))
        assertTrue(DeliveryAckWire.isPersistedUuidAck("  ack|${id.uppercase()}  "))
        assertFalse(DeliveryAckWire.isPersistedUuidAck("ack|not-a-uuid"))
        assertFalse(DeliveryAckWire.isPersistedUuidAck("ack|$id-extra"))
    }

    @Test
    fun `database cleanup pattern is a canonical uuid glob`() {
        val glob = DeliveryAckWire.persistedUuidAckGlobPattern
        assertEquals("ack|", glob.take(4))
        assertEquals(32, glob.count { it == '[' })
        assertEquals(4, glob.windowed(3).count { it == "]-[" })
        assertFalse(glob.contains('?'))
    }
}
