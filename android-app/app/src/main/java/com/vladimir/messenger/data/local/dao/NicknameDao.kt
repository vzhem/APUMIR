package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.NicknameEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NicknameDao {
    @Query("SELECT * FROM nicknames")
    fun observeAll(): Flow<List<NicknameEntity>>

    @Query("SELECT * FROM nicknames WHERE name = :name")
    suspend fun byName(name: String): List<NicknameEntity>

    /** Поиск по части @никнейма: кто зарегистрировался раньше - выше. */
    @Query("SELECT * FROM nicknames WHERE name LIKE '%' || :q || '%' ORDER BY registeredAtMs ASC LIMIT 20")
    suspend fun search(q: String): List<NicknameEntity>

    @Query("SELECT * FROM nicknames WHERE ownerId = :ownerId")
    suspend fun byOwner(ownerId: String): NicknameEntity?

    /** Кто вообще зарегистрировал @имя: для яруса «проверенные» в рое. */
    @Query("SELECT ownerId FROM nicknames")
    suspend fun allOwnerIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: NicknameEntity)

    @Query("DELETE FROM nicknames WHERE ownerId = :ownerId")
    suspend fun deleteByOwner(ownerId: String)
}
