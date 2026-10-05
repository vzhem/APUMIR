package com.vladimir.messenger.data.local.entity

import androidx.room.ColumnInfo
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
    /** Pin timestamp in the mixed home inbox; null means unpinned. */
    val pinnedAtMs: Long? = null,
    /**
     * р249: чат убран в архив - он цел, но не мешается в общем списке.
     * Синхронизируется между устройствами одной личности.
     */
    @ColumnInfo(defaultValue = "0")
    val archived: Boolean = false,
    /**
     * р249: звук выключен до этого времени (0 - звук включён). Время, а не
     * просто отметка: «без звука на день» должно само включиться обратно.
     */
    @ColumnInfo(defaultValue = "0")
    val mutedUntilMs: Long = 0L,
)
