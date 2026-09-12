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
            CallWire.Packet.Audio(callId, 100L + i, 1_700_000_000_000L + i * 20, byteArrayOf(i.toByte(), 9, 8))
        }
        val text = CallWire.buildAudioBatch(callId, frames)
        val parsed = CallWire.parse(text)
        assertTrue(parsed is CallWire.Packet.AudioBatch)
        parsed as CallWire.Packet.AudioBatch
        assertEquals(callId, parsed.callId)
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
        assertNull(CallWire.parse("APUCALL1|ab|$callId|3|1|2|AA=="))
        // n=0 и слишком большое n запрещены.
        assertNull(CallWire.parse("APUCALL1|ab|$callId|0|"))
        assertNull(CallWire.parse("APUCALL1|ab|$callId|99|1|2|AA=="))
        // Пустой список строить нельзя.
        try {
            CallWire.buildAudioBatch(callId, emptyList())
            fail("empty batch must throw")
        } catch (_: IllegalArgumentException) {}
    }

    // ── Сжатый бандаж ac и пакет возможностей cap ─────────────────────────────

    @Test
    fun adpcmBatchCarriesCodec() {
        val frames = listOf(CallWire.Packet.Audio(callId, 7L, 1_700_000_000_000L, ByteArray(164) { 1 }))
        val text = CallWire.buildAudioBatch(callId, frames, CallWire.CODEC_ADPCM_16K)
        assertTrue(text.startsWith("APUCALL1|ac|$callId|1|"))
        val parsed = CallWire.parse(text) as CallWire.Packet.AudioBatch
        assertEquals(CallWire.CODEC_ADPCM_16K, parsed.codec)
        assertEquals(1, parsed.frames.size)
        assertEquals(7L, parsed.frames[0].seq)
        // Обычный ab остаётся PCM — старые сборки его так и читают.
        val pcm = CallWire.parse(CallWire.buildAudioBatch(callId, frames)) as CallWire.Packet.AudioBatch
        assertEquals(CallWire.CODEC_PCM_16K, pcm.codec)
    }

    @Test(expected = IllegalArgumentException::class)
    fun audioBatchRejectsUnknownCodec() {
        CallWire.buildAudioBatch(
            callId,
            listOf(CallWire.Packet.Audio(callId, 1L, 1_700_000_000_000L, byteArrayOf(1))),
            codec = 42,
        )
    }

    @Test
    fun capabilitiesRoundTrip() {
        val text = CallWire.buildCapabilities(callId, setOf(3, 1))
        assertEquals("APUCALL1|cap|$callId|1,3", text) // кодеки отсортированы
        val parsed = CallWire.parse(text) as CallWire.Packet.Capabilities
        assertEquals(callId, parsed.callId)
        assertEquals(setOf(1, 3), parsed.codecs)
        assertFalse(parsed.ack)
        assertEquals("c${callId}p2", CallWire.capabilitiesMessageId(callId, 2))
        assertTrue(CallWire.LOCAL_CODECS.contains(CallWire.CODEC_PCM_16K))
        assertTrue(CallWire.LOCAL_CODECS.contains(CallWire.CODEC_ADPCM_16K))
    }

    @Test
    fun capabilitiesAckIsOptionalFifthField() {
        val ack = CallWire.buildCapabilities(callId, setOf(1, 3), ack = true)
        assertEquals("APUCALL1|cap|$callId|1,3|ack", ack)
        val parsed = CallWire.parse(ack) as CallWire.Packet.Capabilities
        assertTrue(parsed.ack)
        assertEquals(setOf(1, 3), parsed.codecs)
        // Пятое поле бывает только «ack».
        assertNull(CallWire.parse("APUCALL1|cap|$callId|1,3|nak"))
    }

    // ── Кандидаты UDP и пробы пробивания NAT ──────────────────────────────────

    @Test
    fun candidatesRoundTrip() {
        val endpoints = listOf(
            "203.0.113.9" to 40123,
            "2001:db8:1234:5678:11:2233:4455:6677" to 40123,
            "192.168.1.42" to 40123,
        )
        val text = CallWire.buildCandidates(callId, endpoints)
        assertEquals(
            "APUCALL1|cand|$callId|203.0.113.9/40123,2001:db8:1234:5678:11:2233:4455:6677/40123,192.168.1.42/40123",
            text,
        )
        val parsed = CallWire.parse(text) as CallWire.Packet.Candidates
        assertEquals(callId, parsed.callId)
        assertEquals(endpoints, parsed.endpoints)
        assertEquals("c${callId}n1", CallWire.candidatesMessageId(callId, 1))
    }

    @Test
    fun candidatesRejectGarbage() {
        assertNull(CallWire.parse("APUCALL1|cand|$callId|"))
        assertNull(CallWire.parse("APUCALL1|cand|$callId|example.com/1234"))     // имя, не литерал — DNS не нужен
        assertNull(CallWire.parse("APUCALL1|cand|$callId|256.1.1.1/1234"))
        assertNull(CallWire.parse("APUCALL1|cand|$callId|10.0.0.1/0"))
        assertNull(CallWire.parse("APUCALL1|cand|$callId|10.0.0.1/70000"))
        assertNull(CallWire.parse("APUCALL1|cand|$callId|10.0.0.1"))
        assertNull(CallWire.parse("APUCALL1|cand|$callId|2001:db8::1::2/5"))
        assertNull(CallWire.parse("APUCALL1|cand|$callId|" + (1..7).joinToString(",") { "10.0.0.$it/5000" }))
        try {
            CallWire.buildCandidates(callId, listOf("evil.example" to 80))
            fail("hostname must throw")
        } catch (_: IllegalArgumentException) {}
        try {
            CallWire.buildCandidates(callId, emptyList())
            fail("empty list must throw")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun ipLiteralValidation() {
        assertTrue(CallWire.isValidIpLiteral("0.0.0.0"))
        assertTrue(CallWire.isValidIpLiteral("192.168.1.42"))
        assertTrue(CallWire.isValidIpLiteral("2a00:1450:4001:82a::200e"))
        assertTrue(CallWire.isValidIpLiteral("::1"))
        assertTrue(CallWire.isValidIpLiteral("::ffff:192.0.2.128"))
        assertTrue(CallWire.isValidIpLiteral("1:2:3:4:5:6:7:8"))
        assertFalse(CallWire.isValidIpLiteral("01.2.3.4"))
        assertFalse(CallWire.isValidIpLiteral("1.2.3"))
        assertFalse(CallWire.isValidIpLiteral("1:2:3:4:5:6:7"))
        assertFalse(CallWire.isValidIpLiteral("12345::1"))
        assertFalse(CallWire.isValidIpLiteral(":::1"))
        assertFalse(CallWire.isValidIpLiteral("2001:db8:0:0:0:0:0:2:1"))
        assertFalse(CallWire.isValidIpLiteral("fe80::1%wlan0"))
        assertFalse(CallWire.isValidIpLiteral("example.com"))
        assertFalse(CallWire.isValidIpLiteral(""))
    }

    @Test
    fun probeRoundTrip() {
        assertEquals("APUCALL1|probe|$callId|0", CallWire.buildProbe(callId, seen = false))
        assertEquals("APUCALL1|probe|$callId|1", CallWire.buildProbe(callId, seen = true))
        val seen = CallWire.parse(CallWire.buildProbe(callId, true)) as CallWire.Packet.Probe
        assertTrue(seen.seen)
        assertEquals(callId, seen.callId)
        val unseen = CallWire.parse(CallWire.buildProbe(callId, false)) as CallWire.Packet.Probe
        assertFalse(unseen.seen)
        assertNull(CallWire.parse("APUCALL1|probe|$callId|2"))
        assertNull(CallWire.parse("APUCALL1|probe|$callId"))
    }

    @Test
    fun capabilitiesRejectsGarbage() {
        assertNull(CallWire.parse("APUCALL1|cap|$callId|"))
        assertNull(CallWire.parse("APUCALL1|cap|$callId|1,x"))
        assertNull(CallWire.parse("APUCALL1|cap|$callId|0"))
        assertNull(CallWire.parse("APUCALL1|cap|$callId|1,3|ack|more"))
        assertNull(CallWire.parse("APUCALL1|cap|short|1"))
        try {
            CallWire.buildCapabilities(callId, emptySet())
            fail("empty codec set must throw")
        } catch (_: IllegalArgumentException) {}
    }
}
