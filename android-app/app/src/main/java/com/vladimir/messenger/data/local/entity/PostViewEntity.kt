package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Просмотр поста: кто именно открыл запись канала.
 *
 * Храним не счётчик, а сами просмотры - пару «пост + читатель». Иначе один и
 * тот же просмотр, пришедший дважды (роевая рассылка, повтор после разрыва
 * связи), накручивал бы число, а перечитывание поста добавляло бы просмотр
 * при каждом открытии.
 */
@Entity(
    tableName = "post_views",
    primaryKeys = ["topicId", "viewerId"],
    indices = [Index("topicId")],
)
data class PostViewEntity(
    /** Тема поста: именно она отличает одну запись канала от другой. */
    val topicId: String,
    /** Кто открыл пост. */
    val viewerId: String,
    val atMs: Long,
)
