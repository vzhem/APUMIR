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
    private val loadPending: suspend (String, OutboxPosition?, Int) -> List<MessageEntity>,
    private val loadRecent: suspend (String, OutboxPosition?, Int) -> List<MessageEntity>,
    private val loadCurrent: suspend (String) -> MessageEntity?,
    private val chatContact: suspend (String) -> String?,
    private val send: suspend (String, String, String, String) -> Boolean?,
    private val markSent: suspend (String) -> Unit,
    private val recordFailure: () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val gate = Any()
    private val inFlight = mutableSetOf<String>()
    private val states = LinkedHashMap<String, RetryState>(16, 0.75f, true)

    // One peer cannot run concurrently. State is accessed by that invocation
    // only and published through gate on entry/exit; idle peers are LRU-bounded.
    private class RetryState(
        var pending: OutboxPosition? = null,
        var recent: OutboxPosition? = null,
        var lastSync: Long? = null,
    )

    suspend fun retry(peerId: String): Int {
        if (!peerId.startsWith("pk_") || '\u0000' in peerId) return 0
        val state = begin(peerId) ?: return 0
        try {
            var sent = 0
            // Bound rows inspected, not only network calls. The cursors rotate
            // on later discoveries so persistent tombstones cannot occupy the
            // same first window forever. They are hints, not durable state.
            var visited = 0
            val tried = HashSet<String>()
            var pending = load { loadPending(peerId, state.pending, PENDING_LIMIT) }?.take(PENDING_LIMIT)
            if (pending != null && pending.isEmpty() && state.pending != null) {
                state.pending = null
                pending = load { loadPending(peerId, null, PENDING_LIMIT) }?.take(PENDING_LIMIT)
            }
            if (pending != null) {
                for (row in pending) {
                    if (tried.add(row.id)) {
                        visited++
                        if (attempt(row, peerId, pendingOnly = true)) sent++
                    }
                    // Do not advance past a cancelled or still-running attempt.
                    state.pending = OutboxPosition(row.timestamp, row.id)
                }
                if (pending.size < PENDING_LIMIT) state.pending = null
            }

            if (syncDue(state)) {
                var recent = load { loadRecent(peerId, state.recent, RECENT_LIMIT) }?.take(RECENT_LIMIT)
                    ?: return sent
                if (recent.isEmpty() && state.recent != null) {
                    state.recent = null
                    recent = load { loadRecent(peerId, null, RECENT_LIMIT) }?.take(RECENT_LIMIT)
                        ?: return sent
                }
                var consumed = 0
                for (row in recent) {
                    if (visited >= TOTAL_LIMIT) break
                    if (tried.add(row.id)) {
                        visited++
                        if (attempt(row, peerId, pendingOnly = false)) sent++
                    }
                    state.recent = OutboxPosition(row.timestamp, row.id)
                    consumed++
                }
                if (consumed == recent.size && recent.size < RECENT_LIMIT) state.recent = null
                // Only a completed invocation starts the cooldown: cancellation
                // or a failed database read must not suppress the next opportunity.
                state.lastSync = nowMs()
            }
            return sent
        } finally {
            synchronized(gate) { inFlight.remove(peerId) }
        }
    }

    private suspend fun load(block: suspend () -> List<MessageEntity>): List<MessageEntity>? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
        null
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

    private fun begin(peerId: String): RetryState? = synchronized(gate) {
        if (inFlight.size >= MAX_IN_FLIGHT || peerId in inFlight) return@synchronized null
        inFlight.add(peerId)
        val state = states.getOrPut(peerId) { RetryState() }
        while (states.size > MAX_TRACKED_PEERS) {
            val victim = states.keys.firstOrNull { it !in inFlight } ?: break
            states.remove(victim)
        }
        state
    }

    private fun syncDue(state: RetryState): Boolean {
        val last = state.lastSync
        val now = nowMs()
        return last == null || now < last || now - last >= FULL_SYNC_COOLDOWN_MS
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
