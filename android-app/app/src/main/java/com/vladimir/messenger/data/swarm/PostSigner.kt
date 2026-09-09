package com.vladimir.messenger.data.swarm

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import uniffi.p2p_core.identitySigningPublicKeyHex

/**
 * Подпись манифестов постов ключом личности (рой, этап 1).
 *
 * Семя Ed25519 живёт в [IdentitySigningKeyStore] и выдаётся только на время
 * одного вызова; здесь оно уходит в Java-библиотеку `eddsa`, а не в ядро -
 * FFI подписи произвольных байтов в ядре пока нет (этап 3). Чтобы не
 * подписать чем-то не тем, перед первой подписью сверяем открытый ключ,
 * который выводит библиотека, с тем, что ядро сообщает о себе
 * ([identitySigningPublicKeyHex]). Разошлись - подпись выключена, посты
 * уходят как раньше (без манифеста), в журнале предупреждение.
 */
object PostSigner {
    private const val TAG = "PostSigner"

    /** null - ключ не готов или проверка не сошлась; тогда пост уходит без манифеста. */
    @Volatile
    private var verifiedPublicKey: ByteArray? = null

    @Volatile
    private var checkFailed = false

    /** Мой открытый ключ подписи постов (32 байта) или null, если подпись недоступна. */
    fun publicKey(context: Context): ByteArray? {
        verifiedPublicKey?.let { return it.copyOf() }
        if (checkFailed) return null
        synchronized(this) {
            verifiedPublicKey?.let { return it.copyOf() }
            val coreHex = try {
                identitySigningPublicKeyHex()
            } catch (_: Exception) {
                ""
            }
            if (coreHex.length != 64) {
                // Ядро ещё не подняло ключ (первый запуск) - попробуем позже.
                return null
            }
            val derived: ByteArray = try {
                IdentitySigningKeyStore.withSeed(context.applicationContext) { seed ->
                    Ed25519.keyPairFromSeed(seed).publicKey
                }
            } catch (e: Exception) {
                Log.w(TAG, "signing seed unavailable: ${e.message}")
                return null
            }
            if (PostManifest.hex(derived) != coreHex) {
                checkFailed = true
                Log.e(TAG, "eddsa public key differs from core key; post signing disabled")
                return null
            }
            verifiedPublicKey = derived
            Log.i(TAG, "post signing ready key=${coreHex.take(12)}…")
            return derived.copyOf()
        }
    }

    /** Подписать манифест; null - подпись недоступна. */
    fun sign(context: Context, manifest: PostManifest): PostManifest? {
        publicKey(context) ?: return null
        return try {
            IdentitySigningKeyStore.withSeed(context.applicationContext) { seed -> manifest.signed(seed) }
        } catch (e: Exception) {
            Log.w(TAG, "manifest signing failed: ${e.message}")
            null
        }
    }
}
