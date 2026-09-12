package com.vladimir.messenger.data.call

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Побайтная проверка MQTT 3.1.1 (спецификация OASIS, раздел 3). */
class MqttPacketTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun connectPacketMatchesSpec() {
        val packet = MqttPacket.connect("apc1", keepAliveSec = 30)
        val expected = bytes(
            0x10, 16,                          // CONNECT, remaining length
            0x00, 0x04, 'M'.code, 'Q'.code, 'T'.code, 'T'.code,
            0x04,                              // protocol level 4 = 3.1.1
            0x02,                              // clean session
            0x00, 0x1E,                        // keep-alive 30 с
            0x00, 0x04, 'a'.code, 'p'.code, 'c'.code, '1'.code,
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun publishQos0MatchesSpec() {
        val packet = MqttPacket.publish("a/b", bytes(0xAC, 1, 2))!!
        val expected = bytes(
            0x30, 8,                           // PUBLISH QoS0, no retain, no dup
            0x00, 0x03, 'a'.code, '/'.code, 'b'.code,
            0xAC, 1, 2,
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun publishRefusesOversizedPayload() {
        assertNull(MqttPacket.publish("t", ByteArray(MqttPacket.MAX_PUBLISH_PAYLOAD_BYTES + 1)))
        assertNotNull(MqttPacket.publish("t", ByteArray(MqttPacket.MAX_PUBLISH_PAYLOAD_BYTES)))
    }

    @Test
    fun subscribePacketMatchesSpec() {
        val packet = MqttPacket.subscribe(1, "x", 0)
        val expected = bytes(
            0x82, 6,                           // SUBSCRIBE с обязательным флагом 0b0010
            0x00, 0x01,                        // packet id
            0x00, 0x01, 'x'.code,
            0x00,                              // QoS 0
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun fixedPacketsMatchSpec() {
        assertArrayEquals(bytes(0xC0, 0x00), MqttPacket.PINGREQ)
        assertArrayEquals(bytes(0xD0, 0x00), MqttPacket.PINGRESP)
        assertArrayEquals(bytes(0xE0, 0x00), MqttPacket.DISCONNECT)
        assertArrayEquals(bytes(0x40, 0x02, 0x01, 0x02), MqttPacket.puback(0x0102))
    }

    @Test
    fun remainingLengthEncodesLikeSpecTable() {
        assertArrayEquals(bytes(0), MqttPacket.encodeRemainingLength(0))
        assertArrayEquals(bytes(127), MqttPacket.encodeRemainingLength(127))
        assertArrayEquals(bytes(0x80, 0x01), MqttPacket.encodeRemainingLength(128))
        assertArrayEquals(bytes(0xFF, 0x7F), MqttPacket.encodeRemainingLength(16_383))
        assertArrayEquals(bytes(0x80, 0x80, 0x01), MqttPacket.encodeRemainingLength(16_384))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0x7F), MqttPacket.encodeRemainingLength(268_435_455))
    }

    @Test
    fun remainingLengthRoundTrips() {
        for (n in listOf(0, 1, 127, 128, 300, 9 * 1024, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455)) {
            val encoded = MqttPacket.encodeRemainingLength(n)
            val decoded = MqttPacket.readRemainingLength(DataInputStream(ByteArrayInputStream(encoded)))
            assertEquals(n, decoded)
        }
    }

    @Test(expected = IOException::class)
    fun remainingLengthRejectsFiveBytes() {
        MqttPacket.readRemainingLength(DataInputStream(ByteArrayInputStream(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x7F))))
    }

    @Test
    fun connackDecoding() {
        assertTrue(MqttPacket.connackAccepted(0x20, bytes(0x00, 0x00)))
        assertFalse(MqttPacket.connackAccepted(0x20, bytes(0x00, 0x05))) // not authorized
        assertFalse(MqttPacket.connackAccepted(0x30, bytes(0x00, 0x00))) // не CONNACK
        assertFalse(MqttPacket.connackAccepted(0x20, bytes(0x00)))       // короткое тело
    }

    @Test
    fun parsePublishQos0AndQos1() {
        val q0 = MqttPacket.parsePublish(0x30, bytes(0x00, 0x01, 't'.code, 9, 8, 7))!!
        assertEquals("t", q0.topic)
        assertEquals(0, q0.packetId)
        assertArrayEquals(bytes(9, 8, 7), q0.payload)

        val q1 = MqttPacket.parsePublish(0x32, bytes(0x00, 0x01, 't'.code, 0x00, 0x2A, 5))!!
        assertEquals(42, q1.packetId)
        assertArrayEquals(bytes(5), q1.payload)

        // Пустой payload допустим, битая длина темы — нет.
        assertArrayEquals(ByteArray(0), MqttPacket.parsePublish(0x30, bytes(0x00, 0x01, 't'.code))!!.payload)
        assertNull(MqttPacket.parsePublish(0x30, bytes(0x00, 0x05, 't'.code)))
        assertNull(MqttPacket.parsePublish(0x32, bytes(0x00, 0x01, 't'.code, 0x00)))
        assertNull(MqttPacket.parsePublish(0x40, bytes(0x00, 0x01, 't'.code)))
    }

    @Test
    fun packetTypeFromFirstByte() {
        assertEquals(MqttPacket.TYPE_CONNACK, MqttPacket.packetType(0x20))
        assertEquals(MqttPacket.TYPE_PUBLISH, MqttPacket.packetType(0x3D))
        assertEquals(MqttPacket.TYPE_SUBACK, MqttPacket.packetType(0x90))
        assertEquals(MqttPacket.TYPE_PINGRESP, MqttPacket.packetType(0xD0))
    }

    @Test
    fun publishWithTwoByteRemainingLength() {
        val payload = ByteArray(200) { 1 }
        val packet = MqttPacket.publish("ab", payload)!!
        // 2 + 2 + 200 = 204 → 0xCC 0x01
        assertEquals(0x30, packet[0].toInt() and 0xFF)
        assertEquals(0xCC, packet[1].toInt() and 0xFF)
        assertEquals(0x01, packet[2].toInt() and 0xFF)
        assertEquals(3 + 204, packet.size)
    }
}
