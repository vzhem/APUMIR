package com.vladimir.messenger.service

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.chatclear.ChatClearRegistry
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.mirror.MirrorChannel
import com.vladimir.messenger.data.reply.DirectReplyIngress
import com.vladimir.messenger.data.reply.DirectReplyPinnedApply
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.security.SealedWire

/** Production callbacks for the same receive policy on direct, CF and paired routes. */
internal fun directReplyIngress(
    context: Context,
    repository: ChatRepository,
    pins: FileExchangePeerStore,
    mirror: () -> MirrorChannel?,
): DirectReplyIngress {
    val pinnedApply = DirectReplyPinnedApply(
        bindingFromWire = SealedWire::senderBinding,
        withPin = { sender, action -> pins.withPinnedBinding(sender, action) },
        apply = repository::applyDirectReply,
    )
    return DirectReplyIngress(
        isCleared = { sender, parent -> ChatClearRegistry.isCleared(context, sender, parent) },
        apply = repository::applyDirectReply,
        applyNetwork = pinnedApply::apply,
        forwardVerified = { sender, chat, id, text ->
            mirror()?.publishEnvelope(sender, chat, id, text, replySourceAuthenticated = true)
        },
        acknowledge = { id, sender -> RustBridge.sendDeliveryAck(id, sender); Unit },
        recordFailure = { stage, error -> Log.w("DirectReplyIngress", "$stage failed: ${error.javaClass.simpleName}") },
    )
}
