package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.domain.model.Message

/** Resolve self-chat identity from cached local data without blocking chat startup. */
internal fun isSelfChat(messages: List<Message>, peer: String): Boolean {
    val selfNode = com.vladimir.messenger.data.mirror.MirrorHub.nodeIdCached().ifBlank {
        messages.firstNotNullOfOrNull { message ->
            if (message.isFromMe) null else message.recipientId.takeIf { it.startsWith("pk_") }
        }.orEmpty()
    }
    return selfNode.isNotBlank() && peer == selfNode
}
