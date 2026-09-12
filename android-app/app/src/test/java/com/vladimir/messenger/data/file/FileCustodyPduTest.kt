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
        assertNull(FileCustodyPdu.ackStatus(byteArrayOf(4)))
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
