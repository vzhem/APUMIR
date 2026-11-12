package com.vladimir.messenger.service

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.chatclear.ChatClearRegistry
import com.vladimir.messenger.data.mirror.MirrorChannel
import com.vladimir.messenger.data.reply.DirectReplyIngress
import com.vladimir.messenger.data.repository.ChatRepository

/** Production callbacks for the same receive policy on direct, CF and paired routes. */
internal fun directReplyIngress(
    context: Context,
    repository: ChatRepository,
    mirror: () -> MirrorChannel?,
): DirectReplyIngress = DirectReplyIngress(
    isCleared = { sender, parent -> ChatClearRegistry.isCleared(context, sender, parent) },
    apply = repository::applyDirectReply,
    forwardVerified = { sender, chat, id, text ->
        mirror()?.publishEnvelope(sender, chat, id, text, replySourceAuthenticated = true)
    },
    acknowledge = { id, sender -> RustBridge.sendDeliveryAck(id, sender); Unit },
    recordFailure = { stage, error -> Log.w("DirectReplyIngress", "$stage failed: ${error.javaClass.simpleName}") },
)
