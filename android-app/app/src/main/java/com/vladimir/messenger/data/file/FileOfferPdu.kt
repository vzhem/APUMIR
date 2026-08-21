package com.vladimir.messenger.data.file

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned framing for the OFFER item payload: canonical manifest bytes, the authenticated
 * per-transfer file-key envelope and the sender's signed file-exchange binding travel together
 * so the receiver can verify, pin and unwrap in one strict step.
 *
 * Wire layout (big endian):
 *   version u8 = 1
 *   manifestLength  u16 + manifest bytes
 *   envelopeLength  u16 + key envelope bytes
 *   bindingLength   u16 + sender signed exchange binding bytes
 *
 * Strict decode: exact lengths, known version, no trailing bytes.
 */
object FileOfferPdu {
    const val VERSION: Byte = 1
    const val MAX_MANIFEST_BYTES = FileTransferChunkStore.MAX_MANIFEST_BYTES
    const val MIN_MANIFEST_BYTES = FileTransferChunkStore.MIN_MANIFEST_BYTES
    const val MAX_ENVELOPE_BYTES = FileTransferChunkStore.MAX_KEY_ENVELOPE_BYTES
    const val MIN_ENVELOPE_BYTES = FileTransferChunkStore.MIN_KEY_ENVELOPE_BYTES
    const val MAX_BINDING_BYTES = 512
    private const val HEADER_BYTES = 1 + 2 + 2 + 2

    data class Offer(
        val manifest: ByteArray,
        val keyEnvelope: ByteArray,
        val senderBinding: ByteArray,
    )

    fun maxEncodedBytes(): Int =
        HEADER_BYTES + MAX_MANIFEST_BYTES + MAX_ENVELOPE_BYTES + MAX_BINDING_BYTES

    fun encode(manifest: ByteArray, keyEnvelope: ByteArray, senderBinding: ByteArray): ByteArray {
        require(manifest.size in MIN_MANIFEST_BYTES..MAX_MANIFEST_BYTES) { "Invalid manifest size" }
        require(keyEnvelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) { "Invalid key envelope size" }
        require(senderBinding.size in 1..MAX_BINDING_BYTES) { "Invalid sender binding size" }
        return ByteBuffer.allocate(HEADER_BYTES + manifest.size + keyEnvelope.size + senderBinding.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VERSION)
            .putShort(manifest.size.toShort())
            .put(manifest)
            .putShort(keyEnvelope.size.toShort())
            .put(keyEnvelope)
            .putShort(senderBinding.size.toShort())
            .put(senderBinding)
            .array()
    }

    fun decode(bytes: ByteArray): Offer {
        require(bytes.size in (HEADER_BYTES + MIN_MANIFEST_BYTES + MIN_ENVELOPE_BYTES + 1)..maxEncodedBytes()) {
            "Invalid file offer size"
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.get() == VERSION) { "Unsupported file offer version" }
        val manifest = readLengthPrefixed(input, MIN_MANIFEST_BYTES, MAX_MANIFEST_BYTES)
        val keyEnvelope = readLengthPrefixed(input, MIN_ENVELOPE_BYTES, MAX_ENVELOPE_BYTES)
        val senderBinding = readLengthPrefixed(input, 1, MAX_BINDING_BYTES)
        require(!input.hasRemaining()) { "Trailing bytes in file offer" }
        return Offer(manifest, keyEnvelope, senderBinding)
    }

    private fun readLengthPrefixed(input: ByteBuffer, minimum: Int, maximum: Int): ByteArray {
        val length = input.short.toInt() and 0xffff
        require(length in minimum..maximum) { "File offer field length out of bounds" }
        require(input.remaining() >= length) { "Truncated file offer field" }
        val bytes = ByteArray(length)
        input.get(bytes)
        return bytes
    }
}
