package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.PostManifestEntity

@Dao
interface PostManifestDao {

    /** Повторный манифест того же поста заменяет прежний (подпись у обоих верна). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(manifest: PostManifestEntity)

    @Query("SELECT * FROM post_manifests WHERE messageId = :messageId")
    suspend fun get(messageId: String): PostManifestEntity?

    /** Манифест поста темы (у поста канала тема одна, пост в ней первый). */
    @Query("SELECT * FROM post_manifests WHERE topicId = :topicId ORDER BY sentAtMs ASC LIMIT 1")
    suspend fun getByTopic(topicId: String): PostManifestEntity?

    /** Последние по времени поста манифесты канала. */
    @Query("SELECT * FROM post_manifests WHERE groupId = :groupId ORDER BY sentAtMs DESC LIMIT :limit")
    suspend fun latest(groupId: String, limit: Int): List<PostManifestEntity>

    /**
     * Манифесты сообщений темы обычной группы (рой, этап 5) от новых к
     * старым: в теме группы сообщений много, и у каждого свой манифест.
     */
    @Query("SELECT * FROM post_manifests WHERE topicId = :topicId ORDER BY sentAtMs DESC LIMIT :limit")
    suspend fun latestForTopic(topicId: String, limit: Int): List<PostManifestEntity>

    /** Идентификаторы сообщений темы, у которых есть манифест, новее [afterMs]. */
    @Query("SELECT messageId FROM post_manifests WHERE topicId = :topicId AND sentAtMs > :afterMs")
    suspend fun idsForTopicAfter(topicId: String, afterMs: Long): List<String>

    @Query("DELETE FROM post_manifests WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM post_manifests WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}
