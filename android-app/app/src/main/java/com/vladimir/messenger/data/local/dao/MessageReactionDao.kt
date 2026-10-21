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

    /** Все значки одного автора на одном сообщении (их до пяти). */
    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId AND nodeId = :nodeId")
    suspend fun getAllForUser(messageId: String, nodeId: String): List<MessageReactionEntity>

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId AND nodeId = :nodeId AND emoji = :emoji")
    suspend fun getByEmoji(messageId: String, nodeId: String, emoji: String): MessageReactionEntity?

    /** Снять один конкретный значок автора, остальные его реакции не трогать. */
    @Query("DELETE FROM message_reactions WHERE messageId = :messageId AND nodeId = :nodeId AND emoji = :emoji")
    suspend fun removeByEmoji(messageId: String, nodeId: String, emoji: String)

    /** Раунд 135: реакции удаляемого сообщения - за призраком их не оставляем. */
    @Query("DELETE FROM message_reactions WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("DELETE FROM message_reactions WHERE chatId = :chatId")
    suspend fun clearChat(chatId: String)
}
