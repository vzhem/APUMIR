package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageStatus

/** Shared row mapping for bounded history readers and ChatRepository. */
internal fun MessageEntity.toDomainMessage() = Message(
    id = id,
    chatId = chatId,
    senderId = senderId,
    content = content,
    timestamp = timestamp,
    isFromMe = isFromMe,
    status = try { MessageStatus.valueOf(status) } catch (_: IllegalArgumentException) { MessageStatus.PENDING },
    // Self-chat detection uses the LOCAL recipient; no JNI lookup on opening.
    recipientId = recipientId,
    topicId = topicId,
    isPinned = isPinned,
    editedAtMs = editedAtMs,
    replyToId = replyToId,
    replyAuthor = replyAuthor,
    replyText = replyText,
    textColor = textColor,
    senderNameColor = senderNameColor,
)
