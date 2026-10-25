package com.vladimir.messenger.data.security

import android.content.Context
import android.util.Log
import com.vladimir.messenger.BuildConfig
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.FileExchangePeerDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.entity.NicknameSuppressionEntity
import com.vladimir.messenger.data.peer.PeerRatingStore
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes obsolete identities made by this installation when a new app build
 * starts for the first time.
 *
 * This is intentionally stricter than nickname matching: every owner id must be
 * proven by [DeviceIdentityHistory] to have been created on this phone, and the
 * current full node id is always kept.  The cleanup is local and idempotent;
 * it does not claim that remote phones have forgotten their old address.
 */
@Singleton
class ObsoleteSameDeviceRegistrationCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nicknameDao: NicknameDao,
    private val contactDao: ContactDao,
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val fileExchangePeerDao: FileExchangePeerDao,
    private val fileExchangePeerStore: FileExchangePeerStore,
    private val swarmPeerDirectory: SwarmPeerDirectory,
) {
    suspend fun runForCurrentBuild(): CleanupResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val buildCode = BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_LAST_BUILD, Int.MIN_VALUE) == buildCode) {
            return CleanupResult(skipped = true)
        }

        val currentOwnerId = prefs.getString("node_id", null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { FULL_NODE_ID.matches(it) }
            ?: return CleanupResult(skipped = true, noCurrentIdentity = true)

        val registry = nicknameDao.allVisible()
        val namesByOwner = registry
            .groupBy { it.ownerId }
            .mapValues { (_, rows) -> rows.map { it.name }.toSet() }
        val knownOwnerIds = buildSet {
            addAll(registry.map { it.ownerId })
            addAll(contactDao.allIds())
            addAll(chatRepository.getAllContactIds())
            addAll(fileExchangePeerDao.getAll().map { it.nodeId })
        }
        val obsoleteOwnerIds = knownOwnerIds
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != currentOwnerId }
            .filter { DeviceIdentityHistory.wasCreatedOnThisDevice(context, it) }
            .distinct()
            .toList()

        var removed = 0
        var failed = 0
        for (ownerId in obsoleteOwnerIds) {
            try {
                val names = namesByOwner[ownerId].orEmpty()
                // Owner-wide revocation prevents a later packet with a renamed
                // registration from recreating this obsolete identity.
                NicknameRevocationHistory.revokeOwner(context, ownerId)
                for (name in names) {
                    val normalizedName = name.trim().trimStart('@').lowercase()
                    if (normalizedName.isBlank()) continue
                    NicknameRevocationHistory.revoke(context, ownerId, normalizedName)
                    nicknameDao.suppress(
                        NicknameSuppressionEntity(
                            ownerId = ownerId,
                            name = normalizedName,
                            revokedAtMs = System.currentTimeMillis(),
                        )
                    )
                    nicknameDao.deleteByOwnerAndName(ownerId, normalizedName)
                }
                // Keep the owner-wide marker even when the row was already
                // missing, and remove any additional old names under this id.
                nicknameDao.deleteByOwner(ownerId)

                val oldChat = chatRepository.getChatByContactId(ownerId)
                val oldContact = contactRepository.getContactByFingerprint(ownerId)
                val liveContact = contactRepository.getContactByFingerprint(currentOwnerId)
                if (oldChat != null && liveContact != null) {
                    chatRepository.getOrCreateChat(currentOwnerId, liveContact.displayName)
                    chatRepository.absorbChatOf(ownerId, currentOwnerId)
                } else if (oldChat != null) {
                    // No verified live destination exists, so retaining this
                    // chat would retain a route to a permanently dead owner.
                    chatRepository.deleteChatsOf(ownerId)
                }
                if (oldContact != null) {
                    contactRepository.deleteContact(ownerId)
                }

                runCatching { fileExchangePeerStore.forgetPin(ownerId) }
                runCatching { MessageSealer.forget(context, ownerId) }
                runCatching { PeerRatingStore.forget(context, ownerId) }
                removed++
            } catch (error: Exception) {
                failed++
                Log.w(TAG, "obsolete same-device owner cleanup failed: $ownerId", error)
            }
        }

        runCatching { swarmPeerDirectory.invalidate() }
        if (failed == 0) {
            prefs.edit().putInt(KEY_LAST_BUILD, buildCode).apply()
        }
        return CleanupResult(
            removedOwners = removed,
            failedOwners = failed,
        )
    }

    data class CleanupResult(
        val removedOwners: Int = 0,
        val failedOwners: Int = 0,
        val skipped: Boolean = false,
        val noCurrentIdentity: Boolean = false,
    )

    companion object {
        private const val TAG = "SameDeviceCleanup"
        private const val PREFS = "p2p_prefs"
        private const val KEY_LAST_BUILD = "same_device_cleanup_last_build"
        private val FULL_NODE_ID = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")
    }
}
