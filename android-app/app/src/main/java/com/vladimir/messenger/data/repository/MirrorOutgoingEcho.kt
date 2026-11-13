package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorHub

/** A paired device can receive a status again without re-sending the message. */
internal class MirrorOutgoingEcho(
    private val find: suspend (String) -> MessageEntity?,
    private val preflight: suspend (String, String, String) -> Boolean,
    private val emit: (MessageEntity, String) -> Unit,
) {
    suspend fun publish(messageId: String, chatId: String, peer: String) {
        val row = find(messageId) ?: return
        if (row.id != messageId || !row.isFromMe || row.chatId != chatId || peer.isBlank() ||
            OutboxRecipient.forMessage(row, peer) != peer || !preflight(messageId, chatId, peer)
        ) return
        emit(row, peer)
    }
}

internal fun roomMirrorOutgoingEcho(messages: MessageDao, guard: AutomaticOutboxSender): MirrorOutgoingEcho =
    MirrorOutgoingEcho(messages::getMessageById, guard::preflight) { row, peer ->
        MirrorHub.publishSentEcho(
            id = row.id,
            chatId = row.chatId,
            content = row.content,
            ts = row.timestamp,
            recipientId = peer,
            status = row.status,
            replyToId = row.replyToId.orEmpty(),
            replyAuthor = row.replyAuthor,
            replyText = row.replyText,
            textColor = row.textColor,
            senderNameColor = row.senderNameColor,
        )
    }
