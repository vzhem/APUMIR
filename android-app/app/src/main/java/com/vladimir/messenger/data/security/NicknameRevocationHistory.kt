package com.vladimir.messenger.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator

/**
 * Device-local revocation ledger for old nickname announcements.
 *
 * Room tombstones protect the current database, while these Keystore aliases
 * survive clearApplicationUserData() as long as the app installation and its
 * Android Keystore remain.  This matters because logout deliberately clears
 * Room: an old gossip packet must not be able to recreate a registration after
 * the next login.
 *
 * The ledger stores no raw owner id or nickname, is never synchronized, and is
 * not a global revocation.  Uninstall/Keystore reset removes the proof; peers
 * may also still retain the old address until a future network revocation
 * protocol exists.
 */
object NicknameRevocationHistory {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "apu_nickname_revocation_v1_"
    private const val KEY_BITS = 256
    private val ownerPattern = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")

    /** Remember the exact owner/name pair as locally revoked. */
    fun revoke(context: Context, ownerId: String, name: String) {
        val alias = aliasFor(ownerId, name) ?: return
        runCatching {
            val keyStore = keyStore()
            if (keyStore.containsAlias(alias)) return
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER,
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build()
            )
            generator.generateKey()
        }
    }

    /** Whether the exact old owner/name pair was revoked on this installation. */
    fun isRevoked(context: Context, ownerId: String, name: String): Boolean {
        val alias = aliasFor(ownerId, name) ?: return false
        return runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)
    }

    /** Allow an explicit restore of that old identity to reclaim its nickname. */
    fun clear(context: Context, ownerId: String, name: String) {
        val alias = aliasFor(ownerId, name) ?: return
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun aliasFor(ownerId: String, name: String): String? {
        val owner = ownerId.trim().lowercase()
        val normalizedName = name.trim().trimStart('@').trim().lowercase()
        if (!ownerPattern.matches(owner) || normalizedName.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$owner|$normalizedName".toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ALIAS_PREFIX + suffix
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
}
