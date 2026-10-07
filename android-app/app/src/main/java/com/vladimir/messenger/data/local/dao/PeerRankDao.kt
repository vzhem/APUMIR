package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.PeerRankEntity
import kotlinx.coroutines.flow.Flow

/**
 * Ранги собеседников, сообщённые ими самими (конверт `APURANK1`).
 *
 * Читающая сторона — поток узлов-элиты: по нему рисуются знак VIP у имени и
 * золотое кольцо вокруг аватарки, поэтому экраны не опрашивают базу сами.
 */
@Dao
interface PeerRankDao {

    /**
     * Узлы, чей сообщённый ранг дотягивает до VIP. Порог приходит снаружи от
     * политики рангов, чтобы здесь не жила вторая копия правила.
     */
    @Query("SELECT nodeId FROM peer_ranks WHERE qualified >= :vipThreshold")
    fun observeVipNodeIds(vipThreshold: Int): Flow<List<String>>

    @Query("SELECT qualified FROM peer_ranks WHERE nodeId = :nodeId")
    suspend fun qualifiedFor(nodeId: String): Int?

    /**
     * Обновить ранг, только если он свежее уже записанного. Возвращает число
     * строк: 0 — запись не тронута (пакет опоздал или узла ещё нет).
     */
    @Query(
        "UPDATE peer_ranks SET qualified = :qualified, updatedAtMs = :updatedAtMs " +
            "WHERE nodeId = :nodeId AND updatedAtMs < :updatedAtMs"
    )
    suspend fun updateIfNewer(nodeId: String, qualified: Int, updatedAtMs: Long): Int

    /** Первая запись об узле. IGNORE: гонку с UPDATE решает уникальный ключ. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: PeerRankEntity): Long
}
