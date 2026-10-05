package com.vladimir.messenger.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Опрос в группе, теме или канале (р250).
 *
 * Опрос всегда привязан к сообщению: в группе - к обычному сообщению темы, в
 * канале - к посту (первому сообщению темы). Сам опрос едет отдельным
 * конвертом `APUGRP1|poll`, поэтому старые телефоны показывают текст сообщения
 * без карточки опроса, а не теряют сообщение целиком.
 *
 * Варианты ответа лежат одной колонкой: по одному закодированному варианту
 * через запятую (кодирование - `GroupWire.encode`, чтобы текст с `|` и
 * переносами не ломал ни базу, ни конверт). Число вариантов и их длину
 * ограничивает `GroupWire.MAX_POLL_OPTIONS` / `MAX_POLL_OPTION_CHARS`.
 */
@Entity(
    tableName = "group_polls",
    indices = [
        Index(value = ["groupId"], name = "index_group_polls_groupId"),
        Index(value = ["messageId"], name = "index_group_polls_messageId"),
    ],
)
data class GroupPollEntity(
    @PrimaryKey val pollId: String,
    val groupId: String,
    /** Тема группы; у поста канала - тема, в которой лежит пост. */
    val topicId: String = "",
    /** Сообщение (или пост), под которым показывается карточка опроса. */
    val messageId: String = "",
    val question: String = "",
    /** Варианты ответа, закодированные и разделённые запятой. */
    val optionsCsv: String = "",
    /** Голоса считаются, но кто за что голосовал - не показывается. */
    @ColumnInfo(defaultValue = "0")
    val anonymous: Boolean = false,
    /** Можно отметить несколько вариантов сразу. */
    @ColumnInfo(defaultValue = "0")
    val multiChoice: Boolean = false,
    val creatorId: String = "",
    val createdAtMs: Long = 0L,
    /** Опрос закрыт: голосовать нельзя, итоги видны. */
    @ColumnInfo(defaultValue = "0")
    val closed: Boolean = false,
)
