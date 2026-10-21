package com.vladimir.messenger.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Группа. Ровно одна строка на группу, в которой участвует этот телефон.
 *
 * `isPublic` — выбор владельца при создании: публичная принимает по ссылке
 * без одобрения, частная требует подтверждения администратора.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val title: String,
    val about: String = "",
    val ownerId: String,
    val ownerName: String = "",
    val isPublic: Boolean = false,
    val topicsEnabled: Boolean = true,
    /**
     * Канал, а не группа: посты пишут администраторы, обсуждение - в
     * комментариях под постом. Хранится в той же таблице groups.
     */
    val isChannel: Boolean = false,
    val createdAtMs: Long = 0L,
    val memberCount: Int = 1,
    val inviteSlug: String = "",
    /**
     * Права обычных участников на уровне всей группы (маска GroupPermissions.Member).
     * Отдельная маска каждого администратора хранится в group_members.permissions.
     */
    val memberPermissions: Long = 0L,
    /** Телефон вышел из группы: строка хранится, чтобы не потерять историю. */
    val isLeft: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageAtMs: Long? = null,
    val unreadCount: Int = 0,
    /** Pin timestamp in the mixed home inbox; null means unpinned. */
    val pinnedAtMs: Long? = null,
    /** р249: группа или канал убраны в архив (строка цела, в общем списке не видна). */
    @ColumnInfo(defaultValue = "0")
    val archived: Boolean = false,
    /** р249: звук выключен до этого времени (0 - звук включён). */
    @ColumnInfo(defaultValue = "0")
    val mutedUntilMs: Long = 0L,
    /**
     * р250: медленный режим - сколько секунд участник ждёт между своими
     * сообщениями в группе (0 - режим выключен). Касается всех тем сразу:
     * иначе режим обходили, переходя из темы в тему. Владельцу и
     * администраторам режим не мешает - см. GroupRepository.sendMessage.
     */
    @ColumnInfo(defaultValue = "0")
    val slowModeSeconds: Int = 0,
    @ColumnInfo(defaultValue = "''")
    val quietHours: String = "",
    @ColumnInfo(defaultValue = "1")
    val memberListVisible: Boolean = true,
    @ColumnInfo(defaultValue = "''")
    val wallpaper: String = "",
    @ColumnInfo(defaultValue = "''")
    val wallpaperRevision: String = "",
    /**
     * SOS: красная тревожная кнопка в группе. [sosEnabled] - кнопку видно
     * (или скрыта настройкой), [sosMessageCount] - сколько тревожных
     * сообщений уходит за одно нажатие (3..5), [sosPressRole] - кто вправе
     * нажимать (GroupSos.PRESS_*). Все три - общие настройки группы: едут
     * конвертом `soscfg`, как медленный режим.
     */
    @ColumnInfo(defaultValue = "1")
    val sosEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "3")
    val sosMessageCount: Int = 3,
    @ColumnInfo(defaultValue = "'ALL'")
    val sosPressRole: String = "ALL",
    /**
     * Когда ЭТОТ телефон последний раз нажал SOS в группе (защита от
     * повторного нажатия, не меньше 10 секунд). Локальная отметка: по сети
     * не едет и в резервную копию настроек группы не входит.
     */
    @ColumnInfo(defaultValue = "0")
    val sosLastSentAtMs: Long = 0L,
)
