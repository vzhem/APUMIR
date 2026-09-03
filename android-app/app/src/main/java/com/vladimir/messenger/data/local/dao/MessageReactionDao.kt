package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.MessageReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageReactionDao {

    /** Все реакции чата или группы разом: экран раскладывает их по сообщениям. */
    @Query("SELECT * FROM message_reactions WHERE chatId = :chatId")
    fun observeForChat(chatId: String): Flow<List<MessageReactionEntity>>

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId")
    suspend fun getForMessage(messageId: String): List<MessageReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(reaction: MessageReactionEntity)

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId AND nodeId = :nodeId")
    suspend fun remove(messageId: String, nodeId: String)

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId AND nodeId = :nodeId")
    suspend fun get(messageId: String, nodeId: String): MessageReactionEntity?

    @Query("DELETE FROM message_reactions WHERE chatId = :chatId")
    suspend fun clearChat(chatId: String)
}
