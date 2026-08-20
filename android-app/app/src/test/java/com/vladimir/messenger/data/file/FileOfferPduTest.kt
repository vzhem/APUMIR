package com.vladimir.messenger.data.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileOfferPduTest {
    private val manifest = ByteArray(96) { (it % 251).toByte() }
    private val envelope = ByteArray(220) { (it % 241).toByte() }
    private val binding = ByteArray(140) { (it % 199).toByte() }

    @Test
    fun roundTripPreservesAllFields() {
        val decoded = FileOfferPdu.decode(FileOfferPdu.encode(manifest, envelope, binding))
        assertArrayEquals(manifest, decoded.manifest)
        assertArrayEquals(envelope, decoded.keyEnvelope)
        assertArrayEquals(binding, decoded.senderBinding)
    }

    @Test
    fun encodeRejectsOutOfBoundsFields() {
        assertRejected(ByteArray(10), envelope, binding, "manifest too small")
        assertRejected(
            ByteArray(FileOfferPdu.MAX_MANIFEST_BYTES + 1),
            envelope,
            binding,
            "manifest too large",
        )
        assertRejected(manifest, ByteArray(10), binding, "envelope too small")
        assertRejected(manifest, ByteArray(FileOfferPdu.MAX_ENVELOPE_BYTES + 1), binding, "envelope too large")
        assertRejected(manifest, envelope, ByteArray(0), "binding empty")
        assertRejected(manifest, envelope, ByteArray(FileOfferPdu.MAX_BINDING_BYTES + 1), "binding too large")
    }

    @Test
    fun decodeFailsClosedOnTrailingBytes() {
        val encoded = FileOfferPdu.encode(manifest, envelope, binding)
        try {
            FileOfferPdu.decode(encoded + 0)
            fail("Trailing byte accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Trailing"))
        }
    }

    @Test
    fun decodeFailsClosedOnBadVersionAndTruncation() {
        val encoded = FileOfferPdu.encode(manifest, envelope, binding)
        val badVersion = encoded.copyOf().also { it[0] = 2 }
        try {
            FileOfferPdu.decode(badVersion)
            fail("Unknown version accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("version"))
        }
        try {
            FileOfferPdu.decode(encoded.copyOfRange(0, encoded.size - 1))
            fail("Truncated offer accepted")
        } catch (expected: Exception) {
            assertTrue(expected is IllegalArgumentException || expected is IndexOutOfBoundsException)
        }
    }

    private fun assertRejected(m: ByteArray, e: ByteArray, b: ByteArray, why: String) {
        try {
            FileOfferPdu.encode(m, e, b)
            fail("Accepted invalid offer: $why")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
