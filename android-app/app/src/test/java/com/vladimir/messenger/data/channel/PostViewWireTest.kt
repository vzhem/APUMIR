package com.vladimir.messenger.data.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт просмотра поста.
 *
 * Ключевое свойство: читатель в теле НЕ передаётся - его берут из отправителя.
 * Иначе один узел прислал бы пачку просмотров от вымышленных имён.
 */
class PostViewWireTest {

    @Test
    fun `round trip keeps the post and the time`() {
        val packet = PostViewWire.parse(PostViewWire.build("topic-1", 1700)!!)!!
        assertEquals("topic-1", packet.topicId)
        assertEquals(1700L, packet.atMs)
    }

    @Test
    fun `envelope carries no viewer identity`() {
        val envelope = PostViewWire.build("topic-1", 1)!!
        assertEquals(3, envelope.split("|").size)
        assertFalse(envelope.contains("viewer"))
    }

    @Test
    fun `other protocols are not view packets`() {
        assertFalse(PostViewWire.isViewPacket("Привет"))
        assertFalse(PostViewWire.isViewPacket("APUREACT1|c|m|x|1|1"))
        assertFalse(PostViewWire.isViewPacket("APUHEART1|owner|1|1"))
        assertFalse(PostViewWire.isViewPacket(null))
    }

    @Test
    fun `malformed envelopes are rejected`() {
        assertNull(PostViewWire.parse("APUVIEW1|topic"))
        assertNull(PostViewWire.parse("APUVIEW1|topic|notanumber"))
        assertNull(PostViewWire.parse("APUVIEW1||1"))
    }

    @Test
    fun `topic with separator is refused`() {
        assertNull(PostViewWire.build("top|ic", 1))
        assertNull(PostViewWire.build("", 1))
    }

    @Test
    fun `oversized envelope is not accepted`() {
        assertFalse(PostViewWire.isViewPacket("APUVIEW1|" + "a".repeat(600)))
    }

    @Test
    fun `prefix is stable`() {
        // Смена префикса рассинхронизирует телефоны - фиксируем.
        assertTrue(PostViewWire.build("t", 1)!!.startsWith("APUVIEW1|"))
    }
}
