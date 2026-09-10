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

    /**
     * Все просмотры: считаем их на стороне Kotlin.
     *
     * Группировку с COUNT(*) в отдельный класс Room тоже умеет, но требует
     * точного совпадения имён колонок и класса-обёртки. Строк здесь мало -
     * по одной на пост и читателя, - поэтому надёжнее отдать список как есть.
     */
    @Query("SELECT * FROM post_views")
    fun observeAll(): Flow<List<PostViewEntity>>

    /** Отмечал ли ЭТОТ читатель пост: по нему решаем, слать ли пакет. */
    @Query(
        "SELECT COUNT(*) FROM post_views WHERE topicId = :topicId AND viewerId = :viewerId"
    )
    suspend fun hasView(topicId: String, viewerId: String): Int

    /** Все просмотры поста: читатель раскладывает по ним сводку сборщика. */
    @Query("SELECT * FROM post_views WHERE topicId = :topicId")
    suspend fun getForTopic(topicId: String): List<PostViewEntity>
}
