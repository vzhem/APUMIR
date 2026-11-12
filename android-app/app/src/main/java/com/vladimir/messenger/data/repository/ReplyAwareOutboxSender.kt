package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.reply.DirectReplyWire
import com.vladimir.messenger.util.NodeIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Opportunistically offer the saved direct reply again when its parent text is
 * retried. The parent's transport result remains authoritative for the parent;
 * a failed reply attempt never turns a successful text send into a failure.
 *
 * A false body result can mean offline retention, so it also offers the reply;
 * the receiver can see the sidecar first and does not durably buffer it. This
 * is not a durable sidecar outbox or an authenticated sidecar receipt: retries
 * stop when the parent leaves the body retry scans. A clear, deletion, identity
 * change or ACK after the checks cannot revoke an in-flight packet.
 */
internal class ReplyAwareOutboxSender(
    private val sendBody: suspend (String, String, String, String) -> Boolean?,
    private val sendManualBody: suspend (String, String, String, String) -> Boolean?,
    private val find: suspend (String) -> MessageEntity?,
    private val personalScope: suspend (MessageEntity, String) -> Boolean,
    private val sendReply: suspend (String, String, String, DirectReplyWire.Target) -> Boolean,
    private val recordFailure: (Exception) -> Unit,
) {
    private val gate = Any()
    private val repliesInFlight = mutableSetOf<String>()

    suspend fun send(id: String, chat: String, peer: String, body: String): Boolean? =
        sendAndOffer(sendBody, id, chat, peer, body)

    suspend fun sendManual(id: String, chat: String, peer: String, body: String): Boolean? =
        sendAndOffer(sendManualBody, id, chat, peer, body)

    private suspend fun sendAndOffer(
        bodySender: suspend (String, String, String, String) -> Boolean?,
        id: String,
        chat: String,
        peer: String,
        body: String,
    ): Boolean? {
        val result = bodySender(id, chat, peer, body) ?: return null
        currentCoroutineContext().ensureActive()
        if (!begin(id)) return result
        try {
            val row = find(id) ?: return result
            val targetId = row.replyToId?.takeIf { it.isNotBlank() } ?: return result
            if (id.isBlank() || chat.isBlank() || row.id != id || row.chatId != chat || !isDirectReplyTextRow(row) ||
                '\u0000' in id || '|' in id || id.toByteArray(Charsets.UTF_8).size > 128 ||
                !NodeIds.isNodeId(peer) || !personalScope(row, peer)
            ) return result
            currentCoroutineContext().ensureActive()
            // sendReply re-reads the row and checks the original recipient and
            // parent tombstone again; it does not hold a lock across the network.
            sendReply(id, chat, peer, DirectReplyWire.Target(targetId, row.replyAuthor, row.replyText))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(error)
        } finally {
            synchronized(gate) { repliesInFlight.remove(id) }
        }
        return result
    }

    private fun begin(id: String): Boolean = synchronized(gate) {
        if (repliesInFlight.size >= MAX_IN_FLIGHT || id in repliesInFlight) false
        else repliesInFlight.add(id)
    }

    private companion object {
        const val MAX_IN_FLIGHT = 64
    }
}

internal fun roomReplyAwareOutboxSender(
    messages: MessageDao,
    chats: ChatDao,
    groups: GroupDao,
    automatic: AutomaticOutboxSender,
    manual: AutomaticOutboxSender,
    sidecar: OutgoingReplySidecar,
): ReplyAwareOutboxSender = ReplyAwareOutboxSender(
    sendBody = automatic::send,
    sendManualBody = manual::send,
    find = messages::getMessageById,
    personalScope = { row, peer ->
        val contact = chats.getChatById(row.chatId)?.contactId
        contact != null && OutboxRecipient.normalize(contact) == peer &&
            OutboxRecipient.forMessage(row, contact) == peer && groups.getGroupById(row.chatId) == null
    },
    sendReply = sidecar::send,
    recordFailure = { error -> Log.w("ReplyAwareOutbox", "Reply retry failed: ${error.javaClass.simpleName}") },
)
