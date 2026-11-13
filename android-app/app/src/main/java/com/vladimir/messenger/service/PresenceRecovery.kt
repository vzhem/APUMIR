package com.vladimir.messenger.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes the cold-start reset, new observations and expiry. Saved online
 * flags are not evidence that a peer survived a process or APK restart.
 *
 * A delayed reset used to erase a new observation; the expiry loop could also
 * write "offline" after a newer heartbeat. Every transition and both database
 * copies now pass through the same mutex. Failed writes are retried, including
 * a partial reset; cancellation is never swallowed.
 */
internal class PresenceRecovery(
    private val resetContacts: suspend () -> Unit,
    private val resetChats: suspend () -> Unit,
    private val onReset: () -> Unit,
    private val updateContact: suspend (String, Boolean, Long) -> Unit,
    private val updateChats: suspend (String, String, Boolean) -> Unit,
    private val onOnline: (String, Long) -> Unit,
    private val onOffline: (String, Boolean) -> Unit,
    private val recordFailure: (String, Exception) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Observation(val lightTouch: Boolean, val becameOnline: Boolean, val seenAtMs: Long)

    private data class Peer(
        var seenAtMs: Long,
        var lastHeavyAtMs: Long,
        var contactId: String?,
        var online: Boolean = true,
        var retiredAtMs: Long = 0L,
        var contactDirty: Boolean = false,
        var chatsDirty: Boolean = false,
    )

    private val mutex = Mutex()
    private val peers = LinkedHashMap<String, Peer>()
    private var initialized = false

    private suspend fun ensureReset() {
        if (initialized) return
        resetContacts()
        resetChats()
        onReset()
        initialized = true
    }

    suspend fun initialize() = mutex.withLock { ensureReset() }

    /** Start one reset and a quiet expiry loop; an observation can initialize first. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            initialize()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure("initial reset", error)
        }
        while (currentCoroutineContext().isActive) {
            delay(SWEEP_MS)
            try {
                sweep()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure("presence sweep", error)
            }
        }
    }

    /**
     * [eventAtMs] is the original observation time supplied by the core, not
     * the time at which a delayed/retained event happened to be drained.
     * Without a timestamp there is no evidence of freshness.
     */
    suspend fun observe(peerId: String, contactId: String?, eventAtMs: Long?): Observation? =
        mutex.withLock {
            ensureReset()
            val now = nowMs()
            val eventAt = eventAtMs ?: return@withLock null
            if (now <= 0L || eventAt <= 0L ||
                (eventAt > now && eventAt - now > MAX_CLOCK_SKEW_MS) || now - eventAt > TTL_MS
            ) return@withLock null
            val seenAt = minOf(now, eventAt)
            var peer = peers[peerId]
            // A duplicate retained heartbeat cannot resurrect an explicitly
            // lost peer. A genuinely newer observation may bring it back.
            if (peer != null && !peer.online && seenAt <= peer.seenAtMs) return@withLock null
            if (peer == null && peers.size >= MAX_TRACKED_PEERS) {
                // Do not evict a contact whose persisted status would otherwise
                // remain online forever. Unknown peers can safely be forgotten.
                val unknown = peers.entries.firstOrNull { it.value.contactId == null }?.key
                    ?: return@withLock null
                peers.remove(unknown)
                notifyOffline(unknown, expired = false)
            }
            val wasLive = peer != null && peer.online && now >= peer.seenAtMs &&
                now - peer.seenAtMs <= TTL_MS
            val becameOnline = contactId != null && (!wasLive || peer?.contactId != contactId)
            val heavy = peer == null || !wasLive || becameOnline || now < peer.lastHeavyAtMs ||
                now - peer.lastHeavyAtMs >= DEDUP_MS
            if (peer == null) {
                peer = Peer(seenAt, now, contactId)
                peers[peerId] = peer
            } else {
                peer.seenAtMs = maxOf(peer.seenAtMs, seenAt)
                if (heavy) peer.lastHeavyAtMs = now
                if (peer.contactId != contactId) {
                    peer.contactId = contactId
                    peer.contactDirty = contactId != null
                    peer.chatsDirty = contactId != null
                }
                peer.online = true
            }
            if (contactId != null && (heavy || becameOnline)) peer.contactDirty = true
            if (contactId != null && becameOnline) peer.chatsDirty = true
            sync(peerId, peer)
            try {
                onOnline(peerId, peer.seenAtMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure("online callback", error)
            }
            Observation(lightTouch = !heavy, becameOnline = becameOnline, seenAtMs = peer.seenAtMs)
        }

    suspend fun lost(peerId: String) = mutex.withLock {
        ensureReset()
        val peer = peers[peerId]
        if (peer == null) {
            notifyOffline(peerId, expired = false)
        } else {
            retire(peerId, peer, expired = false)
        }
    }

    suspend fun sweep() = mutex.withLock {
        ensureReset()
        val now = nowMs()
        val iterator = peers.iterator()
        while (iterator.hasNext()) {
            val (id, peer) = iterator.next()
            if (peer.online && (now < peer.seenAtMs || now - peer.seenAtMs > TTL_MS)) {
                retire(id, peer, expired = true)
            } else {
                // Retry partial writes even if there are no more heartbeats.
                sync(id, peer)
            }
            if (!peer.online && !peer.contactDirty && !peer.chatsDirty &&
                (now < peer.retiredAtMs || now - peer.retiredAtMs > TTL_MS)
            ) iterator.remove()
        }
    }

    private suspend fun retire(peerId: String, peer: Peer, expired: Boolean) {
        if (peer.online) {
            peer.online = false
            peer.retiredAtMs = nowMs()
            peer.contactDirty = peer.contactId != null
            peer.chatsDirty = peer.contactId != null
            notifyOffline(peerId, expired)
        }
        sync(peerId, peer)
    }

    private fun notifyOffline(peerId: String, expired: Boolean) {
        try {
            onOffline(peerId, expired)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure("offline callback", error)
        }
    }

    private suspend fun sync(peerId: String, peer: Peer) {
        val contactId = peer.contactId ?: return
        if (peer.contactDirty) {
            try {
                updateContact(contactId, peer.online, peer.seenAtMs)
                peer.contactDirty = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure("contact presence", error)
            }
        }
        if (peer.chatsDirty) {
            try {
                // Legacy databases can have a contact primary key different
                // from its routing fingerprint; the adapter updates both.
                updateChats(peerId, contactId, peer.online)
                peer.chatsDirty = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure("chat presence", error)
            }
        }
    }

    companion object {
        const val TTL_MS = 200_000L
        const val SWEEP_MS = 60_000L
        const val DEDUP_MS = 30_000L
        const val MAX_CLOCK_SKEW_MS = 60_000L
        const val MAX_TRACKED_PEERS = 4_096
    }
}
