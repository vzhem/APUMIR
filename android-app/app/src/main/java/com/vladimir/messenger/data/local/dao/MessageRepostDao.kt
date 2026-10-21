package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.MessageRepostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageRepostDao {

    /** Повтор того же события игнорируется: счётчик от дублей не растёт. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(repost: MessageRepostEntity)

    /** Все репосты чата: экран считает их по сообщениям на своей стороне. */
    @Query("SELECT * FROM message_reposts WHERE chatId = :chatId")
    fun observeForChat(chatId: String): Flow<List<MessageRepostEntity>>

    /** Все строки одного сообщения - сборщику сводки и экрану канала. */
    @Query("SELECT * FROM message_reposts WHERE messageId = :messageId")
    suspend fun getForMessage(messageId: String): List<MessageRepostEntity>
}
