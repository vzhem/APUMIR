package com.vladimir.messenger.data.file

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
import uniffi.p2p_core.createFileExchangeBinding
import uniffi.p2p_core.fileExchangeBindingNodeId
import uniffi.p2p_core.fileExchangeBindingPublicKey
import uniffi.p2p_core.verifyFileExchangeBinding

/** Device-bound static X25519 file-exchange secret and its Ed25519-signed public binding. */
object FileExchangeKeyStore {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_file_exchange_wrap_v1"
    private const val PREFS = "apu_file_exchange"
    private const val WRAPPED_SECRET = "wrapped_x25519_secret_v1"
    private const val PUBLIC_BINDING = "signed_public_binding_v1"
    private const val GCM_TAG_BITS = 128
    private const val MAX_ENCODED_SECRET = 128
    private const val MAX_ENCODED_BINDING = 512
    private val AAD = "apu-file-exchange-static-secret-v1".toByteArray(Charsets.US_ASCII)

    enum class Mode { ABSENT, READY, UNAVAILABLE }

    data class Diagnostics(
        val nodeId: String,
        val publicKeySha256Prefix: String,
        val bindingSha256Prefix: String,
    )

    @Synchronized
    fun initialize(
        context: Context,
        legacyNodeId: String,
        identityBinding: ByteArray,
    ): Diagnostics? = try {
        require(legacyNodeId.matches(Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")))
        val app = context.applicationContext
        val candidate = withSecret(app) { secret ->
            createFileExchangeBinding(identityBinding, secret, System.currentTimeMillis())
        }
        check(verifyFileExchangeBinding(candidate))
        check(fileExchangeBindingNodeId(candidate) == legacyNodeId)
        val candidatePublic = fileExchangeBindingPublicKey(candidate)
        check(candidatePublic.size == 32)

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existingText = prefs.getString(PUBLIC_BINDING, null)
        val binding = if (existingText == null) {
            val encoded = Base64.encodeToString(candidate, Base64.NO_WRAP)
            check(encoded.length <= MAX_ENCODED_BINDING)
            check(prefs.edit().putString(PUBLIC_BINDING, encoded).commit())
            candidate
        } else {
            require(existingText.length <= MAX_ENCODED_BINDING)
            val existing = Base64.decode(existingText, Base64.NO_WRAP)
            check(verifyFileExchangeBinding(existing))
            check(fileExchangeBindingNodeId(existing) == legacyNodeId)
            check(fileExchangeBindingPublicKey(existing).contentEquals(candidatePublic)) {
                "Persisted file exchange binding does not match device secret"
            }
            candidate.fill(0)
            existing
        }
        val bindingPublic = fileExchangeBindingPublicKey(binding)
        Diagnostics(
            nodeId = legacyNodeId,
            publicKeySha256Prefix = sha256(bindingPublic).take(12),
            bindingSha256Prefix = sha256(binding).take(12),
        )
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun publicBinding(context: Context): ByteArray? = try {
        val encoded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PUBLIC_BINDING, null) ?: return null
        require(encoded.length <= MAX_ENCODED_BINDING)
        Base64.decode(encoded, Base64.NO_WRAP).also { check(verifyFileExchangeBinding(it)) }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun <T> withExistingSecret(context: Context, operation: (ByteArray) -> T): T {
        val secret = loadExisting(context.applicationContext)
        return try { operation(secret) } finally { secret.fill(0) }
    }

    @Synchronized
    fun mode(context: Context): Mode {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(WRAPPED_SECRET, null)
        val binding = prefs.getString(PUBLIC_BINDING, null)
        if (wrapped == null && binding == null) return Mode.ABSENT
        return try {
            check(wrapped != null && binding != null)
            val secret = loadExisting(app)
            secret.fill(0)
            val decoded = Base64.decode(binding, Base64.NO_WRAP)
            check(verifyFileExchangeBinding(decoded))
            Mode.READY
        } catch (_: Exception) {
            Mode.UNAVAILABLE
        }
    }

    private fun <T> withSecret(context: Context, operation: (ByteArray) -> T): T {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(WRAPPED_SECRET, null)
        val secret = if (existing != null) {
            loadExisting(context)
        } else {
            ByteArray(FileTransferKeyEnvelope.KEY_BYTES).also(SecureRandom()::nextBytes).also { key ->
                try {
                    val encoded = wrap(key, ensureWrapKey())
                    check(encoded.length <= MAX_ENCODED_SECRET)
                    check(prefs.edit().putString(WRAPPED_SECRET, encoded).commit())
                } catch (error: Exception) {
                    key.fill(0)
                    throw error
                }
            }
        }
        return try { operation(secret) } finally { secret.fill(0) }
    }

    private fun loadExisting(context: Context): ByteArray {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(WRAPPED_SECRET, null) ?: error("File exchange secret absent")
        require(encoded.length <= MAX_ENCODED_SECRET)
        val wrapKey = existingWrapKey() ?: error("File exchange Keystore alias absent")
        val envelope = FileTransferKeyEnvelope.decode(Base64.decode(encoded, Base64.NO_WRAP))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(envelope.ciphertext).also {
            check(it.size == FileTransferKeyEnvelope.KEY_BYTES)
        }
    }

    private fun wrap(secret: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val envelope = FileTransferKeyEnvelope.encode(cipher.iv, cipher.doFinal(secret))
        return try { Base64.encodeToString(envelope, Base64.NO_WRAP) } finally { envelope.fill(0) }
    }

    private fun existingWrapKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (store.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
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

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
