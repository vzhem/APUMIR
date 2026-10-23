package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.NicknameEntity
import com.vladimir.messenger.data.local.entity.NicknameSuppressionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NicknameDao {
    /** Visible registry only: revoked owner/name pairs stay in the ledger. */
    @Query(
        "SELECT n.* FROM nicknames AS n " +
            "WHERE NOT EXISTS (" +
            "SELECT 1 FROM nickname_suppressions AS s " +
            "WHERE s.ownerId = n.ownerId AND s.name = n.name COLLATE NOCASE" +
            ")"
    )
    fun observeAll(): Flow<List<NicknameEntity>>

    /** Name ownership checks must not count a locally revoked announcement. */
    @Query(
        "SELECT n.* FROM nicknames AS n " +
            "WHERE n.name = :name COLLATE NOCASE AND NOT EXISTS (" +
            "SELECT 1 FROM nickname_suppressions AS s " +
            "WHERE s.ownerId = n.ownerId AND s.name = n.name COLLATE NOCASE" +
            ") " +
            "ORDER BY CASE WHEN n.registeredAtMs > 0 THEN 0 ELSE 1 END, " +
            "n.registeredAtMs ASC, n.ownerId ASC"
    )
    suspend fun byName(name: String): List<NicknameEntity>

    /**
     * Search by part of a nickname.  Registration time is the primary order;
     * owner id is only a deterministic tie-breaker for equal timestamps.
     */
    @Query(
        "SELECT n.* FROM nicknames AS n " +
            "WHERE n.name LIKE '%' || :q || '%' AND NOT EXISTS (" +
            "SELECT 1 FROM nickname_suppressions AS s " +
            "WHERE s.ownerId = n.ownerId AND s.name = n.name COLLATE NOCASE" +
            ") " +
            "ORDER BY CASE WHEN n.registeredAtMs > 0 THEN 0 ELSE 1 END, " +
            "n.registeredAtMs ASC, n.ownerId ASC LIMIT 50"
    )
    suspend fun search(q: String): List<NicknameEntity>

    @Query(
        "SELECT n.* FROM nicknames AS n " +
            "WHERE n.ownerId = :ownerId AND NOT EXISTS (" +
            "SELECT 1 FROM nickname_suppressions AS s " +
            "WHERE s.ownerId = n.ownerId AND s.name = n.name COLLATE NOCASE" +
            ") LIMIT 1"
    )
    suspend fun byOwner(ownerId: String): NicknameEntity?

    /** Active owners only: old tombstoned addresses must not enter peer routing. */
    @Query(
        "SELECT n.ownerId FROM nicknames AS n " +
            "WHERE NOT EXISTS (" +
            "SELECT 1 FROM nickname_suppressions AS s " +
            "WHERE s.ownerId = n.ownerId AND s.name = n.name COLLATE NOCASE" +
            ")"
    )
    suspend fun allOwnerIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: NicknameEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun suppress(entry: NicknameSuppressionEntity)

    @Query("DELETE FROM nickname_suppressions WHERE ownerId = :ownerId AND name = :name COLLATE NOCASE")
    suspend fun unsuppress(ownerId: String, name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM nickname_suppressions WHERE ownerId = :ownerId AND name = :name COLLATE NOCASE)")
    suspend fun isSuppressed(ownerId: String, name: String): Boolean

    @Query("DELETE FROM nicknames WHERE ownerId = :ownerId")
    suspend fun deleteByOwner(ownerId: String)

    @Query("DELETE FROM nicknames WHERE ownerId = :ownerId AND name = :name COLLATE NOCASE")
    suspend fun deleteByOwnerAndName(ownerId: String, name: String)
}
