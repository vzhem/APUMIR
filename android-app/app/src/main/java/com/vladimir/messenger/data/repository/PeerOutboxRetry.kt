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
    private val loadCurrent: suspend (String) -> MessageEntity?,
    private val chatContact: suspend (String) -> String?,
    private val send: suspend (String, String, String, String) -> Boolean?,
    private val markSent: suspend (String) -> Unit,
    private val recordFailure: () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val gate = Any()
    private val inFlight = mutableSetOf<String>()
    private val lastSync = LinkedHashMap<String, Long>(16, 0.75f, true)

    suspend fun retry(peerId: String): Int {
        if (!peerId.startsWith("pk_") || '\u0000' in peerId || !begin(peerId)) return 0
        try {
            var sent = 0
            // This bounds rows inspected, not network calls: stale or cleared
            // rows still consume the discovery path's finite scan budget.
            var visited = 0
            val tried = HashSet<String>()
            val pending = load { loadPending(peerId, PENDING_LIMIT) }.take(PENDING_LIMIT)
            for (row in pending) {
                if (visited >= TOTAL_LIMIT) break
                if (!tried.add(row.id)) continue
                visited++
                if (attempt(row, peerId, pendingOnly = true)) sent++
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
                    if (visited >= TOTAL_LIMIT) break
                    if (!tried.add(row.id)) continue
                    visited++
                    if (attempt(row, peerId, pendingOnly = false)) sent++
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

    private suspend fun attempt(snapshot: MessageEntity, peerId: String, pendingOnly: Boolean): Boolean {
        // Discovery results can contain multiple chats and can sit behind slow
        // network sends. Re-read each row and never substitute the discovered
        // chat contact for a different, nonblank saved recipient.
        val row = try {
            val current = loadCurrent(snapshot.id) ?: return false
            if (!current.isFromMe || current.status !in RETRYABLE ||
                (pendingOnly && current.status == MessageStatus.SENT.name)
            ) return false
            val contact = if (current.recipientId.isBlank()) chatContact(current.chatId) else null
            if (OutboxRecipient.forMessage(current, contact) != peerId) return false
            current
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            return false
        }
        val delivered = try {
            send(row.id, row.chatId, peerId, TextColorWire.wrap(row.content, row.textColor, row.senderNameColor))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            false
        }
        if (delivered == true && row.status != MessageStatus.SENT.name) {
            try {
                // This guarded update cannot downgrade a delivery/read ACK.
                markSent(row.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordFailure()
            }
        }
        return delivered == true
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
        val RETRYABLE = setOf(MessageStatus.PENDING.name, MessageStatus.QUEUED_OFFLINE.name, MessageStatus.SENT.name)
        const val PENDING_LIMIT = 20
        const val RECENT_LIMIT = 50
        const val TOTAL_LIMIT = 50
        const val MAX_IN_FLIGHT = 16
        const val MAX_TRACKED_PEERS = 1024
        const val FULL_SYNC_COOLDOWN_MS = 300_000L
    }
}
