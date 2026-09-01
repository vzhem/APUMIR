package com.vladimir.messenger.data.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallWireTest {

    private val callId = "0123456789abcdef0123456789abcdef"
    private val key = ByteArray(16) { it.toByte() }

    @Test
    fun offerRoundTrip() {
        val text = CallWire.buildOffer(
            callId = callId,
            callerName = "Аня | «труба»\n100%",
            tsMs = 1725148800000L,
            lanHost = "192.168.1.42",
            lanPort = 42109,
            mediaKey = key,
        )
        val parsed = CallWire.parse(text)
        assertTrue(parsed is CallWire.Packet.Offer)
        val offer = parsed as CallWire.Packet.Offer
        assertEquals(callId, offer.callId)
        assertEquals("Аня | «труба»\n100%", offer.callerName)
        assertEquals(1725148800000L, offer.tsMs)
        assertEquals("192.168.1.42", offer.lanHost)
        assertEquals(42109, offer.lanPort)
        assertEquals(CallWire.PROTO_TCP1, offer.proto)
        assertArrayEquals(key, CallWire.decodeBytes(offer.mediaKeyB64))
    }

    @Test
    fun offerWithoutLanUsesDashFields() {
        val text = CallWire.buildOffer(callId, "Борис", 1725148800000L, null, 0, key)
        val parsed = CallWire.parse(text) as CallWire.Packet.Offer
        assertNull(parsed.lanHost)
        assertEquals(0, parsed.lanPort)
        // Поля «нет LAN» — именно дефисы, не пустые строки.
        assertTrue(text.contains("|-|-|${CallWire.PROTO_TCP1}|"))
    }

    @Test
    fun acceptRoundTrip() {
        val text = CallWire.buildAccept(callId, "10.0.0.7", 42109, key)
        val parsed = CallWire.parse(text)
        assertTrue(parsed is CallWire.Packet.Accept)
        val accept = parsed as CallWire.Packet.Accept
        assertEquals(callId, accept.callId)
        assertEquals("10.0.0.7", accept.lanHost)
        assertEquals(42109, accept.lanPort)
        assertTrue(CallWire.decodeBytes(accept.mediaKeyB64)!!.size == 16)
    }

    @Test
    fun ringRejectByeRoundTrip() {
        assertEquals(CallWire.Packet.Ring(callId), CallWire.parse(CallWire.buildRing(callId)))
        assertEquals(
            CallWire.Packet.Reject(callId, CallWire.REJECT_BUSY),
            CallWire.parse(CallWire.buildReject(callId, CallWire.REJECT_BUSY)),
        )
        assertEquals(
            CallWire.Packet.Bye(callId, CallWire.BYE_END),
            CallWire.parse(CallWire.buildBye(callId, CallWire.BYE_END)),
        )
    }

    @Test
    fun audioRoundTrip() {
        val payload = ByteArray(640) { (it % 251).toByte() }
        val text = CallWire.buildAudio(callId, 17L, 1725148800123L, payload)
        val parsed = CallWire.parse(text)
        assertTrue(parsed is CallWire.Packet.Audio)
        val audio = parsed as CallWire.Packet.Audio
        assertEquals(callId, audio.callId)
        assertEquals(17L, audio.seq)
        assertEquals(1725148800123L, audio.tsMs)
        assertArrayEquals(payload, audio.payload)
    }

    @Test
    fun strictnessRejectsGarbage() {
        // ОБЯЗАТЕЛЬНО строгий разбор: всё битое — null, пакет молча отбрасывается.
        assertNull(CallWire.parse(null))
        assertNull(CallWire.parse(""))
        assertNull(CallWire.parse("APUCALL1"))
        assertNull(CallWire.parse("APUCALL1|offer|$callId")) // полей мало
        assertNull(CallWire.parse("APUCALL1|offer|not-a-call-id|xx|1|-|-|tcp1|xx"))
        assertNull(CallWire.parse("APUCALL1|ring"))
        assertNull(CallWire.parse("APUCALL1|ring|$callId|extra")) // лишнее поле
        assertNull(CallWire.parse("APUCALL1|reject|$callId|rude")) // чужой reason
        assertNull(CallWire.parse("APUCALL1|au|$callId|-1|1|AA")) // отрицательный seq
        assertNull(CallWire.parse("APUCALL1|au|$callId|1|1|!!!")) // битый base64
        // Чужие префиксы не трогаем.
        assertNull(CallWire.parse("APUGRP1|msg|grp|topic|dGV4dA"))
        assertNull(CallWire.parse("apu-file1|AAAA"))
        assertFalse(CallWire.isCallPacket("обычный текст сообщения"))
    }

    @Test
    fun futureKindIsSilentlySkipped() {
        // Сборки попроще пропускают неизвестные виды, не устраивая ошибок.
        assertNull(CallWire.parse("APUCALL1|video_offer|$callId|whatever"))
    }

    @Test
    fun endpointMixingIsRejected() {
        // Хост без порта и порт без хоста — невалидны, только пара или два дефиса.
        val badHostOnly = "APUCALL1|offer|$callId|QQ|1|192.168.1.5|-|tcp1|${CallWire.encodeBytes(key)}"
        val badPortOnly = "APUCALL1|offer|$callId|QQ|1|-|42109|tcp1|${CallWire.encodeBytes(key)}"
        assertNull(CallWire.parse(badHostOnly))
        assertNull(CallWire.parse(badPortOnly))
    }

    @Test
    fun messageIdsAreDeterministicAndDistinct() {
        assertEquals("c${callId}o3", CallWire.offerMessageId(callId, 3))
        assertEquals("c${callId}r", CallWire.ringMessageId(callId))
        assertEquals("c${callId}a1", CallWire.acceptMessageId(callId, 1))
        assertEquals("c${callId}j", CallWire.rejectMessageId(callId))
        assertEquals("c${callId}b2", CallWire.byeMessageId(callId, 2))
        assertEquals("c${callId}au41", CallWire.audioMessageId(callId, 41L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildingWithBadCallIdFails() {
        CallWire.buildRing("short")
    }

    // ── Бандаж кадров для брокер-пути (любая сеть) ───────────────────────────

    @Test
    fun audioBatchRoundTrip() {
        val frames = (0 until 5).map { i ->
            CallWire.Packet.Audio("call42", 100L + i, 1_700_000_000_000L + i * 20, byteArrayOf(i.toByte(), 9, 8))
        }
        val text = CallWire.buildAudioBatch("call42", frames)
        val parsed = CallWire.parse(text)
        assertTrue(parsed is CallWire.Packet.AudioBatch)
        parsed as CallWire.Packet.AudioBatch
        assertEquals("call42", parsed.callId)
        assertEquals(5, parsed.frames.size)
        frames.forEachIndexed { i, f ->
            assertEquals(f.seq, parsed.frames[i].seq)
            assertEquals(f.tsMs, parsed.frames[i].tsMs)
            assertTrue(f.payload.contentEquals(parsed.frames[i].payload))
        }
    }

    @Test
    fun audioBatchRejectsGarbage() {
        // Полей меньше, чем заявлено n=3.
        assertNull(CallWire.parse("APUCALL1|ab|call42|3|1|2|AA=="))
        // n=0 и слишком большое n запрещены.
        assertNull(CallWire.parse("APUCALL1|ab|call42|0|"))
        assertNull(CallWire.parse("APUCALL1|ab|call42|99|1|2|AA=="))
        // Пустой список строить нельзя.
        try {
            CallWire.buildAudioBatch("call42", emptyList())
            fail("empty batch must throw")
        } catch (_: IllegalArgumentException) {}
    }
}
