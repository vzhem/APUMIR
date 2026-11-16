package com.vladimir.messenger.data.push

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated, versioned token-journal envelopes; no provider token is exposed to callers. */
internal object FcmTokenCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val currentAad = "apu-push-fcm-token-v2".toByteArray(Charsets.US_ASCII)
    private val hash = Regex("^[0-9a-f]{64}$")

    fun encrypt(fingerprint: String, key: SecretKey, confirmed: Boolean = false): ByteArray {
        require(hash.matches(fingerprint) && fingerprint.any { it != '0' })
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(currentAad)
        val iv = cipher.iv
        check(iv.size == 12)
        return byteArrayOf(2) + iv + cipher.doFinal((if (confirmed) "1" else "0").plus(fingerprint).toByteArray(Charsets.US_ASCII))
    }

    /** Version 1 used the current token's fingerprint as AAD and encrypted the token itself.
     * Only a matching current token can authenticate it. The caller must re-register it. */
    fun decrypt(blob: ByteArray, key: SecretKey, currentToken: String? = null, currentFingerprint: String? = null): Result {
        require(blob.size in 30..4096)
        val version = blob[0].toInt()
        require(version == 1 || version == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        if (version == 1) {
            require(currentToken != null && currentFingerprint != null && hash.matches(currentFingerprint))
            cipher.updateAAD("apu-push-fcm-token-v1\u0000$currentFingerprint".toByteArray(Charsets.US_ASCII))
        } else {
            cipher.updateAAD(currentAad)
        }
        val plaintext = cipher.doFinal(blob.copyOfRange(13, blob.size))
        try {
            if (version == 1) {
                val tokenBytes = requireNotNull(currentToken).toByteArray(Charsets.UTF_8)
                try { check(MessageDigest.isEqual(plaintext, tokenBytes)) } finally { tokenBytes.fill(0) }
                return Result(requireNotNull(currentFingerprint), legacy = true, confirmed = false)
            }
            val text = plaintext.toString(Charsets.US_ASCII)
            check(text.length == 65 && (text[0] == '0' || text[0] == '1'))
            val fingerprint = text.substring(1)
            check(hash.matches(fingerprint) && fingerprint.any { it != '0' })
            return Result(fingerprint, legacy = false, confirmed = text[0] == '1')
        } finally {
            plaintext.fill(0)
        }
    }

    data class Result(val fingerprint: String, val legacy: Boolean, val confirmed: Boolean)
}
