package com.vladimir.messenger.data.mirror

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric frame protection for a newly paired device.
 *
 * A paired device has its own device/file-exchange keys, so the legacy
 * MessageSealer envelope cannot be opened by the other phone. The primary
 * sends this room key inside the authenticated pairing grant; both phones then
 * use it only inside the private mirror room. Older mirror peers continue to
 * use MessageSealer until both sides advertise this protocol version.
 */
object MirrorFrameCipher {
    private const val PREFIX = "APUMIRROR2:"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val aad = "apu-mirror-frame-v2".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun seal(key: ByteArray, plaintext: String): String? {
        if (key.size != KEY_BYTES || plaintext.isEmpty()) return null
        return runCatching {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(iv + ciphertext)
        }.getOrNull()
    }

    fun open(key: ByteArray, wire: String): String? {
        if (key.size != KEY_BYTES || !wire.startsWith(PREFIX)) return null
        return runCatching {
            val encoded = wire.removePrefix(PREFIX)
            val raw = java.util.Base64.getUrlDecoder().decode(encoded)
            require(raw.size > IV_BYTES + TAG_BITS / 8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }
}
