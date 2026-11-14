package com.vladimir.messenger.data.repository

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.chatclear.ChatClearRegistry
import com.vladimir.messenger.data.local.dao.ChatDao

/** Production tombstone lookup is identity-scoped and fails closed on storage errors. */
internal fun roomAutomaticOutboxSender(
    context: Context,
    chats: ChatDao,
    send: suspend (String, String, String, String) -> Boolean,
): AutomaticOutboxSender = AutomaticOutboxSender(
    chatContact = { chats.getChatById(it)?.contactId },
    isCleared = { peer, id -> ChatClearRegistry.isCleared(context, peer, id) },
    transport = { messageId, chatId, peerId, content ->
        val sent = send(messageId, chatId, peerId, content)
        // Best-effort epoch announcement for this correspondent; deliberately
        // not part of `sent` and never able to change a message status.
        com.vladimir.messenger.data.push.PushEpochPublish.noteCorrespondentIfEnabled(context, peerId)
        sent
    },
    recordFailure = { error -> Log.w("AutomaticOutboxSender", "Clear preflight failed: ${error.javaClass.simpleName}") },
)
