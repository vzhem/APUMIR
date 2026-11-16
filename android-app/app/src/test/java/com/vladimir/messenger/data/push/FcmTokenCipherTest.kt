package com.vladimir.messenger.data.push

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class FcmTokenCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val fingerprint = "ab".repeat(32)
    private val token = "test-token-0123456789-abcdefgh"

    private fun legacy(): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12) { 3 }))
        cipher.updateAAD("apu-push-fcm-token-v1\u0000$fingerprint".toByteArray(Charsets.US_ASCII))
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
    }

    @Test fun currentRoundTripNeedsNoTokenAndDetectsTampering() {
        val blob = FcmTokenCipher.encrypt(fingerprint, key)
        assertEquals(FcmTokenCipher.Result(fingerprint, false, false), FcmTokenCipher.decrypt(blob, key))
        val confirmed = FcmTokenCipher.encrypt(fingerprint, key, confirmed = true)
        assertEquals(FcmTokenCipher.Result(fingerprint, false, true), FcmTokenCipher.decrypt(confirmed, key))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(fingerprint))
        blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { FcmTokenCipher.decrypt(blob, key) }
    }

    @Test fun legacyRequiresMatchingTokenAndAuthentication() {
        val blob = legacy()
        assertEquals(FcmTokenCipher.Result(fingerprint, true, false), FcmTokenCipher.decrypt(blob, key, token, fingerprint))
        assertThrows(Exception::class.java) { FcmTokenCipher.decrypt(blob, key) }
        assertThrows(Exception::class.java) { FcmTokenCipher.decrypt(blob, key, "different-token", fingerprint) }
        assertThrows(Exception::class.java) { FcmTokenCipher.decrypt(blob, key, token, "cd".repeat(32)) }
        assertThrows(Exception::class.java) { FcmTokenCipher.decrypt(blob, SecretKeySpec(ByteArray(32) { 2 }, "AES"), token, fingerprint) }
    }

    @Test fun unknownVersionIsNotSilentlyAccepted() {
        val blob = FcmTokenCipher.encrypt(fingerprint, key)
        blob[0] = 3
        assertThrows(Exception::class.java) { FcmTokenCipher.decrypt(blob, key) }
    }
}
