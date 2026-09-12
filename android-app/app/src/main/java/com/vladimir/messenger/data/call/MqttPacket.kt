package com.vladimir.messenger.data.call

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * Минимальный MQTT 3.1.1 для провода звонка (CallBrokerLink): CONNECT, PUBLISH
 * QoS0, SUBSCRIBE QoS0, PUBACK, PINGREQ/PINGRESP, DISCONNECT и разбор входящего
 * PUBLISH. Ровно столько, сколько нужно, чтобы говорить с публичным брокером
 * без библиотек и без ядра. Чистая JVM — проверяется unit-тестами побайтно.
 */
object MqttPacket {
    const val TYPE_CONNECT = 1
    const val TYPE_CONNACK = 2
    const val TYPE_PUBLISH = 3
    const val TYPE_PUBACK = 4
    const val TYPE_SUBSCRIBE = 8
    const val TYPE_SUBACK = 9
    const val TYPE_PINGREQ = 12
    const val TYPE_PINGRESP = 13
    const val TYPE_DISCONNECT = 14

    /** Приёмник ядра (rumqttc) режет пакеты больше 10 КиБ — оставляем запас на заголовки. */
    const val MAX_PUBLISH_PAYLOAD_BYTES = 9 * 1024

    /** Больше этого от брокера не ждём (mesh-конверты ≤ 64 КиБ); иначе — обрыв провода. */
    const val MAX_INCOMING_BODY_BYTES = 256 * 1024

    const val MAX_REMAINING_LENGTH = 268_435_455

    val PINGREQ: ByteArray = byteArrayOf(0xC0.toByte(), 0x00)
    val PINGRESP: ByteArray = byteArrayOf(0xD0.toByte(), 0x00)
    val DISCONNECT: ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

    class Publish(val topic: String, val packetId: Int, val payload: ByteArray)

    fun connect(clientId: String, keepAliveSec: Int, cleanSession: Boolean = true): ByteArray {
        require(keepAliveSec in 0..0xFFFF) { "bad keep-alive" }
        val body = ByteArrayOutputStream()
        writeString(body, "MQTT")
        body.write(4) // protocol level: 3.1.1
        body.write(if (cleanSession) 0x02 else 0x00)
        body.write(keepAliveSec shr 8)
        body.write(keepAliveSec and 0xFF)
        writeString(body, clientId)
        return packet(TYPE_CONNECT shl 4, body.toByteArray())
    }

    /** PUBLISH QoS0 без retain. @return пакет или null, если тело не влезает в приёмник ядра. */
    fun publish(topic: String, payload: ByteArray): ByteArray? {
        if (payload.size > MAX_PUBLISH_PAYLOAD_BYTES) return null
        val body = ByteArrayOutputStream(2 + topic.length + payload.size)
        writeString(body, topic)
        body.write(payload)
        return packet(TYPE_PUBLISH shl 4, body.toByteArray())
    }

    fun subscribe(packetId: Int, topic: String, qos: Int = 0): ByteArray {
        require(packetId in 1..0xFFFF) { "bad packet id" }
        require(qos in 0..2) { "bad qos" }
        val body = ByteArrayOutputStream()
        body.write(packetId shr 8)
        body.write(packetId and 0xFF)
        writeString(body, topic)
        body.write(qos)
        return packet((TYPE_SUBSCRIBE shl 4) or 0x02, body.toByteArray())
    }

    fun puback(packetId: Int): ByteArray = byteArrayOf(
        (TYPE_PUBACK shl 4).toByte(), 0x02, (packetId shr 8).toByte(), packetId.toByte(),
    )

    fun packetType(firstByte: Int): Int = (firstByte and 0xFF) shr 4

    /** CONNACK: тело из двух байт, второй — код возврата (0 = принято). */
    fun connackAccepted(firstByte: Int, body: ByteArray): Boolean =
        packetType(firstByte) == TYPE_CONNACK && body.size >= 2 && body[1].toInt() == 0

    fun parsePublish(firstByte: Int, body: ByteArray): Publish? {
        if (packetType(firstByte) != TYPE_PUBLISH || body.size < 2) return null
        val qos = (firstByte shr 1) and 0x03
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        var pos = 2 + topicLen
        if (pos > body.size) return null
        val topic = String(body, 2, topicLen, Charsets.UTF_8)
        var packetId = 0
        if (qos > 0) {
            if (pos + 2 > body.size) return null
            packetId = ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
            pos += 2
        }
        return Publish(topic, packetId, body.copyOfRange(pos, body.size))
    }

    fun encodeRemainingLength(length: Int): ByteArray {
        require(length in 0..MAX_REMAINING_LENGTH) { "bad remaining length" }
        val out = ByteArrayOutputStream(4)
        var x = length
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    @Throws(IOException::class)
    fun readRemainingLength(input: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        var count = 0
        while (true) {
            val digit = input.readUnsignedByte()
            value += (digit and 0x7F) * multiplier
            count++
            if (digit and 0x80 == 0) return value
            if (count == 4) throw IOException("malformed remaining length")
            multiplier *= 128
        }
    }

    private fun packet(firstByte: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(1 + 4 + body.size)
        out.write(firstByte)
        out.write(encodeRemainingLength(body.size))
        out.write(body)
        return out.toByteArray()
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "string too long" }
        out.write(bytes.size shr 8)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }
}
