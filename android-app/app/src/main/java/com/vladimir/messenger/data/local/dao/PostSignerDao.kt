package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.PostSignerEntity

@Dao
interface PostSignerDao {

    /** Свежий ключ того же узла от того же заверителя заменяет прежний (переустановка телефона). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(signer: PostSignerEntity)

    @Query("SELECT * FROM post_signers WHERE nodeId = :nodeId AND attestedBy = :attestedBy")
    suspend fun get(nodeId: String, attestedBy: String): PostSignerEntity?

    /** Ключи, которые узлы предъявили сами: их владелец канала вправе заверить дальше. */
    @Query("SELECT * FROM post_signers WHERE nodeId = attestedBy AND nodeId IN (:nodeIds)")
    suspend fun selfAttested(nodeIds: List<String>): List<PostSignerEntity>

    /**
     * Все узлы, предъявившие свой ключ сами, - это и есть телефоны, которые
     * умеют рой (старые версии `pkeys` не шлют). Одним запросом без IN(...):
     * у SQLite предел в 999 параметров, а подписчиков может быть больше.
     */
    @Query("SELECT nodeId FROM post_signers WHERE nodeId = attestedBy")
    suspend fun allSelfAttestedIds(): List<String>
}
