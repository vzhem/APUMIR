package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.domain.model.MessageStatus

/** Room adapter for the bounded discovery path; re-reads every selected row. */
internal fun roomPeerOutboxRetry(
    messages: MessageDao,
    chats: ChatDao,
    send: suspend (String, String, String, String) -> Boolean?,
    recordFailure: () -> Unit = { Log.w("PeerOutboxRetry", "Peer outbox retry failed") },
): PeerOutboxRetry = PeerOutboxRetry(
    loadPending = { peer, after, limit ->
        if (after == null) messages.getRetryableOutgoingForPeer(peer, limit)
        else messages.getRetryableOutgoingForPeerAfter(peer, after.timestamp, after.id, limit)
    },
    loadRecent = { peer, before, limit ->
        chats.getChatByContactId(peer)?.let { chat ->
            if (before == null) messages.getUnconfirmedOutgoingMessages(chat.id, peer, limit)
            else messages.getUnconfirmedOutgoingMessagesBefore(chat.id, peer, before.timestamp, before.id, limit)
        } ?: emptyList()
    },
    loadCurrent = messages::getMessageById,
    chatContact = { chats.getChatById(it)?.contactId },
    send = send,
    markSent = { id -> messages.updateOutgoingRetryResult(id, MessageStatus.SENT.name, MessageChannel.LOCAL.name); Unit },
    recordFailure = recordFailure,
)
