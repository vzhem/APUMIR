package com.vladimir.messenger.data.call

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Двоичный провод голосового моста через брокер (CallBrokerLink).
 *
 * По мосту ходят два вида пакетов:
 *
 * - **control** `[0x02][seq u64][шифртекст]` — сигнал звонка (accept, ring, bye,
 *   cap-приветствие) в виде обычной APUCALL1-строки, закрытой AES-GCM ключом
 *   моста (deriveLinkKey от медиа-ключа звонящего, известен обеим сторонам с
 *   offer). Пространство seq делится по ролям: звонящий считает от 0,
 *   принимающий — от 2^62, поэтому nonce под общим ключом никогда не совпадут.
 * - **media** `[0xAC][codec u8][n u8] n × ([seq u32][len u16][шифртекст])` —
 *   пачка голосовых кадров, каждый кадр закрыт медиа-ключом направления
 *   (CallMediaCrypto, nonce = seq) как и на LAN-сокете. Без base64 и без
 *   меток времени: воспроизведение идёт по seq.
 *
 * Тема брокера считается от callId и nodeId стороны-получателя: SHA-256, 32 hex-
 * знака, префикс `apucall1/` — вне дерева `p2pm2/#`, поэтому ядро моста не
 * видит и в свою очередь событий его не кладёт.
 */
object CallLinkWire {

    const val TYPE_CONTROL = 0x02
    const val TYPE_MEDIA = 0xAC

    const val MAX_BATCH_FRAMES = 8
    const val MAX_FRAME_BYTES = CallWire.MAX_AUDIO_PAYLOAD_BYTES
    const val MAX_CONTROL_BYTES = 4 * 1024

    /** seq управляющих пакетов принимающей стороны начинаются отсюда. */
    const val CONTROL_SEQ_CALLEE_BASE = 1L shl 62

    const val TOPIC_PREFIX = "apucall1/"

    private const val CONTROL_HEADER = 1 + 8
    private const val MEDIA_HEADER = 1 + 1 + 1
    private const val FRAME_HEADER = 4 + 2

    class MediaFrame(val seq: Long, val cipher: ByteArray)

    sealed class Packet {
        class Control(val seq: Long, val cipher: ByteArray) : Packet()
        class Media(val codec: Int, val frames: List<MediaFrame>) : Packet()
    }

    fun buildControl(seq: Long, cipher: ByteArray): ByteArray {
        require(seq >= 0) { "Negative control seq" }
        require(cipher.isNotEmpty() && cipher.size <= MAX_CONTROL_BYTES) { "Control out of bounds" }
        val buf = ByteBuffer.allocate(CONTROL_HEADER + cipher.size)
        buf.put(TYPE_CONTROL.toByte())
        buf.putLong(seq)
        buf.put(cipher)
        return buf.array()
    }

    /** @return пакет или null, если пачка не влезает в один PUBLISH брокера. */
    fun buildMedia(codec: Int, frames: List<MediaFrame>): ByteArray? {
        require(codec in 1..255) { "Bad codec" }
        require(frames.isNotEmpty() && frames.size <= MAX_BATCH_FRAMES) { "Batch out of bounds" }
        var size = MEDIA_HEADER
        frames.forEach { f ->
            require(f.seq in 0..0xFFFF_FFFFL) { "Media seq out of u32" }
            require(f.cipher.isNotEmpty() && f.cipher.size <= MAX_FRAME_BYTES) { "Frame out of bounds" }
            size += FRAME_HEADER + f.cipher.size
        }
        if (size > MqttPacket.MAX_PUBLISH_PAYLOAD_BYTES) return null
        val buf = ByteBuffer.allocate(size)
        buf.put(TYPE_MEDIA.toByte())
        buf.put(codec.toByte())
        buf.put(frames.size.toByte())
        frames.forEach { f ->
            buf.putInt(f.seq.toInt())
            buf.putShort(f.cipher.size.toShort())
            buf.put(f.cipher)
        }
        return buf.array()
    }

    /** Строгий разбор: любое отклонение — null, пакет молча отбрасывается. */
    fun parse(bytes: ByteArray?): Packet? {
        if (bytes == null || bytes.isEmpty()) return null
        return when (bytes[0].toInt() and 0xFF) {
            TYPE_CONTROL -> {
                if (bytes.size <= CONTROL_HEADER || bytes.size - CONTROL_HEADER > MAX_CONTROL_BYTES) return null
                val seq = ByteBuffer.wrap(bytes, 1, 8).long
                if (seq < 0) return null
                Packet.Control(seq, bytes.copyOfRange(CONTROL_HEADER, bytes.size))
            }

            TYPE_MEDIA -> {
                if (bytes.size < MEDIA_HEADER) return null
                val codec = bytes[1].toInt() and 0xFF
                val n = bytes[2].toInt() and 0xFF
                if (codec == 0 || n == 0 || n > MAX_BATCH_FRAMES) return null
                val buf = ByteBuffer.wrap(bytes, MEDIA_HEADER, bytes.size - MEDIA_HEADER)
                val frames = ArrayList<MediaFrame>(n)
                repeat(n) {
                    if (buf.remaining() < FRAME_HEADER) return null
                    val seq = buf.int.toLong() and 0xFFFF_FFFFL
                    val len = buf.short.toInt() and 0xFFFF
                    if (len == 0 || len > MAX_FRAME_BYTES || buf.remaining() < len) return null
                    val cipher = ByteArray(len)
                    buf.get(cipher)
                    frames += MediaFrame(seq, cipher)
                }
                if (buf.hasRemaining()) return null
                Packet.Media(codec, frames)
            }

            else -> null
        }
    }

    /** Ключ управляющих пакетов моста: производная от медиа-ключа звонящего (16 байт). */
    fun deriveLinkKey(callerMediaKey: ByteArray): ByteArray {
        require(callerMediaKey.size == 16) { "Caller media key must be 16 bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("APUCALL1-link|".toByteArray(Charsets.US_ASCII))
        digest.update(callerMediaKey)
        return digest.digest().copyOf(16)
    }

    /** Тема, на которую сторона nodeId слушает кадры этого звонка. */
    fun topicFor(callId: String, nodeId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("APUCALL1|topic|$callId|$nodeId".toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val sb = StringBuilder(TOPIC_PREFIX.length + 32)
        sb.append(TOPIC_PREFIX)
        for (i in 0 until 16) {
            val v = hash[i].toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
