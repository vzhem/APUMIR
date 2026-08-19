package com.vladimir.messenger.data.security

/** Pure, versioned framing for the device-wrapped Ed25519 seed. */
internal object IdentitySigningSeedEnvelope {
    const val VERSION = 1
    const val IV_BYTES = 12
    const val SEED_BYTES = 32
    const val GCM_TAG_BYTES = 16
    const val CIPHERTEXT_BYTES = SEED_BYTES + GCM_TAG_BYTES
    const val ENVELOPE_BYTES = 1 + IV_BYTES + CIPHERTEXT_BYTES

    data class Decoded(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "identity signing IV must be $IV_BYTES bytes" }
        require(ciphertext.size == CIPHERTEXT_BYTES) {
            "identity signing ciphertext must be $CIPHERTEXT_BYTES bytes"
        }
        return ByteArray(ENVELOPE_BYTES).also { output ->
            output[0] = VERSION.toByte()
            System.arraycopy(iv, 0, output, 1, iv.size)
            System.arraycopy(ciphertext, 0, output, 1 + iv.size, ciphertext.size)
        }
    }

    fun decode(envelope: ByteArray): Decoded {
        require(envelope.size == ENVELOPE_BYTES) {
            "identity signing envelope must be $ENVELOPE_BYTES bytes"
        }
        val version = envelope[0].toInt() and 0xFF
        require(version == VERSION) { "unsupported identity signing envelope version $version" }
        return Decoded(
            iv = envelope.copyOfRange(1, 1 + IV_BYTES),
            ciphertext = envelope.copyOfRange(1 + IV_BYTES, envelope.size),
        )
    }
}
