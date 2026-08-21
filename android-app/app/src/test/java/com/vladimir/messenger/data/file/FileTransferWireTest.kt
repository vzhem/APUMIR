package com.vladimir.messenger.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileTransferWireTest {
    private val transferIdHex = "0123456789abcdef0123456789abcdef"
    private val transferId = ByteArray(16) { it.toByte() }

    private fun samplePacket() = FileTransferPacketCodec.Packet(
        FileTransferPacketCodec.Type.CHUNK,
        transferId,
        7,
        0,
        1,
        ByteArray(64) { it.toByte() },
    )

    @Test
    fun wireRoundTripThroughEncodedPacket() {
        val encoded = FileTransferPacketCodec.encode(samplePacket())
        val text = FileTransferWire.encodeEncodedPacket(encoded)
        assertTrue(FileTransferWire.isFilePacketText(text))
        val decoded = FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(text))
        assertEquals(FileTransferPacketCodec.Type.CHUNK, decoded.type)
        assertEquals(7, decoded.itemIndex)
        assertTrue(decoded.transferId.contentEquals(transferId))
    }

    @Test
    fun nonPacketTextIsRejectedCheaply() {
        assertFalse(FileTransferWire.isFilePacketText("hello"))
        assertFalse(FileTransferWire.isFilePacketText("apu-file1|" + "A".repeat(FileTransferWire.MAX_WIRE_CHARS + 1)))
        assertFalse(FileTransferWire.isFilePacketText("apu-file2|AAAA"))
    }

    @Test
    fun corruptBase64FailsClosed() {
        val text = FileTransferWire.encodeEncodedPacket(FileTransferPacketCodec.encode(samplePacket()))
        try {
            FileTransferWire.decodeToEncodedPacket(text.dropLast(2) + "!!")
            fail("Corrupt base64 accepted")
        } catch (expected: Exception) {
            assertTrue(expected is IllegalArgumentException)
        }
    }

    @Test
    fun messageIdsAreDeterministicAndDelimiterSafe() {
        assertEquals(
            FileTransferWire.chunkMessageId(transferIdHex, 3, 0),
            FileTransferWire.chunkMessageId(transferIdHex, 3, 0),
        )
        val ids = listOf(
            FileTransferWire.offerMessageId(transferIdHex, 0),
            FileTransferWire.chunkMessageId(transferIdHex, 65535, 15),
            FileTransferWire.ackMessageId(transferIdHex, 80),
            FileTransferWire.chatPlaceholderMessageId(transferIdHex),
        )
        ids.forEach { id ->
            assertTrue(id.length <= FileTransferWire.MAX_MESSAGE_ID_BYTES)
            assertFalse(id.contains('|'))
        }
        assertEquals(4, ids.toSet().size)
    }

    @Test
    fun invalidTransferIdRejected() {
        try {
            FileTransferWire.offerMessageId("not-hex", 0)
            fail("Invalid transfer id accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
