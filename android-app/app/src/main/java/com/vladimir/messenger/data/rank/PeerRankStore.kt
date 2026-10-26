package com.vladimir.messenger.data.rank

import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.local.dao.PeerRankDao
import com.vladimir.messenger.data.local.entity.PeerRankEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Что телефон знает о рангах собеседников.
 *
 * Раньше это была отметка «что-то пришло, перечитайте базу». Теперь источник —
 * таблица `peer_ranks`, а экраны подписаны на поток узлов-элиты: знак VIP у
 * имени и золотое кольцо вокруг аватарки обновляются сами, как только приходит
 * конверт `APURANK1` — в личке, в группе или в канале.
 *
 * Узлы сравниваются в нижнем регистре: идентификаторы приходят то так, то так, а
 * для показа это один и тот же человек.
 */
@Singleton
class PeerRankStore @Inject constructor(
    private val dao: PeerRankDao,
) {

    /**
     * Узлы, чей сообщённый ранг-эквивалент дотягивает до VIP. Пустое множество — никто ещё
     * ранг не сообщал: тогда ни знаков, ни кольца ни у кого нет.
     */
    val vipNodeIds: Flow<Set<String>> = dao
        .observeVipNodeIds(FileTransferRankPolicy.vipMinimumReferrals)
        .map { ids -> ids.mapTo(HashSet()) { it.lowercase() } }
        .distinctUntilChanged()

    /** Известный ранг-эквивалент узла или null, если он ещё не сообщал. */
    suspend fun qualifiedFor(nodeId: String): Int? =
        dao.qualifiedFor(nodeId.trim().lowercase())

    /** VIP ли этот узел по максимальному известному рангу. */
    suspend fun isVip(nodeId: String): Boolean =
        isVipRank(qualifiedFor(nodeId))

    /**
     * Запомнить ранг, пришедший конвертом. true — запись изменилась (узел новый,
     * пришло более свежее время или вырос ранг), false — пакет полностью старее.
     * DAO сохраняет максимум qualified, поэтому VIP нельзя случайно откатить.
     */
    suspend fun remember(nodeId: String, qualified: Int, updatedAtMs: Long): Boolean {
        val id = nodeId.trim().lowercase()
        if (id.isBlank() || qualified < 0 || updatedAtMs <= 0) return false
        val updated = dao.updateIfNewer(id, qualified, updatedAtMs)
        if (updated > 0) return true
        return dao.insertIfAbsent(PeerRankEntity(id, qualified, updatedAtMs)) > 0
    }

    companion object {

        /**
         * VIP по числу приглашений. Порог берётся у политики рангов: десятка
         * («Проводник») — уже элита (решение владельца 2026-10-07).
         */
        fun isVipRank(qualified: Int?): Boolean =
            (qualified ?: -1) >= FileTransferRankPolicy.vipMinimumReferrals
    }
}
