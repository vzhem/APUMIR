package com.vladimir.messenger.data.local.dao

import androidx.room.*
import com.vladimir.messenger.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY lastMessageTime DESC")
    fun observeAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE contactId = :contactId LIMIT 1")
    suspend fun getChatByContactId(contactId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Delete
    suspend fun deleteChat(chat: ChatEntity)

    @Query("UPDATE chats SET unreadCount = 0 WHERE id = :chatId")
    suspend fun markAsRead(chatId: String)

    @Query("UPDATE chats SET lastMessage = :message, lastMessageTime = :time WHERE id = :chatId")
    suspend fun updateLastMessage(chatId: String, message: String, time: Long)

    @Query("UPDATE chats SET contactName = :name WHERE contactId = :contactId")
    suspend fun updateContactName(contactId: String, name: String)
}
