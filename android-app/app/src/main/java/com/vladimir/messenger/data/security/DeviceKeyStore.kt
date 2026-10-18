package com.vladimir.messenger.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uniffi.p2p_core.devicePublicKeyFromSeed

/**
 * P2 of docs/MULTI_DEVICE_IDENTITY.md: every installed device owns its own Ed25519 device key (DK).
 *
 * The DK seed is random per installation, wrapped by a non-exportable Android Keystore AES key
 * and never stored in plaintext. The account key is never created or stored here: only the
 * primary device holds it (IdentitySigningKeyStore). A new device starts without an account key
 * and gets a device certificate later through pairing (P3).
 *
 * Plaintext seed bytes leave this object only inside [withSeed], which zeroes them in `finally`.
 */
object DeviceKeyStore {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_device_key_wrap_v1"
    private const val PREFS_NAME = "apu_device_key"
    private const val PREF_WRAPPED_SEED = "wrapped_seed_v1"
    private const val PREF_DEVICE_ID = "device_id_v1"
    private const val PREF_DEVICE_CERT = "device_certificate_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val SEED_BYTES = 32
    private const val DEVICE_ID_BYTES = 16
    private const val MAX_CERT_CHARS = 1024
    private val AAD = "apu-device-key-seed-v1".toByteArray(Charsets.US_ASCII)

    class DeviceKeyUnavailableException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** Stable 16-byte device id, random once per installation. Not secret. */
    @Synchronized
    fun deviceId(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREF_DEVICE_ID, null)?.let { return hexToBytes(it) }
        val id = ByteArray(DEVICE_ID_BYTES).also { SecureRandom().nextBytes(it) }
        if (!prefs.edit().putString(PREF_DEVICE_ID, bytesToHex(id)).commit()) {
            throw DeviceKeyUnavailableException("cannot persist device id")
        }
        return id
    }

    /** Public device key derived in Rust; the seed is zeroed right after. */
    fun devicePublicKey(context: Context): ByteArray =
        withSeed(context) { seed -> devicePublicKeyFromSeed(seed) }

    /** Certificate issued by the primary device for this device key, when paired. */
    fun storeCertificate(context: Context, certificate: ByteArray): Boolean {
        val encoded = Base64.encodeToString(certificate, Base64.NO_WRAP)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_DEVICE_CERT, encoded).commit()
    }

    fun certificate(context: Context): ByteArray? {
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DEVICE_CERT, null) ?: return null
        if (encoded.length > MAX_CERT_CHARS) return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Runs [operation] with the plaintext device seed and zeroes it afterwards. */
    fun <T> withSeed(context: Context, operation: (ByteArray) -> T): T {
        val seed = loadOrCreateSeed(context)
        try {
            return operation(seed)
        } finally {
            seed.fill(0)
        }
    }

    private fun loadOrCreateSeed(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_WRAPPED_SEED, null)
        if (existing != null) {
            val wrapKey = existingWrapKey()
                ?: throw DeviceKeyUnavailableException(
                    "wrapped device seed exists without its Keystore key"
                )
            try {
                return unwrap(existing, wrapKey)
            } catch (error: Exception) {
                throw DeviceKeyUnavailableException("cannot unwrap device seed", error)
            }
        }
        val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val encoded = wrap(seed, ensureWrapKey())
            if (!prefs.edit().putString(PREF_WRAPPED_SEED, encoded).commit()) {
                throw DeviceKeyUnavailableException("cannot persist wrapped device seed")
            }
            return seed
        } catch (error: DeviceKeyUnavailableException) {
            seed.fill(0)
            throw error
        } catch (error: Exception) {
            seed.fill(0)
            throw DeviceKeyUnavailableException("cannot create device seed", error)
        }
    }

    /** Envelope: base64( iv[12] || ciphertext ). */
    private fun wrap(seed: ByteArray, wrapKey: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(seed)
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String, wrapKey: SecretKey): ByteArray {
        require(encoded.length <= 256) { "device seed envelope is unbounded" }
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > IV_BYTES) { "device seed envelope is truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrapKey,
            GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_BYTES)),
        )
        cipher.updateAAD(AAD)
        val seed = cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size))
        if (seed.size != SEED_BYTES) {
            seed.fill(0)
            throw DeviceKeyUnavailableException("unexpected device seed length")
        }
        return seed
    }

    private fun existingWrapKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (keyStore.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun ensureWrapKey(): SecretKey {
        existingWrapKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == DEVICE_ID_BYTES * 2 && hex.all { it.isDigit() || it in 'a'..'f' })
        return ByteArray(DEVICE_ID_BYTES) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
