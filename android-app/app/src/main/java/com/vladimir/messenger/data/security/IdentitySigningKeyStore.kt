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

/**
 * R0.5/S2 device-bound storage for a future real Ed25519 signing seed.
 *
 * This class is intentionally not wired to Rust/engine yet. The seed is wrapped
 * by a non-exportable Android Keystore AES key. [withSeed] is the only API that
 * exposes plaintext bytes, and it zeroes the same array in `finally`.
 */
object IdentitySigningKeyStore {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_identity_signing_wrap_v1"
    private const val PREFS_NAME = "apu_identity_signing"
    private const val PREF_WRAPPED_SEED = "wrapped_seed_v1"
    private const val GCM_TAG_BITS = 128
    private const val MAX_ENCODED_ENVELOPE_CHARS = 128
    private val AAD = "apu-identity-signing-seed-v1".toByteArray(Charsets.US_ASCII)

    enum class Mode {
        ABSENT,
        READY,
        UNAVAILABLE,
    }

    class SigningSeedUnavailableException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    /**
     * Load or create exactly one 32-byte seed and use it for one bounded call.
     * Existing-but-unreadable state is never overwritten or silently rotated.
     */
    @Synchronized
    @Throws(SigningSeedUnavailableException::class)
    fun <T> withSeed(context: Context, operation: (ByteArray) -> T): T {
        val seed = loadOrCreate(context.applicationContext)
        return try {
            operation(seed)
        } finally {
            seed.fill(0)
        }
    }

    /** Read-only diagnostic: never creates a Keystore key, seed, or prefs. */
    @Synchronized
    fun mode(context: Context): Mode {
        val app = context.applicationContext
        val encoded = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_WRAPPED_SEED, null) ?: return Mode.ABSENT
        val seed = try {
            val wrapKey = existingWrapKey() ?: return Mode.UNAVAILABLE
            unwrap(encoded, wrapKey)
        } catch (_: Exception) {
            return Mode.UNAVAILABLE
        }
        seed.fill(0)
        return Mode.READY
    }

    private fun loadOrCreate(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_WRAPPED_SEED, null)
        if (existing != null) {
            val wrapKey = existingWrapKey()
                ?: throw SigningSeedUnavailableException(
                    "wrapped identity signing seed exists without its device Keystore key"
                )
            return try {
                unwrap(existing, wrapKey)
            } catch (error: Exception) {
                throw SigningSeedUnavailableException(
                    "cannot unwrap existing identity signing seed",
                    error,
                )
            }
        }

        val seed = ByteArray(IdentitySigningSeedEnvelope.SEED_BYTES).also {
            SecureRandom().nextBytes(it)
        }
        try {
            val encoded = wrap(seed, ensureWrapKey())
            val persisted = prefs.edit().putString(PREF_WRAPPED_SEED, encoded).commit()
            if (!persisted) {
                throw SigningSeedUnavailableException("cannot persist wrapped identity signing seed")
            }
            return seed
        } catch (error: SigningSeedUnavailableException) {
            seed.fill(0)
            throw error
        } catch (error: Exception) {
            seed.fill(0)
            throw SigningSeedUnavailableException("cannot create identity signing seed", error)
        }
    }

    private fun wrap(seed: ByteArray, wrapKey: SecretKey): String {
        require(seed.size == IdentitySigningSeedEnvelope.SEED_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(seed)
        val envelope = IdentitySigningSeedEnvelope.encode(cipher.iv, ciphertext)
        return Base64.encodeToString(envelope, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String, wrapKey: SecretKey): ByteArray {
        require(encoded.length <= MAX_ENCODED_ENVELOPE_CHARS) {
            "identity signing envelope is unbounded"
        }
        val envelope = IdentitySigningSeedEnvelope.decode(
            Base64.decode(encoded, Base64.NO_WRAP)
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrapKey,
            GCMParameterSpec(GCM_TAG_BITS, envelope.iv),
        )
        cipher.updateAAD(AAD)
        val seed = cipher.doFinal(envelope.ciphertext)
        if (seed.size != IdentitySigningSeedEnvelope.SEED_BYTES) {
            seed.fill(0)
            throw SigningSeedUnavailableException("unexpected identity signing seed length")
        }
        return seed
    }

    private fun existingWrapKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (keyStore.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun ensureWrapKey(): SecretKey {
        existingWrapKey()?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
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
}
