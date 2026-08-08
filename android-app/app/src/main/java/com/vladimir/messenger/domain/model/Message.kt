package com.vladimir.messenger.domain.model

data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.PENDING,
    val isFromMe: Boolean = false,
    val replyToId: String? = null,
    val channel: MessageChannel = MessageChannel.UNKNOWN,
)
