package com.vladimir.messenger.data.repository

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.chatclear.ChatClearRegistry
import com.vladimir.messenger.data.local.dao.ChatDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // The missing direction: ask the service to wake this correspondent, so a
        // sleeping phone learns to look in its mailbox. Only when this attempt
        // did not get through - a message that was just handed over needs no
        // nudge, and the whole point is the peer that cannot be reached.
        //
        // Detached on purpose: the send has already happened, and a wake must
        // never delay it, change it, or fail it. Bounded per peer by a local
        // budget; a no-op in a build whose push gate is closed.
        if (!sent) {
            wakeDispatcher.launch {
                try {
                    com.vladimir.messenger.data.push.PushWakePublish.wake(context, peerId)
                } catch (_: Exception) {
                    Log.w("AutomaticOutboxSender", "Wake attempt skipped")
                }
            }
        }
        sent
    },
    recordFailure = { error -> Log.w("AutomaticOutboxSender", "Clear preflight failed: ${error.javaClass.simpleName}") },
)

/**
 * A process-lifetime scope for best-effort wake attempts. Not a message pipeline:
 * nothing here is retried, and nothing awaits it.
 */
private val wakeDispatcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
