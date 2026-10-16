package com.vladimir.messenger.data.heartgame

import android.content.Context
import android.os.SystemClock
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.rank.RankWire
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import com.vladimir.messenger.ui.theme.UsernameHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded periodic gossip lets verified records travel beyond the address book. No immediate echo. */
@Singleton
class HeartGameNetwork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactDao,
    private val chats: ChatDao,
    private val groups: GroupDao,
) {
    private val sendLock = Mutex()
    private val receiveLock = Mutex()
    private var lastSend = -300_000L
    private var window = 0L
    private var received = 0

    suspend fun routeIncoming(text: String): Boolean {
        if (!text.startsWith(HeartGameWire.PREFIX)) return false
        if (text.length > HeartGameWire.MAX_PACKET) return true
        withContext(Dispatchers.IO) {
            receiveLock.withLock {
                val now = SystemClock.elapsedRealtime()
                if (now - window >= 60_000) { window = now; received = 0 }
                if (++received > 60) return@withLock
                val id = HeartGameStore.ownId() ?: return@withLock
                val rows = text.removePrefix(HeartGameWire.PREFIX).split('\n')
                if (rows.size > HeartGameWire.MAX_RECORDS) return@withLock
                val verified = rows.mapNotNull { HeartGameWire.verify(it, System.currentTimeMillis()) { binding ->
                    uniffi.p2p_core.verifyIdentitySigningBinding(binding)
                } }
                HeartGameStore.merge(context, id, verified)
            }
        }
        return true
    }

    suspend fun broadcastIfNeeded() = withContext(Dispatchers.IO) {
        sendLock.withLock {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSend < 300_000) return@withLock
            val id = HeartGameStore.ownId() ?: return@withLock
            if (!HeartGameStore.enabled(context, id) || !com.vladimir.messenger.service.CoreStatus.ready.value) return@withLock
            lastSend = now
            val binding = IdentitySigningKeyStore.existingVerifiedBinding(context) ?: return@withLock
            val own = IdentitySigningKeyStore.withSeed(context) { seed ->
                HeartGameWire.sign(binding, seed, HeartGameStore.score(context, id), System.currentTimeMillis(),
                    UsernameHolder.name.value.orEmpty().take(48))
            }
            // Do not advertise under a stale signing identity after account switching.
            if (HeartGameWire.verify(own, System.currentTimeMillis()) { uniffi.p2p_core.verifyIdentitySigningBinding(it) }?.nodeId != id) return@withLock
            val board = HeartGameStore.board(context, id)
            val rows = listOf(own) + board.shuffled().take(4).map { it.record }
            val envelope = HeartGameWire.PREFIX + rows.joinToString("\n")
            val targets = linkedMapOf<String, String>()
            for (contact in contacts.allIds()) {
                val node = RankWire.canonicalNodeId(contact) ?: continue
                val chat = chats.getChatByContactId(contact)?.id ?: continue
                targets[node] = chat
            }
            for (group in groups.getGroups() + groups.getChannels()) {
                for (member in groups.getMembers(group.id)) {
                    if (!member.isBanned) RankWire.canonicalNodeId(member.nodeId)?.let { targets.putIfAbsent(it, group.id) }
                }
            }
            targets.remove(id)
            // At most 16 packets / five minutes. Random sampling avoids starving large rosters.
            for ((target, chat) in targets.entries.shuffled().take(16)) {
                if (HeartGameStore.ownId() != id || !HeartGameStore.enabled(context, id)) break
                runCatching { RustBridge.sendMessage(UUID.randomUUID().toString(), chat, target, envelope) }
            }
        }
    }
}
