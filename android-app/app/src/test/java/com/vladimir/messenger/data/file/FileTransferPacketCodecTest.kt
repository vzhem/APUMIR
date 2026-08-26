package com.vladimir.messenger.data.file

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTransferPacketCodecTest {
    private val transferId = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun encryptedChunkFragmentsRoundTripAcrossBoundary() {
        for (size in listOf(1, 4 * 1024, 4 * 1024 + 1, 128 * 1024 + 16, 1024 * 1024 + 16, 4 * 1024 * 1024)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val fragments = FileTransferPacketCodec.fragment(
                FileTransferPacketCodec.Type.CHUNK,
                transferId,
                7,
                payload,
            )
            assertArrayEquals(payload, FileTransferPacketCodec.reassemble(fragments))
        }
    }

    @Test
    fun packetRoundTripPreservesMetadata() {
        val encoded = FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.OFFER,
            transferId,
            0,
            byteArrayOf(4, 5, 6),
        ).single()
        val packet = FileTransferPacketCodec.decode(encoded)
        assertEquals(FileTransferPacketCodec.Type.OFFER, packet.type)
        assertArrayEquals(transferId, packet.transferId)
        assertEquals(0L, packet.itemIndex)
        assertEquals(0, packet.fragmentIndex)
        assertEquals(1, packet.fragmentCount)
        assertArrayEquals(byteArrayOf(4, 5, 6), packet.payload)
    }

    @Test
    fun tamperTruncationTrailingDuplicateAndMixedFragmentsFail() {
        val payload = ByteArray(30_000) { 9 }
        val fragments = FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.CHUNK,
            transferId,
            1,
            payload,
        )
        val tampered = fragments[0].copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        expectFailure { FileTransferPacketCodec.decode(tampered) }
        expectFailure { FileTransferPacketCodec.decode(fragments[0].copyOf(fragments[0].size - 1)) }
        expectFailure { FileTransferPacketCodec.decode(fragments[0] + 0) }
        expectFailure { FileTransferPacketCodec.reassemble(listOf(fragments[0], fragments[0])) }

        val other = FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.CHUNK,
            ByteArray(16) { 3 },
            1,
            payload,
        )
        expectFailure { FileTransferPacketCodec.reassemble(listOf(fragments[0], other[1])) }
    }

    @Test
    fun currentPacketsPreserveIndicesBeyondLegacyUnsignedRange() {
        val highIndex = 5_000_000_000L
        val encoded = FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.CHUNK,
            transferId,
            highIndex,
            byteArrayOf(9),
        ).single()
        assertEquals(FileTransferPacketCodec.VERSION, encoded[0])
        assertEquals(highIndex, FileTransferPacketCodec.decode(encoded).itemIndex)
    }

    @Test
    fun legacyV1PacketsRemainDecodable() {
        val v2 = FileTransferPacketCodec.fragment(
            FileTransferPacketCodec.Type.CHUNK,
            transferId,
            123L,
            byteArrayOf(7, 8, 9),
        ).single()
        val v2Unsigned = v2.copyOfRange(0, v2.size - FileTransferPacketCodec.AUTH_DIGEST_BYTES)
        val legacyUnsigned = ByteBuffer.allocate(v2Unsigned.size - 4)
            .order(ByteOrder.BIG_ENDIAN)
            .put(FileTransferPacketCodec.VERSION_V1)
            .put(v2Unsigned[1])
            .put(transferId)
            .putInt(123)
            .put(v2Unsigned, 26, v2Unsigned.size - 26)
            .array()
        val legacyDigest = MessageDigest.getInstance("SHA-256")
            .apply {
                update("apu-file-packet-v1\u0000".toByteArray(Charsets.US_ASCII))
                update(legacyUnsigned)
            }
            .digest()
            .copyOf(FileTransferPacketCodec.AUTH_DIGEST_BYTES)
        val legacy = legacyUnsigned + legacyDigest
        val decoded = FileTransferPacketCodec.decode(legacy)
        assertEquals(FileTransferPacketCodec.VERSION_V1, decoded.wireVersion)
        assertEquals(123L, decoded.itemIndex)
        assertArrayEquals(byteArrayOf(7, 8, 9), decoded.payload)
    }

    @Test
    fun malformedAndOversizedInputsFailClosed() {
        expectFailure {
            FileTransferPacketCodec.fragment(
                FileTransferPacketCodec.Type.CHUNK,
                ByteArray(16),
                0,
                byteArrayOf(1),
            )
        }
        expectFailure {
            FileTransferPacketCodec.fragment(
                FileTransferPacketCodec.Type.CHUNK,
                transferId,
                0,
                ByteArray(4 * 1024 * 1024 + 17),
            )
        }
        expectFailure { FileTransferPacketCodec.decode(byteArrayOf()) }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected operation to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
