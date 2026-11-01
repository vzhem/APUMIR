package com.vladimir.messenger.data.contactreset

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.dao.ContactResetDao
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import com.vladimir.messenger.util.NodeIds
import com.vladimir.messenger.util.readBytesAtMost
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.p2p_core.verifiedReferralInviterNodeId
import uniffi.p2p_core.verifyReferralInviteToken

/**
 * One-time contact-directory reset and authenticated re-discovery.
 *
 * The migration clears visible contacts but keeps chats/keys. This coordinator
 * probes the pre-reset candidate set with a short-lived signed hello. Only a
 * live peer whose signed token and exchange binding match the transport sender
 * is restored; retained presence, gossip and old mirror snapshots are not enough.
 */
@Singleton
class ContactResetCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resetDao: ContactResetDao,
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val peerStore: FileExchangePeerStore,
    private val directory: SwarmPeerDirectory,
) {
    private val mutex = Mutex()
    private val lastReplyAt = HashMap<String, Long>()
    private val loopStarted = AtomicBoolean(false)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Run before engine start: drop stale signed-directory leases, keep only candidate address hints. */
    suspend fun prepareNetworkCaches() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = resetDao.state() ?: return@withLock
            startLoop()
            if (state.cacheClearedAtMs != 0L) return@withLock
            val now = System.currentTimeMillis()
            runCatching {
                File(context.filesDir, "apu_peer_directory.json").delete()
                val book = File(context.filesDir, "apu_peer_addresses.json")
                if (book.isFile) {
                    val allowed = resetDao.allIds().map { it.removeSuffix("_public") }.toHashSet()
                    val root = JSONObject(String(book.readBytesAtMost(MAX_BOOK_BYTES), Charsets.UTF_8))
                    val entries = root.optJSONArray("entries") ?: JSONArray()
                    val kept = JSONArray()
                    for (i in 0 until entries.length()) {
                        val entry = entries.optJSONObject(i) ?: continue
                        val id = entry.optString("id").removeSuffix("_public")
                        if (id in allowed) kept.put(entry)
                    }
                    root.put("entries", kept)
                    root.put("history", JSONArray())
                    val tmp = File(book.parentFile, book.name + ".tmp")
                    tmp.writeText(root.toString())
                    if (!tmp.renameTo(book)) {
                        book.writeText(root.toString())
                        tmp.delete()
                    }
                }
                resetDao.markCacheCleared(now)
                directory.invalidate()
                Log.i(TAG, "contact reset: stale network caches cleared")
            }.onFailure { Log.w(TAG, "contact reset cache cleanup failed: ${it.message}") }
        }
    }

    private fun startLoop() {
        if (!loopStarted.compareAndSet(false, true)) return
        appScope.launch {
            delay(15_000L)
            while (isActive) {
                runCatching { pump() }.onFailure { Log.w(TAG, "contact reset pump failed: ${it.message}") }
                if (runCatching { resetDao.pending(1).isEmpty() }.getOrDefault(false)) break
                delay(30_000L)
            }
        }
    }

    /** Keep restored address-book hints inside the pre-reset candidate set. */
    suspend fun filterAddressBook(root: JSONObject): JSONObject {
        if (resetDao.state() == null) return root
        val allowed = resetDao.allIds().map { it.removeSuffix("_public") }.toHashSet()
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val id = entry.optString("id").removeSuffix("_public")
            if (id in allowed) kept.put(entry)
        }
        root.put("entries", kept)
        root.put("history", JSONArray())
        return root
    }

    /** Periodic bounded probe. */
    suspend fun pump() = withContext(Dispatchers.IO) {
        val state = resetDao.state() ?: return@withContext
        if (!RustBridge.isRunning()) return@withContext
        val myId = RustBridge.nodeId() ?: return@withContext
        val binding = FileExchangeKeyStore.publicBinding(context) ?: return@withContext
        val now = System.currentTimeMillis()
        val interval = when {
            now - state.startedAtMs < 3_600_000L -> 60_000L
            now - state.startedAtMs < 86_400_000L -> 300_000L
            else -> 21_600_000L
        }
        val pending = resetDao.pending(MAX_PROBES_PER_PUMP)
        for (candidate in pending) {
            if (candidate.nodeId == myId) continue
            if (candidate.lastAttemptAtMs != 0L && now - candidate.lastAttemptAtMs < interval) continue
            resetDao.markAttempt(candidate.nodeId, now)
            val token = IdentitySigningKeyStore.createSignedReferralToken(context, now, TOKEN_LIFETIME_MS)
                ?: continue
            val name = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .getString("display_name", "").orEmpty().take(MAX_NAME_CHARS)
            val wire = runCatching {
                ContactResetWire.build(ContactResetWire.EPOCH, myId, name, token, binding)
            }.getOrElse {
                Log.w(TAG, "contact reset hello build failed: ${it.message}")
                null
            } ?: continue
            runCatching {
                RustBridge.sendMessage("dir-reset-${ContactResetWire.EPOCH}", candidate.nodeId, candidate.nodeId, wire)
            }.onFailure { Log.w(TAG, "contact reset hello send failed: ${it.message}") }
        }
    }

    /** GroupRouter hands us APUGRP1|dir1 before ordinary group parsing. */
    suspend fun receive(senderId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val hello = ContactResetWire.parse(text) ?: return@withContext true
        val state = resetDao.state() ?: return@withContext true
        if (hello.epoch != state.epoch || hello.nodeId != senderId) return@withContext true
        val now = System.currentTimeMillis()
        val tokenOk = runCatching {
            verifyReferralInviteToken(hello.token, now) &&
                verifiedReferralInviterNodeId(hello.token, now) == senderId
        }.getOrElse {
            Log.w(TAG, "contact reset token verification failed: ${it.message}")
            false
        }
        if (!tokenOk) return@withContext true
        val candidate = resetDao.candidate(senderId) ?: return@withContext true
        val pinned = runCatching { peerStore.pinFirstSeen(hello.binding, now) }
            .getOrElse {
                Log.w(TAG, "contact reset binding rejected: ${it.message}")
                return@withContext true
            }
        if (pinned.nodeId != senderId) return@withContext true
        if (candidate.restoredAtMs != null) {
            reply(senderId, now)
            return@withContext true
        }
        MessageSealer.remember(context, senderId, hello.binding)
        val savedName = candidate.displayName
        val shown = when {
            contactRepository.isRealName(savedName) -> savedName
            contactRepository.isRealName(hello.displayName) -> hello.displayName
            savedName.isNotBlank() -> savedName
            else -> NodeIds.autoName(senderId)
        }
        val result = contactRepository.addContact(shown, senderId, candidate.username)
        if (result.isFailure && result.exceptionOrNull()?.message != "Contact already exists") {
            Log.w(TAG, "contact reset restore failed: ${result.exceptionOrNull()?.message}")
            return@withContext true
        }
        runCatching { chatRepository.getOrCreateChat(senderId, shown) }
            .onFailure { Log.w(TAG, "contact reset chat ensure failed: ${it.message}") }
        resetDao.markRestored(senderId, now)
        directory.invalidate()
        runCatching { RustBridge.setPresenceAudience(directory.audienceIds()) }
            .onFailure { Log.w(TAG, "contact reset audience refresh failed: ${it.message}") }
        Log.i(TAG, "contact reset: live peer restored")
        reply(senderId, now)
        true
    }

    private suspend fun reply(peerId: String, now: Long) {
        val last = lastReplyAt[peerId] ?: 0L
        if (now - last < REPLY_THROTTLE_MS) return
        lastReplyAt[peerId] = now
        val myId = RustBridge.nodeId() ?: return
        val binding = FileExchangeKeyStore.publicBinding(context) ?: return
        val token = IdentitySigningKeyStore.createSignedReferralToken(context, now, TOKEN_LIFETIME_MS) ?: return
        val name = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getString("display_name", "").orEmpty().take(MAX_NAME_CHARS)
        val wire = runCatching {
            ContactResetWire.build(ContactResetWire.EPOCH, myId, name, token, binding)
        }.getOrElse {
            Log.w(TAG, "contact reset reply build failed: ${it.message}")
            return
        }
        runCatching {
            RustBridge.sendMessage("dir-reset-${ContactResetWire.EPOCH}", peerId, peerId, wire)
        }.onFailure { Log.w(TAG, "contact reset reply failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "ContactReset"
        private const val MAX_BOOK_BYTES = 4L * 1024 * 1024
        private const val MAX_PROBES_PER_PUMP = 4
        private const val MAX_NAME_CHARS = 160
        private const val TOKEN_LIFETIME_MS = 10L * 60 * 1000
        private const val REPLY_THROTTLE_MS = 5L * 60 * 1000
    }
}
