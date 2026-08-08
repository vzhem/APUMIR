package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val fingerprint: String = "",
    val isOnline: Boolean = false,
    val lastSeen: String? = null,
)
