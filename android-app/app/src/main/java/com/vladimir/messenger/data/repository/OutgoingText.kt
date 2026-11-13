package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.reply.DirectReplyWire
import com.vladimir.messenger.data.textcolor.TextColorPalette
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.domain.model.MessageStatus

/** Use the originating device's palette for paired sends, including its default colours. */
internal fun outgoingTextColors(
    fromMirror: Boolean,
    paired: Pair<String, String>?,
    local: () -> Pair<String, String>,
): Pair<String, String> = if (fromMirror && paired != null) {
    TextColorPalette.normalize(paired.first) to TextColorPalette.normalize(paired.second)
} else local()

/** Both local and paired send paths persist the same reply and presentation fields. */
internal fun outgoingTextRow(
    id: String,
    chatId: String,
    recipientId: String,
    content: String,
    timestamp: Long,
    reply: DirectReplyWire.Target?,
    textColor: String,
    senderNameColor: String,
): MessageEntity = MessageEntity(
    id = id,
    chatId = chatId,
    senderId = "self",
    content = content,
    timestamp = timestamp,
    isFromMe = true,
    status = MessageStatus.PENDING.name,
    channel = MessageChannel.UNKNOWN.name,
    recipientId = recipientId,
    replyToId = reply?.messageId,
    replyAuthor = reply?.author.orEmpty(),
    replyText = reply?.let { DirectReplyWire.preview(it.text) }.orEmpty(),
    textColor = textColor,
    senderNameColor = senderNameColor,
)
