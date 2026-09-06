package com.vladimir.messenger.data.heart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт сердечка — рейтинг профиля.
 *
 * Главное свойство: в теле НЕТ того, кто голосует. Голосующий берётся из
 * адреса отправителя, иначе один узел прислал бы пачку голосов от вымышленных
 * имён и накрутил себе рейтинг.
 */
class HeartWireTest {

    @Test
    fun `round trip keeps owner and state`() {
        val added = HeartWire.parse(HeartWire.build("pk_abc", true, 1700)!!)!!
        assertEquals("pk_abc", added.ownerId)
        assertTrue(added.added)
        assertEquals(1700L, added.atMs)

        val removed = HeartWire.parse(HeartWire.build("pk_abc", false, 1701)!!)!!
        assertFalse(removed.added)
    }

    @Test
    fun `envelope carries no voter identity`() {
        // Голосующего подделать нельзя: его просто нет в конверте.
        val envelope = HeartWire.build("pk_owner", true, 1)!!
        assertFalse(envelope.contains("voter"))
        assertEquals(4, envelope.split("|").size)
    }

    @Test
    fun `plain text and other protocols are not hearts`() {
        assertFalse(HeartWire.isHeartPacket("Привет"))
        assertFalse(HeartWire.isHeartPacket("APUREACT1|c|m|x|1|1"))
        assertFalse(HeartWire.isHeartPacket("APUREAD1|c|m|1"))
        assertFalse(HeartWire.isHeartPacket(null))
    }

    @Test
    fun `malformed envelopes are rejected`() {
        assertNull(HeartWire.parse("APUHEART1|owner|2|1"))
        assertNull(HeartWire.parse("APUHEART1|owner|1|notanumber"))
        assertNull(HeartWire.parse("APUHEART1||1|1"))
        assertNull(HeartWire.parse("APUHEART1|owner|1"))
    }

    @Test
    fun `owner with separator is refused`() {
        assertNull(HeartWire.build("pk_a|bc", true, 1))
        assertNull(HeartWire.build("", true, 1))
    }
}
