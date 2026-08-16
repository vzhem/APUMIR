package com.vladimir.messenger.domain.model

data class Contact(
    val id: String,
    val displayName: String,
    val fingerprint: String = "",
    val isOnline: Boolean = false,
    val lastSeen: String? = null,
)
