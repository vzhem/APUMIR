package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.ProfileHeartEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileHeartDao {

    /** Повторный голос того же человека молча заменяет прежний, а не удваивает. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(heart: ProfileHeartEntity)

    @Query("DELETE FROM profile_hearts WHERE ownerId = :ownerId AND voterId = :voterId")
    suspend fun remove(ownerId: String, voterId: String)

    @Query("SELECT COUNT(*) FROM profile_hearts WHERE ownerId = :ownerId")
    fun observeCount(ownerId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM profile_hearts WHERE ownerId = :ownerId")
    suspend fun countOf(ownerId: String): Int

    @Query(
        "SELECT COUNT(*) FROM profile_hearts WHERE ownerId = :ownerId AND voterId = :voterId"
    )
    suspend fun hasVote(ownerId: String, voterId: String): Int

    /**
     * Все отметки анти-рейтинга (`ownerId LIKE 'anti|%'`) для ленты и карточек.
     *
     * Отдаём сущности, а счётчики считаем в Kotlin: проекция `COUNT(*)` в свой
     * data class в этом проекте уже один раз роняла сборку (см. AI_HANDOFF).
     */
    @Query("SELECT * FROM profile_hearts WHERE ownerId LIKE 'anti|%' ORDER BY atMs DESC")
    fun observeAllAntiVotes(): Flow<List<ProfileHeartEntity>>

    /** Кому этот узел поставил анти-рейтинг (`ownerId LIKE 'anti|%'`). */
    @Query("SELECT ownerId FROM profile_hearts WHERE voterId = :voterId AND ownerId LIKE 'anti|%'")
    fun observeMyAntiVotes(voterId: String): Flow<List<String>>

    /** Все отметки по одному профилю: по времени считаем всплеск жалоб. */
    @Query("SELECT * FROM profile_hearts WHERE ownerId = :ownerId")
    fun observeVotesOf(ownerId: String): Flow<List<ProfileHeartEntity>>

    /** То же без подписки: нужно в момент сохранения/приёма жалобы. */
    @Query("SELECT * FROM profile_hearts WHERE ownerId = :ownerId")
    suspend fun votesOf(ownerId: String): List<ProfileHeartEntity>
}
