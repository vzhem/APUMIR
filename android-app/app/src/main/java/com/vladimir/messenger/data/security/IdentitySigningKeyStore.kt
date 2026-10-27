package com.vladimir.messenger.data.security

import com.vladimir.messenger.data.diagnostics.KeyModeReason

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import uniffi.p2p_core.createIdentitySigningBinding
import uniffi.p2p_core.createReferralInviteToken
import uniffi.p2p_core.identitySigningBindingMatchesInstalled
import uniffi.p2p_core.identitySigningKeyId
import uniffi.p2p_core.identitySigningMode
import uniffi.p2p_core.identitySigningPublicKeyHex
import uniffi.p2p_core.installIdentitySigningSeed
import uniffi.p2p_core.verifyIdentitySigningBinding
import uniffi.p2p_core.verifyReferralInviteToken
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
    private const val TAG = "IdentitySigningKeyStore"
    private const val FORMAT_VERSION = 1
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_identity_signing_wrap_v1"
    private const val PREFS_NAME = "apu_identity_signing"
    private const val PREF_WRAPPED_SEED = "wrapped_seed_v1"
    private const val PREF_IDENTITY_BINDING = "identity_binding_v1"
    private const val GCM_TAG_BITS = 128
    private const val MAX_ENCODED_ENVELOPE_CHARS = 128
    private const val MAX_ENCODED_BINDING_CHARS = 512
    private const val DEFAULT_REFERRAL_LIFETIME_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_REFERRAL_TOKEN_BYTES = 512
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

    data class InstallDiagnostics(
        val mode: String,
        val keyId: String,
        val publicKeyHex: String,
        val bindingSha256: String,
    )

    /**
     * Install the device-bound sidecar into Rust before engine startup.
     * Returns null on honest degrade; normal messaging remains legacy-compatible.
     */
    @Synchronized
    fun installIntoCore(context: Context, legacyRoutingNodeId: String): InstallDiagnostics? {
        if (!legacyRoutingNodeId.matches(Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$"))) {
            Log.w(TAG, "Signing sidecar disabled: invalid legacy routing ID")
            return null
        }
        return try {
            withSeed(context) { seed ->
                installIdentitySigningSeed(
                    FORMAT_VERSION.toUByte(),
                    legacyRoutingNodeId,
                    seed,
                )
            }
            val mode = identitySigningMode()
            val publicKey = identitySigningPublicKeyHex()
            val keyId = identitySigningKeyId()
            val expectedKeyId = MessageDigest.getInstance("SHA-256")
                .digest(publicKey.hexToBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            check(mode == "legacy+ed25519-sidecar-v1") { "unexpected signing mode" }
            check(publicKey.length == 64) { "unexpected signing public key length" }
            check(keyId.length == 64 && keyId == expectedKeyId) { "signing key ID mismatch" }

            dropStaleBinding(context.applicationContext)
            val binding = loadOrCreateBinding(context.applicationContext)
            check(verifyIdentitySigningBinding(binding)) { "identity binding signature invalid" }
            check(identitySigningBindingMatchesInstalled(binding)) {
                "identity binding does not match installed sidecar"
            }
            val bindingHash = MessageDigest.getInstance("SHA-256")
                .digest(binding)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            Log.i(
                TAG,
                "Signing sidecar installed (mode=$mode, keyId=${keyId.take(12)}…, " +
                    "binding=${bindingHash.take(12)}…)"
            )
            InstallDiagnostics(mode, keyId, publicKey, bindingHash)
        } catch (error: Exception) {
            // Причина одной строкой: отчёт из logcat хранит только строки
            // выбранных тегов, а стек без заголовка не говорит ничего - именно
            // так «ключ не читается» месяцами оставался без виновника.
            Log.e(
                TAG,
                "Signing sidecar unavailable: " + describe(error) + " · reason=" + lastModeReason,
                error,
            )
            null
        }
    }

    /** Returns a verified public binding copy only while the matching sidecar is installed. */
    @Synchronized
    fun existingVerifiedBinding(context: Context): ByteArray? = try {
        loadExistingVerifiedBinding(context.applicationContext)?.copyOf()
    } catch (_: Exception) {
        null
    }

    /** Creates a short-lived public referral token without returning private material. */
    @Synchronized
    fun createSignedReferralToken(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        lifetimeMs: Long = DEFAULT_REFERRAL_LIFETIME_MS,
    ): ByteArray? {
        if (nowMs < 0 || lifetimeMs <= 0 || lifetimeMs > DEFAULT_REFERRAL_LIFETIME_MS) return null
        return try {
            val binding = loadExistingVerifiedBinding(context.applicationContext) ?: return null
            val expiresAt = Math.addExact(nowMs, lifetimeMs)
            val token = createReferralInviteToken(binding, nowMs, expiresAt)
            check(token.isNotEmpty() && token.size <= MAX_REFERRAL_TOKEN_BYTES) {
                "unexpected referral token size"
            }
            check(verifyReferralInviteToken(token, nowMs)) { "new referral token verification failed" }
            token
        } catch (error: Exception) {
            Log.w(TAG, "Signed referral unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

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

    /**
     * Положить seed из резервной копии, завернув его ключом Keystore ЭТОГО
     * телефона. Прежний seed (если был) заменяется - вместе с ним копия несёт
     * и привязку личности, подписанную именно этим seed.
     */
    @Synchronized
    fun importSeed(context: Context, seed: ByteArray): Boolean = try {
        require(seed.size == IdentitySigningSeedEnvelope.SEED_BYTES)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Ключ-обёртка служит ТОЛЬКО этому семени. Если прежний блоб им уже не
        // открывается (`reason=unwrap_failed`), значит запись в Keystore
        // осталась от другого ключа - и заворачивать в неё новое семя нельзя:
        // она может быть непригодна и на шифрование. Сносим старую запись и
        // создаём свежую. Возврат ключей не должен зависеть от той поломки, из-за
        // которой он понадобился.
        val encoded = wrap(seed, freshWrapKey())
        prefs.edit().putString(PREF_WRAPPED_SEED, encoded).commit()
    } catch (error: Exception) {
        lastImportError = describe(error)
        false
    }

    /** Сообщение последнего неудачного импорта: человеку и в отчёт, без стека. */
    @Volatile var lastImportError: String = ""
        private set

    @Volatile var lastModeReason = com.vladimir.messenger.data.diagnostics.KeyModeReason.NOT_PROBED
        private set

    /** Read-only diagnostic: never creates a Keystore key, seed, or prefs. */
    @Synchronized
    fun mode(context: Context): Mode {
        lastModeReason = KeyModeReason.READ_PREFS
        val app = context.applicationContext
        val encoded = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_WRAPPED_SEED, null)
        if (encoded == null) { lastModeReason = KeyModeReason.ABSENT; return Mode.ABSENT }
        val seed = try {
            lastModeReason = KeyModeReason.LOOKUP_KEY
            val wrapKey = existingWrapKey()
            if (wrapKey == null) { lastModeReason = KeyModeReason.MISSING_WRAP_KEY; return Mode.UNAVAILABLE }
            lastModeReason = KeyModeReason.UNWRAP
            unwrap(encoded, wrapKey)
        } catch (_: Exception) {
            lastModeReason = if (lastModeReason == KeyModeReason.LOOKUP_KEY) KeyModeReason.KEYSTORE_ERROR else KeyModeReason.UNWRAP_FAILED
            return Mode.UNAVAILABLE
        }
        seed.fill(0)
        lastModeReason = KeyModeReason.READY
        return Mode.READY
    }

    private fun loadExistingVerifiedBinding(context: Context): ByteArray? {
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_IDENTITY_BINDING, null) ?: return null
        require(encoded.length <= MAX_ENCODED_BINDING_CHARS) {
            "identity signing binding is unbounded"
        }
        val binding = Base64.decode(encoded, Base64.NO_WRAP)
        check(binding.isNotEmpty()) { "identity signing binding is empty" }
        check(verifyIdentitySigningBinding(binding)) { "identity binding signature invalid" }
        check(identitySigningBindingMatchesInstalled(binding)) {
            "identity binding does not match installed sidecar"
        }
        return binding
    }

    /**
     * Убрать привязку, которая не подходит установленному сейчас семени.
     *
     * Привязка подписана семенем и содержит routing id. После возврата ключей
     * из копии, смены профиля или пересоздания личности старая привязка
     * перестаёт совпадать. Без этой проверки код ниже всё равно упал бы - но
     * тогда телефон остался бы совсем без подписи, хотя ключ уже на месте.
     * Нет привязки - [loadOrCreateBinding] честно подпишет новую тем же
     * ключом.
     */
    private fun dropStaleBinding(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = prefs.getString(PREF_IDENTITY_BINDING, null) ?: return
        val usable = runCatching {
            require(encoded.length <= MAX_ENCODED_BINDING_CHARS)
            val binding = Base64.decode(encoded, Base64.NO_WRAP)
            verifyIdentitySigningBinding(binding) && identitySigningBindingMatchesInstalled(binding)
        }.getOrDefault(false)
        if (!usable) {
            prefs.edit().remove(PREF_IDENTITY_BINDING).commit()
            Log.w(TAG, "identity binding did not fit the installed seed; a new one will be signed")
        }
    }

    private fun loadOrCreateBinding(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_IDENTITY_BINDING, null)
        if (existing != null) {
            require(existing.length <= MAX_ENCODED_BINDING_CHARS) {
                "identity signing binding is unbounded"
            }
            return Base64.decode(existing, Base64.NO_WRAP)
        }

        val binding = createIdentitySigningBinding(System.currentTimeMillis())
        check(binding.isNotEmpty()) { "Rust returned empty identity binding" }
        check(verifyIdentitySigningBinding(binding)) { "new identity binding signature invalid" }
        check(identitySigningBindingMatchesInstalled(binding)) {
            "new identity binding does not match installed sidecar"
        }
        val encoded = Base64.encodeToString(binding, Base64.NO_WRAP)
        check(encoded.length <= MAX_ENCODED_BINDING_CHARS)
        val persisted = prefs.edit().putString(PREF_IDENTITY_BINDING, encoded).commit()
        if (!persisted) {
            throw SigningSeedUnavailableException("cannot persist identity signing binding")
        }
        return binding
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

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0 && all { it.isDigit() || it in 'a'..'f' })
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun existingWrapKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (keyStore.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** «Класс: сообщение <- причина» — одной строкой, без перевода строк. */
    private fun describe(error: Throwable): String {
        val cause = error.cause?.let { " <- " + it.javaClass.simpleName }
        val message = error.message?.replace('\n', ' ')?.take(160)
        return error.javaClass.simpleName + (if (message != null) ": " + message else "") + (cause ?: "")
    }

    /**
     * Свежий ключ-обёртка вместо прежнего.
     *
     * Запись в Keystore может остаться, но быть непригодной: старый блоб ею не
     * открывается. Удаление и создание заново здесь ничего не теряет - этим
     * ключом завёрнуто только семя подписи, и его мы как раз перезаписываем.
     * Сбой удаления не отменяет попытку: если запись жива и рабочая, ниже она
     * просто будет использована.
     */
    private fun freshWrapKey(): SecretKey {
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(WRAP_ALIAS)
        }.onFailure { Log.w(TAG, "wrap key delete failed: " + it.javaClass.simpleName) }
        return ensureWrapKey()
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
