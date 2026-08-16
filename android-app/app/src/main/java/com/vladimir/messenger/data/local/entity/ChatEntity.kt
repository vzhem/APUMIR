package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val contactId: String = "",
    val contactName: String,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val isContactOnline: Boolean = false,
)
