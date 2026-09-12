package com.vladimir.messenger.data.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLinkWireTest {

    private val callId = "0123456789abcdef0123456789abcdef"
    private val callerKey = ByteArray(16) { (it * 7).toByte() }

    @Test
    fun controlRoundTrip() {
        val cipher = ByteArray(40) { it.toByte() }
        val bytes = CallLinkWire.buildControl(CallLinkWire.CONTROL_SEQ_CALLEE_BASE + 5, cipher)
        assertEquals(CallLinkWire.TYPE_CONTROL, bytes[0].toInt() and 0xFF)
        assertEquals(1 + 8 + 40, bytes.size)
        val parsed = CallLinkWire.parse(bytes) as CallLinkWire.Packet.Control
        assertEquals(CallLinkWire.CONTROL_SEQ_CALLEE_BASE + 5, parsed.seq)
        assertArrayEquals(cipher, parsed.cipher)
    }

    @Test
    fun controlSignalEncryptedWithLinkKeySurvivesTheWire() {
        val crypto = CallMediaCrypto(CallLinkWire.deriveLinkKey(callerKey))
        val text = CallWire.buildCapabilities(callId, CallWire.LOCAL_CODECS, ack = true)
        val seq = 3L
        val wire = CallLinkWire.buildControl(seq, crypto.encrypt(seq, text.toByteArray(Charsets.UTF_8)))
        val parsed = CallLinkWire.parse(wire) as CallLinkWire.Packet.Control
        val plain = CallMediaCrypto(CallLinkWire.deriveLinkKey(callerKey)).decrypt(parsed.seq, parsed.cipher)!!
        val signal = CallWire.parse(String(plain, Charsets.UTF_8)) as CallWire.Packet.Capabilities
        assertTrue(signal.ack)
        assertEquals(CallWire.LOCAL_CODECS, signal.codecs)
        // Чужой ключ моста (другой звонок) не расшифрует.
        assertNull(CallMediaCrypto(CallLinkWire.deriveLinkKey(ByteArray(16))).decrypt(parsed.seq, parsed.cipher))
    }

    @Test
    fun mediaBatchRoundTrip() {
        val frames = (0 until 4).map { i ->
            CallLinkWire.MediaFrame(1_000L + i, ByteArray(164 + 16) { (i + it).toByte() })
        }
        val bytes = CallLinkWire.buildMedia(CallWire.CODEC_ADPCM_16K, frames)!!
        assertEquals(CallLinkWire.TYPE_MEDIA, bytes[0].toInt() and 0xFF)
        assertEquals(3 + 4 * (6 + 180), bytes.size)
        val parsed = CallLinkWire.parse(bytes) as CallLinkWire.Packet.Media
        assertEquals(CallWire.CODEC_ADPCM_16K, parsed.codec)
        assertEquals(4, parsed.frames.size)
        frames.forEachIndexed { i, f ->
            assertEquals(f.seq, parsed.frames[i].seq)
            assertArrayEquals(f.cipher, parsed.frames[i].cipher)
        }
    }

    @Test
    fun mediaSeqUsesFullU32Range() {
        val seq = 0xFFFF_FFF0L
        val bytes = CallLinkWire.buildMedia(CallWire.CODEC_PCM_16K, listOf(CallLinkWire.MediaFrame(seq, byteArrayOf(1))))!!
        val parsed = CallLinkWire.parse(bytes) as CallLinkWire.Packet.Media
        assertEquals(seq, parsed.frames[0].seq)
    }

    @Test
    fun oversizedMediaBatchReturnsNull() {
        // 8 PCM-кадров по 656 байт = 5.2 КиБ влезают; 8 кадров по 4 КиБ — нет.
        val small = (0 until 8).map { CallLinkWire.MediaFrame(it.toLong(), ByteArray(656)) }
        assertTrue(CallLinkWire.buildMedia(CallWire.CODEC_PCM_16K, small) != null)
        val big = (0 until 8).map { CallLinkWire.MediaFrame(it.toLong(), ByteArray(CallLinkWire.MAX_FRAME_BYTES)) }
        assertNull(CallLinkWire.buildMedia(CallWire.CODEC_PCM_16K, big))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(CallLinkWire.parse(null))
        assertNull(CallLinkWire.parse(ByteArray(0)))
        assertNull(CallLinkWire.parse(byteArrayOf(0x7F, 1, 2, 3)))                 // неизвестный тип
        assertNull(CallLinkWire.parse(byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0, 1)))  // control без тела
        assertNull(CallLinkWire.parse(byteArrayOf(0xAC.toByte(), 3, 0)))           // media с n=0
        assertNull(CallLinkWire.parse(byteArrayOf(0xAC.toByte(), 0, 1, 0, 0, 0, 1, 0, 1, 9))) // codec=0
        // Заявлено 2 кадра, есть один.
        val truncated = CallLinkWire.buildMedia(3, listOf(CallLinkWire.MediaFrame(1, byteArrayOf(9))))!!
        truncated[2] = 2
        assertNull(CallLinkWire.parse(truncated))
        // Хвост после последнего кадра — тоже мусор.
        val ok = CallLinkWire.buildMedia(3, listOf(CallLinkWire.MediaFrame(1, byteArrayOf(9))))!!
        assertNull(CallLinkWire.parse(ok + byteArrayOf(0)))
    }

    @Test
    fun linkKeyIsDerivedNotReused() {
        val link = CallLinkWire.deriveLinkKey(callerKey)
        assertEquals(16, link.size)
        assertFalse(link.contentEquals(callerKey))
        assertArrayEquals(link, CallLinkWire.deriveLinkKey(callerKey.copyOf())) // детерминированно
        assertFalse(link.contentEquals(CallLinkWire.deriveLinkKey(ByteArray(16))))
    }

    @Test
    fun topicsAreOutsideCoreTreeAndPerSide() {
        val a = CallLinkWire.topicFor(callId, "pk_alice")
        val b = CallLinkWire.topicFor(callId, "pk_bob")
        assertTrue(a.startsWith("apucall1/"))
        assertEquals("apucall1/".length + 32, a.length)
        assertTrue(a.substring(9).all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
        assertEquals(a, CallLinkWire.topicFor(callId, "pk_alice"))
        // Другой звонок — другая тема, даже для той же стороны.
        assertNotEquals(a, CallLinkWire.topicFor("fedcba9876543210fedcba9876543210", "pk_alice"))
        assertFalse(a.startsWith("p2pm2/"))
    }

    @Test
    fun calleeControlSeqSpaceNeverMeetsCaller() {
        // Звонящий считает от 0, принимающий от 2^62: за звонок счётчики не пересекутся.
        assertTrue(CallLinkWire.CONTROL_SEQ_CALLEE_BASE > 0)
        assertEquals(1L shl 62, CallLinkWire.CONTROL_SEQ_CALLEE_BASE)
    }
}
