package com.vladimir.messenger.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator

/**
 * Device-local history of identities created by this installation.
 *
 * The Room database and ordinary preferences are erased by «log out» (the app
 * calls clearApplicationUserData), so they cannot prove that an old `pk_…`
 * belonged to this same physical phone.  Android Keystore aliases survive an
 * app-data clear while the app remains installed; one empty AES key per seen
 * node gives us a non-exportable, local-only ledger without storing the old
 * private key or the raw node id.
 *
 * This is intentionally only a same-device hint: it is not synced, not used as
 * network authentication, and does not survive uninstall/Keystore reset.  If
 * the proof is unavailable, cleanup must leave the candidate untouched rather
 * than guessing from an equal nickname.
 */
object DeviceIdentityHistory {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "apu_identity_history_v1_"
    private const val KEY_BITS = 256

    private val nodePattern = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")

    /** Remember a full canonical node id. Invalid/truncated display ids are ignored. */
    fun remember(context: Context, nodeId: String) {
        val alias = aliasFor(nodeId) ?: return
        runCatching {
            val keyStore = keyStore()
            if (keyStore.containsAlias(alias)) return
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
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

    /** Whether this installation has previously created/restored [nodeId]. */
    fun wasCreatedOnThisDevice(context: Context, nodeId: String): Boolean {
        val alias = aliasFor(nodeId) ?: return false
        return runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)
    }

    private fun aliasFor(nodeId: String): String? {
        val canonical = nodeId.trim().lowercase()
        if (!nodePattern.matches(canonical)) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.US_ASCII))
        val suffix = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ALIAS_PREFIX + suffix
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
}
