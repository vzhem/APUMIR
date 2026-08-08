package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val status: String = "PENDING",
    val isFromMe: Boolean = false,
    val replyToId: String? = null,
    val channel: String = "UNKNOWN",
    val recipientId: String = "",
)
