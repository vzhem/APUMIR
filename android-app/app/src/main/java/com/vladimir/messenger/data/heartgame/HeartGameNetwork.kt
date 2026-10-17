package com.vladimir.messenger.data.heartgame

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.vladimir.messenger.data.diagnostics.RuntimeDiagnostics
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Рассылка подписанных записей сердечек. Синхронизация не зависит от того, включена ли игра:
 * таблица лидеров должна сходиться у всех. Частоту и «свободность телефона» решает [HeartSyncPolicy].
 * Периодический вызов [broadcastIfNeeded] ведёт CoreServerService; [syncWithNewcomer] — при появлении контакта в сети.
 */
@Singleton
class HeartGameNetwork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactDao,
    private val chats: ChatDao,
    private val groups: GroupDao,
) {
    private val sendLock = Mutex()
    private val receiveLock = Mutex()
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

    /** Общая рассылка раз в [HeartSyncPolicy.SYNC_INTERVAL_MS] всем контактам и участникам групп. */
    suspend fun broadcastIfNeeded(nowMs: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        sendLock.withLock {
            if (!HeartSyncPolicy.isDue(HeartSyncPrefs.lastGlobal(context), nowMs)) return@withLock 0
            if (!canSendNow()) return@withLock 0
            val id = HeartGameStore.ownId() ?: return@withLock 0
            val envelope = buildEnvelope(id, nowMs) ?: return@withLock 0
            // Отметку ставим до отправки и при нуле доставок: офлайн-очередь ядра сама доставит позже.
            HeartSyncPrefs.markGlobal(context, nowMs)
            val targets = allTargets(id).entries.shuffled().take(MAX_TARGETS_PER_ROUND)
            deliver(id, envelope, targets.map { it.key to it.value })
        }
    }

    /** Точечная отправка новенькому, который только что появился в сети. Антиспам — в [HeartSyncPolicy]. */
    suspend fun syncWithNewcomer(contactId: String, nowMs: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            sendLock.withLock {
                val peerLast = HeartSyncPrefs.lastPeer(context, contactId)
                val lastNewcomer = HeartSyncPrefs.lastNewcomer(context)
                if (!HeartSyncPolicy.newcomerAllowed(peerLast, lastNewcomer, nowMs)) return@withLock 0
                if (!canSendNow()) return@withLock 0
                val id = HeartGameStore.ownId() ?: return@withLock 0
                val node = RankWire.canonicalNodeId(contactId) ?: return@withLock 0
                if (node == id) return@withLock 0
                val chat = chats.getChatByContactId(contactId)?.id ?: return@withLock 0
                val envelope = buildEnvelope(id, nowMs) ?: return@withLock 0
                HeartSyncPrefs.markNewcomer(context, nowMs)
                HeartSyncPrefs.markPeer(context, contactId, nowMs)
                deliver(id, envelope, listOf(node to chat))
            }
        }

    /** Ядро поднято, есть подпись, и телефон не занят другой работой. */
    private fun canSendNow(): Boolean {
        if (!com.vladimir.messenger.service.CoreStatus.ready.value) return false
        if (IdentitySigningKeyStore.existingVerifiedBinding(context) == null) return false
        val busy = runCatching {
            RuntimeDiagnostics.snapshot(context).operations.associate { it.operation to it.inFlight }
        }.getOrNull() ?: return false
        val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
        val free = HeartSyncPolicy.isPhoneFree(powerSave, HeartSyncPolicy.busyInFlight(busy))
        if (!free) Log.i(TAG, "heart sync postponed: phone busy or power save")
        return free
    }

    private suspend fun buildEnvelope(id: String, nowMs: Long): String? {
        val binding = IdentitySigningKeyStore.existingVerifiedBinding(context) ?: return null
        val own = IdentitySigningKeyStore.withSeed(context) { seed ->
            HeartGameWire.sign(binding, seed, HeartGameStore.score(context, id), nowMs,
                UsernameHolder.name.value.orEmpty().take(48))
        }
        // Do not advertise under a stale signing identity after account switching.
        if (HeartGameWire.verify(own, nowMs) { uniffi.p2p_core.verifyIdentitySigningBinding(it) }?.nodeId != id) return null
        val board = HeartGameStore.board(context, id)
        val rows = listOf(own) + board.shuffled().take(4).map { it.record }
        return HeartGameWire.PREFIX + rows.joinToString("\n")
    }

    private suspend fun allTargets(id: String): Map<String, String> {
        val targets = linkedMapOf<String, String>() // узел -> адрес переписки
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
        return targets
    }

    private suspend fun deliver(id: String, envelope: String, targets: List<Pair<String, String>>): Int {
        var sent = 0
        for ((target, chat) in targets) {
            currentCoroutineContext().ensureActive()
            if (HeartGameStore.ownId() != id) break
            if (runCatching { RustBridge.sendMessage(UUID.randomUUID().toString(), chat, target, envelope) }.getOrDefault(false)) sent++
        }
        return sent
    }

    private companion object {
        const val TAG = "HeartSync"
        /** Потолок адресатов за один раунд; при большом списке выборка случайная и со временем покрывает всех. */
        const val MAX_TARGETS_PER_ROUND = 32
    }
}
