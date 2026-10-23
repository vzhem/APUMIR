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
    private const val OWNER_ALIAS_PREFIX = "apu_nickname_owner_revocation_v1_"
    private const val KEY_BITS = 256
    private val ownerPattern = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")

    /** Remember the exact owner/name pair as locally revoked. */
    fun revoke(context: Context, ownerId: String, name: String) {
        aliasFor(ownerId, name)?.let(::createMarker)
    }

    /** Revoke every future nickname announcement made by this obsolete owner. */
    fun revokeOwner(context: Context, ownerId: String) {
        ownerAliasFor(ownerId)?.let(::createMarker)
    }

    /** Whether this owner or the exact old owner/name pair was revoked locally. */
    fun isRevoked(context: Context, ownerId: String, name: String): Boolean {
        val ownerAlias = ownerAliasFor(ownerId)
        val nameAlias = aliasFor(ownerId, name)
        return runCatching {
            val keyStore = keyStore()
            (ownerAlias != null && keyStore.containsAlias(ownerAlias)) ||
                (nameAlias != null && keyStore.containsAlias(nameAlias))
        }.getOrDefault(false)
    }

    /** Allow an explicit restore of that old identity to reclaim its nickname. */
    fun clear(context: Context, ownerId: String, name: String) {
        runCatching {
            val keyStore = keyStore()
            aliasFor(ownerId, name)?.let(keyStore::deleteEntry)
            ownerAliasFor(ownerId)?.let(keyStore::deleteEntry)
        }
    }

    /** Clear the owner-wide marker when an old profile is explicitly restored. */
    fun clearOwner(context: Context, ownerId: String) {
        runCatching { ownerAliasFor(ownerId)?.let(keyStore()::deleteEntry) }
    }

    private fun createMarker(alias: String) {
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

    private fun ownerAliasFor(ownerId: String): String? {
        val owner = ownerId.trim().lowercase()
        if (!ownerPattern.matches(owner)) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(owner.toByteArray(Charsets.US_ASCII))
        val suffix = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return OWNER_ALIAS_PREFIX + suffix
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
