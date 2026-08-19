package com.vladimir.messenger.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentitySigningSeedEnvelopeTest {

    @Test
    fun exactLayoutRoundTrip() {
        val iv = ByteArray(IdentitySigningSeedEnvelope.IV_BYTES) { it.toByte() }
        val ciphertext = ByteArray(IdentitySigningSeedEnvelope.CIPHERTEXT_BYTES) {
            (0x80 + it).toByte()
        }

        val encoded = IdentitySigningSeedEnvelope.encode(iv, ciphertext)

        assertEquals(IdentitySigningSeedEnvelope.ENVELOPE_BYTES, encoded.size)
        assertEquals(IdentitySigningSeedEnvelope.VERSION, encoded[0].toInt())
        val decoded = IdentitySigningSeedEnvelope.decode(encoded)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun encodeRejectsWrongIvAndCiphertextLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentitySigningSeedEnvelope.encode(
                ByteArray(IdentitySigningSeedEnvelope.IV_BYTES - 1),
                ByteArray(IdentitySigningSeedEnvelope.CIPHERTEXT_BYTES),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IdentitySigningSeedEnvelope.encode(
                ByteArray(IdentitySigningSeedEnvelope.IV_BYTES),
                ByteArray(IdentitySigningSeedEnvelope.CIPHERTEXT_BYTES + 1),
            )
        }
    }

    @Test
    fun decodeRejectsEveryTruncationAndTrailingBytes() {
        val valid = IdentitySigningSeedEnvelope.encode(
            ByteArray(IdentitySigningSeedEnvelope.IV_BYTES) { 1 },
            ByteArray(IdentitySigningSeedEnvelope.CIPHERTEXT_BYTES) { 2 },
        )

        for (length in 0 until valid.size) {
            assertThrows("length=$length", IllegalArgumentException::class.java) {
                IdentitySigningSeedEnvelope.decode(valid.copyOf(length))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IdentitySigningSeedEnvelope.decode(valid + byteArrayOf(0))
        }
    }

    @Test
    fun decodeRejectsUnknownVersion() {
        val encoded = IdentitySigningSeedEnvelope.encode(
            ByteArray(IdentitySigningSeedEnvelope.IV_BYTES) { 3 },
            ByteArray(IdentitySigningSeedEnvelope.CIPHERTEXT_BYTES) { 4 },
        )
        encoded[0] = 2

        assertThrows(IllegalArgumentException::class.java) {
            IdentitySigningSeedEnvelope.decode(encoded)
        }
    }

    @Test
    fun decodedArraysDoNotAliasEnvelope() {
        val encoded = IdentitySigningSeedEnvelope.encode(
            ByteArray(IdentitySigningSeedEnvelope.IV_BYTES) { 5 },
            ByteArray(IdentitySigningSeedEnvelope.CIPHERTEXT_BYTES) { 6 },
        )
        val decoded = IdentitySigningSeedEnvelope.decode(encoded)
        encoded.fill(0)

        assertEquals(5, decoded.iv[0].toInt())
        assertEquals(6, decoded.ciphertext[0].toInt())
    }
}
