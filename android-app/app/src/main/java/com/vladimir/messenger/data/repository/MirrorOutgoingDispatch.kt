package com.vladimir.messenger.data.repository

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.chatclear.ChatClearRegistry
import com.vladimir.messenger.data.mirror.MirrorRow
import com.vladimir.messenger.data.reply.DirectReplyWire
import com.vladimir.messenger.util.NodeIds
import kotlinx.coroutines.CancellationException

/** Local scope and clear checks do not authenticate the paired-frame source. */
internal class MirrorOutgoingDispatcher(
    private val isCleared: (String, String) -> Boolean,
    private val localChat: suspend (String) -> String,
    private val initial: suspend (MirrorRow, String) -> Unit,
    private val manual: suspend (MirrorRow, String) -> Unit,
    private val recordFailure: (Exception) -> Unit,
) {
    suspend fun dispatch(row: MirrorRow) {
        try {
            if (!MirrorOutgoingPolicy.accepts(row) || !mirrorPeerPermitted(row.recipientId, row.id, isCleared)) return
            val chatId = localChat(row.recipientId).takeIf { it.isNotBlank() } ?: return
            if (row.manualRetry) manual(row, chatId) else initial(row, chatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The frame may be redelivered; do not treat a failed check as permission.
            recordFailure(error)
        }
    }
}

internal suspend fun dispatchMirrorOutgoing(context: Context, repository: ChatRepository, row: MirrorRow) {
    MirrorOutgoingDispatcher(
        isCleared = { peer, id -> ChatClearRegistry.isCleared(context, peer, id) },
        localChat = { peer ->
            (repository.getChatByContactId(peer) ?: repository.getOrCreateChat(peer, NodeIds.autoName(peer))).id
        },
        initial = { frame, chatId ->
            repository.sendMessage(
                chatId = chatId, recipientId = frame.recipientId, content = frame.content,
                fixedMessageId = frame.id, fromMirror = true,
                mirrorColors = frame.textColor to frame.senderNameColor,
                reply = frame.replyToId.takeIf { it.isNotBlank() }?.let {
                    DirectReplyWire.Target(it, frame.replyAuthor, frame.replyText)
                },
            )
        },
        manual = { frame, chatId -> repository.retryOutgoingFromMirror(frame, chatId) },
        recordFailure = { error -> Log.w("MirrorOutgoing", "Paired outgoing frame deferred: ${error.javaClass.simpleName}") },
    ).dispatch(row)
}
