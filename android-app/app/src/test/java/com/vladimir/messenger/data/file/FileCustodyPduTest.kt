package com.vladimir.messenger.data.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileCustodyPduTest {
    private val origin = "pk_" + "ab".repeat(16)
    private val recipient = "pk_" + "cd".repeat(16)
    private val manifest = ByteArray(96) { (it % 251).toByte() }
    private val envelope = ByteArray(220) { (it % 241).toByte() }
    private val binding = ByteArray(140) { (it % 199).toByte() }

    @Test
    fun roundTripPreservesAllFields() {
        val encoded = FileCustodyPdu.encode(origin, recipient, manifest, envelope, binding)
        val decoded = FileCustodyPdu.decode(encoded)
        assertEquals(origin, decoded.originId)
        assertEquals(recipient, decoded.recipientId)
        assertArrayEquals(manifest, decoded.manifest)
        assertArrayEquals(envelope, decoded.keyEnvelope)
        assertArrayEquals(binding, decoded.senderBinding)
        // Пересланное предложение - ровно то, что понимает обычный приёмник.
        val offer = decoded.asOffer()
        assertArrayEquals(manifest, offer.manifest)
        assertArrayEquals(envelope, offer.keyEnvelope)
        assertArrayEquals(binding, offer.senderBinding)
    }

    @Test
    fun fitsIntoOneCustodyOfferPacketBudget() {
        // Самое большое предложение обязано помещаться в лимит сборки фрагментов.
        assertTrue(FileCustodyPdu.maxEncodedBytes() <= FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES * 4)
        val fragments = FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.CUSTODY_OFFER,
            ByteArray(16) { 1 },
            0L,
            FileCustodyPdu.encode(origin, recipient, manifest, envelope, binding),
        )
        assertEquals(1, fragments.size)
        val packet = FileTransferPacketCodec.decode(fragments.single())
        assertEquals(FileTransferPacketCodec.Type.CUSTODY_OFFER, packet.type)
        assertTrue(packet.type.isCustody)
    }

    @Test
    fun encodeRejectsBadParties() {
        assertThrows { FileCustodyPdu.encode(origin, origin, manifest, envelope, binding) }
        assertThrows { FileCustodyPdu.encode("nope", recipient, manifest, envelope, binding) }
        assertThrows { FileCustodyPdu.encode(origin, "pk_zz|zz", manifest, envelope, binding) }
        assertThrows { FileCustodyPdu.encode(origin, recipient, ByteArray(10), envelope, binding) }
        assertThrows { FileCustodyPdu.encode(origin, recipient, manifest, ByteArray(10), binding) }
        assertThrows { FileCustodyPdu.encode(origin, recipient, manifest, envelope, ByteArray(0)) }
    }

    @Test
    fun decodeFailsClosedOnTrailingBytesVersionAndTruncation() {
        val encoded = FileCustodyPdu.encode(origin, recipient, manifest, envelope, binding)
        assertThrows { FileCustodyPdu.decode(encoded + byteArrayOf(0)) }
        assertThrows { FileCustodyPdu.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows { FileCustodyPdu.decode(encoded.copyOf().also { it[0] = 2 }) }
        // Пакет, где отправитель и получатель совпали, не проходит и на разборе.
        // Раскладка: ver(1) | len(1) origin | origin | len(1) recipient | recipient | ...
        val same = encoded.copyOf()
        val recipientStart = 1 + 1 + origin.length + 1
        for (index in origin.indices) same[recipientStart + index] = origin[index].code.toByte()
        assertThrows { FileCustodyPdu.decode(same) }
    }

    @Test
    fun ackStatusAcceptsOnlyKnownSingleByte() {
        assertEquals(FileCustodyPdu.ACK_OK, FileCustodyPdu.ackStatus(byteArrayOf(1)))
        assertEquals(FileCustodyPdu.ACK_REFUSED, FileCustodyPdu.ackStatus(byteArrayOf(2)))
        assertEquals(FileCustodyPdu.ACK_FULL, FileCustodyPdu.ackStatus(byteArrayOf(3)))
        assertEquals(FileCustodyPdu.ACK_RELEASE, FileCustodyPdu.ackStatus(byteArrayOf(4)))
        assertNull(FileCustodyPdu.ackStatus(byteArrayOf(5)))
        assertNull(FileCustodyPdu.ackStatus(byteArrayOf(1, 1)))
        assertNull(FileCustodyPdu.ackStatus(ByteArray(0)))
    }

    @Test
    fun wireTypePeekAndAckIdsAreDeterministic() {
        val transferId = "0123456789abcdef0123456789abcdef"
        val packet = FileTransferPacketCodec.encode(
            FileTransferPacketCodec.Packet(
                FileTransferPacketCodec.Type.CUSTODY_ACK,
                ByteArray(16) { (it + 1).toByte() },
                5L,
                0,
                1,
                byteArrayOf(FileCustodyPdu.ACK_OK),
            ),
        )
        val text = FileTransferWire.encodeEncodedPacket(packet)
        assertEquals(FileTransferPacketCodec.Type.CUSTODY_ACK, FileTransferWire.peekType(text))
        assertNull(FileTransferWire.peekType("hello"))
        assertNull(FileTransferWire.peekType(FileTransferWire.PREFIX + "!!!"))
        val a = FileTransferWire.custodyAckMessageId(transferId, origin, 5L)
        val b = FileTransferWire.custodyAckMessageId(transferId, origin, 5L)
        val other = FileTransferWire.custodyAckMessageId(transferId, recipient, 5L)
        assertEquals(a, b)
        assertTrue(a != other)
        assertTrue(a != FileTransferWire.ackMessageId(transferId, 5L))
        assertTrue(!a.contains('|'))
        // Этап 8: одно и то же подтверждение разным хранителям - разные id,
        // иначе сеть отсеет второе как дубль первого.
        val toFirst = FileTransferWire.custodyAckMessageId(transferId, recipient, 5L, holderTag = origin)
        val toSecond = FileTransferWire.custodyAckMessageId(transferId, recipient, 5L, holderTag = "pk_" + "11".repeat(16))
        assertTrue(toFirst != toSecond && toFirst != other)
        val want1 = FileTransferWire.custodyWantMessageId(transferId, recipient, origin, 7L)
        val want2 = FileTransferWire.custodyWantMessageId(transferId, recipient, origin, 8L)
        assertTrue(want1 != want2 && !want1.contains('|'))
    }

    // ── Этап 8: несколько хранителей, инвентарь ──────────────────────────────

    @Test
    fun holdersColumnRoundTripsAndDeduplicates() {
        assertEquals(emptyList<String>(), FileCustodyPdu.holders(""))
        assertEquals(listOf(origin), FileCustodyPdu.holders(origin))
        assertEquals(listOf(origin, recipient), FileCustodyPdu.holders("$origin,$recipient"))
        assertEquals("$origin,$recipient", FileCustodyPdu.joinHolders(listOf(origin, recipient, origin)))
        assertEquals(listOf(origin, recipient), FileCustodyPdu.holders(FileCustodyPdu.joinHolders(listOf(origin, recipient))))
    }

    @Test
    fun wantRoundTripKeepsSeqAndRanges() {
        val ranges = listOf(0L..5L, 7L..7L, 100L..4_294_967_294L)
        val encoded = FileCustodyPdu.encodeWant(1_700_000_000_123L, ranges)
        val decoded = FileCustodyPdu.decodeWant(encoded)
        assertEquals(1_700_000_000_123L, decoded.seq)
        assertEquals(ranges, decoded.ranges)
        // Пустой инвентарь - «пока ничего не шли».
        val empty = FileCustodyPdu.decodeWant(FileCustodyPdu.encodeWant(3L, emptyList()))
        assertEquals(3L, empty.seq)
        assertTrue(empty.ranges.isEmpty())
        assertTrue(encoded.size <= FileCustodyPdu.maxWantBytes())
    }

    @Test
    fun wantRejectsUnsortedTouchingOrMalformed() {
        assertThrows { FileCustodyPdu.encodeWant(1L, listOf(5L..6L, 0L..1L)) } // не по возрастанию
        assertThrows { FileCustodyPdu.encodeWant(1L, listOf(0L..3L, 4L..6L)) } // касаются - должны быть слиты
        assertThrows { FileCustodyPdu.encodeWant(1L, listOf(0L..3L, 2L..6L)) } // пересекаются
        assertThrows { FileCustodyPdu.encodeWant(-1L, emptyList()) }
        assertThrows { FileCustodyPdu.encodeWant(1L, List(FileCustodyPdu.MAX_WANT_RANGES + 1) { (it * 2L)..(it * 2L) }) }
        val good = FileCustodyPdu.encodeWant(9L, listOf(1L..2L))
        assertThrows { FileCustodyPdu.decodeWant(good + byteArrayOf(0)) } // хвост
        assertThrows { FileCustodyPdu.decodeWant(good.copyOf(good.size - 1)) } // обрезан
        assertThrows { FileCustodyPdu.decodeWant(byteArrayOf(2) + good.copyOfRange(1, good.size)) } // версия
        assertThrows { FileCustodyPdu.decodeWant(ByteArray(0)) }
    }

    @Test
    fun rangesCompressAndExpandSymmetrically() {
        val indices = longArrayOf(0, 1, 2, 5, 9, 10, 11, 12, 40)
        val ranges = FileCustodyPdu.compressRanges(indices)
        assertEquals(listOf(0L..2L, 5L..5L, 9L..12L, 40L..40L), ranges)
        assertArrayEquals(indices, FileCustodyPdu.expandRanges(ranges, chunkCount = 100L, limit = 100))
        // За пределами файла и сверх лимита - отбрасывается.
        assertArrayEquals(longArrayOf(0, 1, 2, 5, 9), FileCustodyPdu.expandRanges(ranges, chunkCount = 10L, limit = 100))
        assertArrayEquals(longArrayOf(0, 1), FileCustodyPdu.expandRanges(ranges, chunkCount = 100L, limit = 2))
        // Слишком много диапазонов - хвост списка отбрасывается, а не падает.
        val sparse = LongArray(200) { it * 2L }
        assertEquals(FileCustodyPdu.MAX_WANT_RANGES, FileCustodyPdu.compressRanges(sparse).size)
        assertTrue(FileCustodyPdu.compressRanges(LongArray(0)).isEmpty())
    }

    @Test
    fun assignmentStripesByChunkIndexAndHonoursReserveAndLiveness() {
        val missing = LongArray(40) { it.toLong() }
        // Два хранителя: полосы по 8 по кругу, по номеру куска.
        val two = FileCustodyPdu.assign(missing, holderCount = 2, active = listOf(0, 1))
        assertArrayEquals(longArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 16, 17, 18, 19, 20, 21, 22, 23, 32, 33, 34, 35, 36, 37, 38, 39), two[0])
        assertArrayEquals(longArrayOf(8, 9, 10, 11, 12, 13, 14, 15, 24, 25, 26, 27, 28, 29, 30, 31), two[1])
        // Стало меньше недостающих - тот же кусок остаётся за тем же хранителем.
        val later = FileCustodyPdu.assign(longArrayOf(9L, 17L, 30L), holderCount = 2, active = listOf(0, 1))
        assertArrayEquals(longArrayOf(17), later[0])
        assertArrayEquals(longArrayOf(9, 30), later[1])
        // Первое окно первого хранителя уже в пути - его не делим.
        val reserved = FileCustodyPdu.assign(missing, holderCount = 2, active = listOf(0, 1), reservedFor = 0, reservedEnd = 20L)
        assertTrue(reserved[0].toList().containsAll((0L until 20L).toList()))
        assertTrue(reserved[1].none { it < 20L })
        assertEquals(40, reserved[0].size + reserved[1].size)
        // Замолчавший хранитель выпадает: всё достаётся живому, пустой список живых = все.
        val oneAlive = FileCustodyPdu.assign(missing, holderCount = 2, active = listOf(1))
        assertEquals(0, oneAlive[0].size)
        assertEquals(40, oneAlive[1].size)
        val nobodyKnown = FileCustodyPdu.assign(missing, holderCount = 3, active = emptyList())
        assertEquals(40, nobodyKnown.sumOf { it.size })
        assertTrue(nobodyKnown.all { it.isNotEmpty() })
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }
}
