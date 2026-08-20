package com.vladimir.messenger.data.file

/** Exact framing for one Android-Keystore-wrapped 32-byte transfer key. */
object FileTransferKeyEnvelope {
    const val VERSION: Byte = 1
    const val IV_BYTES = 12
    const val KEY_BYTES = 32
    const val TAG_BYTES = 16
    const val CIPHERTEXT_BYTES = KEY_BYTES + TAG_BYTES
    const val ENVELOPE_BYTES = 1 + IV_BYTES + CIPHERTEXT_BYTES

    data class Decoded(val iv: ByteArray, val ciphertext: ByteArray)

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "Invalid transfer key IV length" }
        require(ciphertext.size == CIPHERTEXT_BYTES) { "Invalid wrapped transfer key length" }
        return ByteArray(ENVELOPE_BYTES).also { output ->
            output[0] = VERSION
            iv.copyInto(output, 1)
            ciphertext.copyInto(output, 1 + IV_BYTES)
        }
    }

    fun decode(envelope: ByteArray): Decoded {
        require(envelope.size == ENVELOPE_BYTES) { "Invalid transfer key envelope length" }
        require(envelope[0] == VERSION) { "Unsupported transfer key envelope version" }
        return Decoded(
            iv = envelope.copyOfRange(1, 1 + IV_BYTES),
            ciphertext = envelope.copyOfRange(1 + IV_BYTES, ENVELOPE_BYTES),
        )
    }
}
