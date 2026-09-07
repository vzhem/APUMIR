package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.PostViewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PostViewDao {

    /** Повторный просмотр того же читателя заменяет прежний, а не удваивает. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(view: PostViewEntity)

    @Query("SELECT topicId, COUNT(*) AS count FROM post_views GROUP BY topicId")
    fun observeCounts(): Flow<List<TopicViewCount>>

    @Query("SELECT COUNT(*) FROM post_views WHERE topicId = :topicId")
    suspend fun countOf(topicId: String): Int

    /** Отмечал ли ЭТОТ читатель пост: по нему решаем, слать ли пакет. */
    @Query(
        "SELECT COUNT(*) FROM post_views WHERE topicId = :topicId AND viewerId = :viewerId"
    )
    suspend fun hasView(topicId: String, viewerId: String): Int
}

/** Сколько просмотров у поста: строка выборки «тема - число». */
data class TopicViewCount(
    val topicId: String,
    val count: Int,
)
