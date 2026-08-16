package com.vladimir.messenger.domain.model

data class Chat(
    val id: String,
    val contactId: String = "",
    val contactName: String,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val isContactOnline: Boolean = false,
)
