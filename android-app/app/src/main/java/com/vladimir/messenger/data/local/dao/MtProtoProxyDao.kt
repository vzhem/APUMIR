package com.vladimir.messenger.data.local.dao

import androidx.room.*
import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MtProtoProxyDao {
    @Query("SELECT * FROM mtproto_proxies ORDER BY isActive DESC, successCount DESC, lastCheck DESC")
    fun observeAll(): Flow<List<MtProtoProxyEntity>>

    @Query("SELECT * FROM mtproto_proxies ORDER BY isActive DESC, successCount DESC, lastCheck DESC")
    suspend fun getAll(): List<MtProtoProxyEntity>

    @Query("SELECT * FROM mtproto_proxies WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): MtProtoProxyEntity?

    @Query("SELECT * FROM mtproto_proxies WHERE id = :id")
    suspend fun getById(id: String): MtProtoProxyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(proxy: MtProtoProxyEntity)

    @Update
    suspend fun update(proxy: MtProtoProxyEntity)

    @Query("DELETE FROM mtproto_proxies WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE mtproto_proxies SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE mtproto_proxies SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Query("UPDATE mtproto_proxies SET failCount = failCount + 1, lastCheck = :ts WHERE id = :id")
    suspend fun incFailCount(id: String, ts: Long)

    @Query("UPDATE mtproto_proxies SET successCount = successCount + 1, failCount = 0, lastCheck = :ts WHERE id = :id")
    suspend fun incSuccessCount(id: String, ts: Long)

    /** Удалить прокси, которые не работали N дней и имеют failCount >= 3 */
    @Query("""
        DELETE FROM mtproto_proxies 
        WHERE failCount >= 3 
          AND (:now - lastCheck) > :maxAgeMs
          AND source != 'MANUAL'
    """)
    suspend fun cleanupDead(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int
}
