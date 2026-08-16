package com.vladimir.messenger.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import uniffi.p2p_core.installRelayAtRestKey
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * M8-C slice 3: мост между Android Keystore и Rust ядром для durable
 * ENCRYPTED relay custody (шифрование хранимых relay-записей «в покое»).
 *
 * Схема (стандартный envelope-подход):
 *  - В Android Keystore хранится не-извлекаемый AES/GCM wrap-ключ
 *    ([WRAP_ALIAS]). Он никогда не покидает TEE/железо.
 *  - Один случайный 32-байтный master secret генерируется один раз
 *    (SecureRandom), шифруется (wrap) Keystore-ключом и сохраняется в
 *    app-private SharedPreferences в виде blob:
 *    [keyId: 2 байта big-endian][IV: 12 байт][ciphertext+GCM-tag].
 *  - При каждом старте сервиса blob unwrap-ится и материал передаётся в
 *    Rust через UniFFI ДО старта движка; Kotlin-копия зануляется сразу
 *    после передачи.
 *
 * Честная семантика сбоев (без маскировки и без plaintext-fallback):
 *  - Keystore недоступен / wrap-ключ инвалидирован (data clear, смена
 *    железа) → генерируется НОВЫЙ master secret с новым keyId; старые
 *    записи честно уходят в quarantine в Rust (local custody потерян,
 *    об этом видно в логах и diagnostics).
 *  - Любая ошибка → возвращаем false: движок честно работает в RAM-only
 *    режиме и НЕ заявляет durable custody.
 */
object RelayAtRestMasterKey {

    private const val TAG = "RelayAtRestKey"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_relay_at_rest_wrap_v1"

    private const val PREFS_NAME = "apu_relay_at_rest"
    private const val PREF_WRAPPED_BLOB = "wrapped_master_v1"

    private const val MASTER_SECRET_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val BLOB_HEADER_BYTES = 2 // keyId big-endian

    /** 0 зарезервирован Rust-стороной под эфемерный RAM-only ключ. */
    private const val MAX_EPHEMERAL_KEY_ID = 0

    /**
     * Установить at-rest ключ в Rust ядро. ВЫЗЫВАТЬ СТРОГО ДО
     * [com.vladimir.messenger.data.RustBridge.initialize] / engine.start().
     *
     * @return true — ключ установлен (durable-encrypted custody доступно
     * при наличии relay_db_path); false — честный RAM-only degrade.
     */
    @Synchronized
    fun installIntoCore(context: Context): Boolean {
        val material = try {
            loadOrCreate(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Keystore bridge failed; relay custody will be RAM-only", e)
            null
        } ?: return false

        return try {
            installRelayAtRestKey(material.keyId.toUShort(), material.secret)
            Log.i(TAG, "at-rest key installed into Rust core (keyId=${material.keyId})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install into Rust core failed; relay custody will be RAM-only", e)
            false
        } finally {
            // Kotlin-копия секрета зануляется сразу после передачи.
            material.secret.fill(0)
        }
    }

    /** keyId сохранённого blob (диагностика; материал не возвращается). */
    fun persistedKeyId(context: Context): Int? {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_WRAPPED_BLOB, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size >= BLOB_HEADER_BYTES) {
                ((blob[0].toInt() and 0xFF) shl 8) or (blob[1].toInt() and 0xFF)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ────────────────────────────────────────────────────────────────
    // internals
    // ────────────────────────────────────────────────────────────────

    private class UnwrappedMaster(val keyId: Int, val secret: ByteArray)

    private fun loadOrCreate(context: Context): UnwrappedMaster {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = prefs.getString(PREF_WRAPPED_BLOB, null)
        if (encoded != null) {
            try {
                return unwrap(encoded)
            } catch (e: Exception) {
                // Data clear / смена Keystore: unwrap невозможен. Честно
                // генерируем новый master secret с НОВЫМ keyId — старые записи
                // станут quarantine (UnknownKeyId/auth-failed на Rust-стороне),
                // а не молча пропадут или «расшифруются неправильно».
                Log.w(
                    TAG,
                    "Cannot unwrap master secret (${e.javaClass.simpleName}); " +
                        "generating fresh one — previous relay records become quarantined"
                )
            }
        }
        val freshBlob = createFresh()
        prefs.edit().putString(PREF_WRAPPED_BLOB, freshBlob).apply()
        return unwrap(freshBlob)
    }

    private fun ensureWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Создать master secret, wrap-нуть и вернуть Base64(blob). */
    private fun createFresh(): String {
        val secret = ByteArray(MASTER_SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            // keyId 1..65534 (0 = эфемерный RAM-only маркер на Rust-стороне).
            val keyId = SecureRandom().nextInt(0xFFFE - 1) + 1
            check(keyId != MAX_EPHEMERAL_KEY_ID) { "keyId 0 is reserved" }

            val wrapKey = ensureWrapKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
            val wrapped = cipher.doFinal(secret)
            val iv = cipher.iv
            check(iv.size == GCM_IV_BYTES) { "unexpected GCM IV size ${iv.size}" }

            val blob = ByteArray(BLOB_HEADER_BYTES + iv.size + wrapped.size)
            blob[0] = (keyId shr 8).toByte()
            blob[1] = (keyId and 0xFF).toByte()
            System.arraycopy(iv, 0, blob, BLOB_HEADER_BYTES, iv.size)
            System.arraycopy(wrapped, 0, blob, BLOB_HEADER_BYTES + iv.size, wrapped.size)
            return Base64.encodeToString(blob, Base64.NO_WRAP)
        } finally {
            secret.fill(0)
        }
    }

    private fun unwrap(encoded: String): UnwrappedMaster {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > BLOB_HEADER_BYTES + GCM_IV_BYTES) { "corrupt wrapped blob length" }
        val keyId = ((blob[0].toInt() and 0xFF) shl 8) or (blob[1].toInt() and 0xFF)
        val iv = blob.copyOfRange(BLOB_HEADER_BYTES, BLOB_HEADER_BYTES + GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(BLOB_HEADER_BYTES + GCM_IV_BYTES, blob.size)

        val wrapKey = ensureWrapKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val secret = cipher.doFinal(ciphertext) // AEADBadTagException при чужом ключе
        check(secret.size == MASTER_SECRET_BYTES) { "unexpected master secret size ${secret.size}" }
        return UnwrappedMaster(keyId, secret)
    }
}
