package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.reply.DirectReplyWire
import kotlinx.coroutines.CancellationException

/**
 * A reply's wire metadata has its own transport ID, but belongs to the parent
 * message's chat/recipient and clear scope. This is a preflight, not a network
 * barrier: a later clear, delete or identity change cannot revoke an in-flight
 * sidecar or an already retained/transmitted copy.
 */
internal class OutgoingReplySidecar(
    private val find: suspend (String) -> MessageEntity?,
    private val preflight: suspend (String, String, String) -> Boolean,
    private val transport: suspend (String, String, String, String) -> Boolean,
    private val recordFailure: (Exception) -> Unit,
) {
    suspend fun send(messageId: String, chatId: String, peer: String, reply: DirectReplyWire.Target): Boolean {
        try {
            val recipient = OutboxRecipient.normalize(peer).takeIf { it.isNotBlank() } ?: return false
            val row = find(messageId) ?: return false
            if (row.id != messageId || !row.isFromMe || row.senderId != "self" || row.chatId != chatId ||
                OutboxRecipient.forMessage(row, peer) != recipient || row.replyToId != reply.messageId ||
                row.replyAuthor != reply.author || row.replyText != DirectReplyWire.preview(reply.text)
            ) return false
            if (!preflight(messageId, chatId, peer)) return false
            return transport(
                DirectReplyWire.transportMessageId(messageId), chatId, peer,
                DirectReplyWire.build(messageId, reply.messageId, reply.author, reply.text),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(error)
            return false
        }
    }
}

internal fun roomOutgoingReplySidecar(
    messages: MessageDao,
    guard: AutomaticOutboxSender,
    transport: suspend (String, String, String, String) -> Boolean,
): OutgoingReplySidecar = OutgoingReplySidecar(messages::getMessageById, guard::preflight, transport) { error ->
    Log.w("OutgoingReply", "Reply metadata send failed: ${error.javaClass.simpleName}")
}
