package com.vladimir.messenger.data.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileTransferKeyEnvelopeTest {
    @Test
    fun exactRoundTripDoesNotAliasInputs() {
        val iv = ByteArray(FileTransferKeyEnvelope.IV_BYTES) { it.toByte() }
        val ciphertext = ByteArray(FileTransferKeyEnvelope.CIPHERTEXT_BYTES) { (it + 20).toByte() }
        val envelope = FileTransferKeyEnvelope.encode(iv, ciphertext)
        iv.fill(99)
        ciphertext.fill(88)

        val decoded = FileTransferKeyEnvelope.decode(envelope)
        assertEquals(FileTransferKeyEnvelope.ENVELOPE_BYTES, envelope.size)
        assertFalse(decoded.iv.all { it == 99.toByte() })
        assertFalse(decoded.ciphertext.all { it == 88.toByte() })
        val reencoded = FileTransferKeyEnvelope.encode(decoded.iv, decoded.ciphertext)
        assertArrayEquals(envelope, reencoded)
    }

    @Test
    fun everyTruncationAndTrailingByteAreRejected() {
        val envelope = FileTransferKeyEnvelope.encode(
            ByteArray(FileTransferKeyEnvelope.IV_BYTES),
            ByteArray(FileTransferKeyEnvelope.CIPHERTEXT_BYTES),
        )
        for (length in 0 until envelope.size) {
            expectFailure { FileTransferKeyEnvelope.decode(envelope.copyOf(length)) }
        }
        expectFailure { FileTransferKeyEnvelope.decode(envelope + 0) }
    }

    @Test
    fun unknownVersionAndInvalidPartsAreRejected() {
        val envelope = FileTransferKeyEnvelope.encode(
            ByteArray(FileTransferKeyEnvelope.IV_BYTES),
            ByteArray(FileTransferKeyEnvelope.CIPHERTEXT_BYTES),
        )
        envelope[0] = 2
        expectFailure { FileTransferKeyEnvelope.decode(envelope) }
        expectFailure {
            FileTransferKeyEnvelope.encode(
                ByteArray(FileTransferKeyEnvelope.IV_BYTES - 1),
                ByteArray(FileTransferKeyEnvelope.CIPHERTEXT_BYTES),
            )
        }
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
