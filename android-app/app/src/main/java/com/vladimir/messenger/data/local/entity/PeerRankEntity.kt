package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ранг собеседника, который он сам сообщил конвертом `APURANK1`.
 *
 * Одна таблица на ВСЕ узлы, о ранге которых телефон знает: не только на
 * контакты, но и на участников групп и авторов постов каналов. Раньше ранг
 * лежал колонкой в `contacts`, и знак VIP не получался у того, кого нет в
 * адресной книге, — а «видно везде» как раз этого и требует (решение владельца
 * 2026-10-07). Колонки в `contacts` остались ради совместимости схемы и больше
 * не пишутся: источник истины один — эта таблица.
 *
 * `qualified` — число подтверждённых приглашений на стороне собеседника.
 * `updatedAtMs` — когда он это сообщил: запись идёт только «вперёд», поэтому
 * опоздавший старый пакет не откатывает знак VIP назад.
 */
@Entity(tableName = "peer_ranks")
data class PeerRankEntity(
    @PrimaryKey val nodeId: String,
    val qualified: Int,
    val updatedAtMs: Long,
)
