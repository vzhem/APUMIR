package com.vladimir.messenger.data.repost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepostWireTest {

    @Test
    fun roundTrip() {
        val wire = RepostWire.build("chat1", "msg1", "evt1", 1234L)
        assertEquals("APURPOST1|chat1|msg1|evt1|1234", wire)
        val packet = RepostWire.parse(wire)
        assertEquals("chat1", packet?.chatId)
        assertEquals("msg1", packet?.messageId)
        assertEquals("evt1", packet?.eventId)
        assertEquals(1234L, packet?.atMs)
        assertTrue(RepostWire.isRepostPacket(wire))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(RepostWire.parse("APURPOST1|chat1|msg1"))
        assertNull(RepostWire.parse("APURPOST1|chat1|msg1|evt|soon"))
        assertNull(RepostWire.parse("APURPOST1||msg1|evt|1"))
        assertNull(RepostWire.parse("hello"))
        assertNull(RepostWire.build("cha|t", "msg1", "evt1", 1L))
        assertNull(RepostWire.build("", "msg1", "evt1", 1L))
    }
}
