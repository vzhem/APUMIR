package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Суточная статистика группы для администраторов: сколько сообщений и сколько
 * разных отправителей в группе и в конкретной теме за день.
 * `topicId == ""` означает строку по группе целиком.
 */
@Entity(
    tableName = "group_message_stats",
    primaryKeys = ["groupId", "topicId", "dayKey"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class GroupMessageStatEntity(
    val groupId: String,
    val topicId: String,
    /** Формат yyyy-MM-dd в UTC. */
    val dayKey: String,
    val messageCount: Int = 0,
    val senderCount: Int = 0,
    val sendersCsv: String = "",
)
