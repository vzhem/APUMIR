package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.textcolor.TextColorWire
import com.vladimir.messenger.domain.model.MessageStatus
import kotlinx.coroutines.CancellationException

/**
 * Bounded discovery-triggered retries for one contact. The regular fair outbox
 * pump handles rows outside this small fast path, including multiple chats for
 * the same contact. Message bodies and peer identifiers must not be logged.
 */
internal class PeerOutboxRetry(
    private val loadPending: suspend (String, Int) -> List<MessageEntity>,
    private val loadRecent: suspend (String, Int) -> List<MessageEntity>,
    private val send: suspend (String, String, String, String) -> Boolean,
    private val markSent: suspend (String) -> Unit,
    private val recordFailure: () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val gate = Any()
    private val inFlight = mutableSetOf<String>()
    private val lastSync = LinkedHashMap<String, Long>(16, 0.75f, true)

    suspend fun retry(peerId: String): Int {
        if (peerId.isBlank() || !begin(peerId)) return 0
        try {
            var sent = 0
            var attempts = 0
            val tried = HashSet<String>()
            val pending = load { loadPending(peerId, PENDING_LIMIT) }.take(PENDING_LIMIT)
            for (row in pending) {
                if (attempts >= TOTAL_LIMIT) break
                if (!tried.add(row.id)) continue
                attempts++
                if (attempt(row, peerId)) sent++
            }

            if (syncDue(peerId)) {
                val recent = try {
                    loadRecent(peerId, RECENT_LIMIT).take(RECENT_LIMIT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recordFailure()
                    return sent
                }
                for (row in recent) {
                    if (attempts >= TOTAL_LIMIT) break
                    if (!tried.add(row.id)) continue
                    attempts++
                    if (attempt(row, peerId)) sent++
                }
                // Only a completed pass starts the cooldown: cancellation or a
                // failed database read must not suppress the next opportunity.
                noteSync(peerId)
            }
            return sent
        } finally {
            synchronized(gate) { inFlight.remove(peerId) }
        }
    }

    private suspend fun load(block: suspend () -> List<MessageEntity>): List<MessageEntity> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
        emptyList()
    }

    private suspend fun attempt(row: MessageEntity, peerId: String): Boolean {
        val delivered = try {
            send(row.id, row.chatId, peerId, TextColorWire.wrap(row.content, row.textColor, row.senderNameColor))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            false
        }
        if (delivered && row.status != MessageStatus.SENT.name) {
            try {
                // This guarded update cannot downgrade a delivery/read ACK.
                markSent(row.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordFailure()
            }
        }
        return delivered
    }

    private fun begin(peerId: String): Boolean = synchronized(gate) {
        if (inFlight.size >= MAX_IN_FLIGHT || peerId in inFlight) false
        else inFlight.add(peerId)
    }

    private fun syncDue(peerId: String): Boolean = synchronized(gate) {
        val last = lastSync[peerId]
        val now = nowMs()
        last == null || now < last || now - last >= FULL_SYNC_COOLDOWN_MS
    }

    private fun noteSync(peerId: String) = synchronized(gate) {
        lastSync[peerId] = nowMs()
        while (lastSync.size > MAX_TRACKED_PEERS) lastSync.remove(lastSync.keys.first())
    }

    private companion object {
        const val PENDING_LIMIT = 20
        const val RECENT_LIMIT = 50
        const val TOTAL_LIMIT = 50
        const val MAX_IN_FLIGHT = 16
        const val MAX_TRACKED_PEERS = 1024
        const val FULL_SYNC_COOLDOWN_MS = 300_000L
    }
}
