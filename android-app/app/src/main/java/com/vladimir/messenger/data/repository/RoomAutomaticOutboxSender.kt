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
        sent
    },
    recordFailure = { error -> Log.w("AutomaticOutboxSender", "Clear preflight failed: ${error.javaClass.simpleName}") },
)

/**
 * Best-effort wake of a correspondent whose phone may be asleep.
 *
 * Called from the ordinary personal-message send path after the ordinary attempt,
 * and only when that attempt did not get through: a message the direct transport
 * just accepted needs no nudge, and the whole point is the peer that cannot be
 * reached. Detached on purpose - the send has already happened by then, and a
 * wake must never delay it, change its status, or fail it.
 *
 * A no-op in a build whose push gate is closed (every non-test build), and bounded
 * per peer by a local budget so a conversation cannot become one provider call
 * per message.
 */
internal fun wakeCorrespondentIfUnreachable(context: Context, peerId: String, sent: Boolean) {
    if (sent || peerId.isBlank()) return
    wakeDispatcher.launch {
        try {
            com.vladimir.messenger.data.push.PushWakePublish.wake(context, peerId)
        } catch (_: Exception) {
            Log.w("AutomaticOutboxSender", "Wake attempt skipped")
        }
    }
}

/**
 * A process-lifetime scope for best-effort wake attempts. Not a message pipeline:
 * nothing here is retried, and nothing awaits it.
 */
private val wakeDispatcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
