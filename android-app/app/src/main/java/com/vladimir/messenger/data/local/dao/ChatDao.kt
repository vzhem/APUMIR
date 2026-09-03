package com.vladimir.messenger.data.local.dao

import androidx.room.*
import com.vladimir.messenger.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY lastMessageTime DESC")
    fun observeAllChats(): Flow<List<ChatEntity>>

    /**
     * Окно списка чатов: только свежие :limit строк.
     *
     * Главный экран не грузит переписку целиком - берёт верхушку по времени и
     * досыпает по мере прокрутки. Порядок тот же, поэтому верхние :limit строк
     * окна совпадают с верхними строками полного списка.
     */
    @Query("SELECT * FROM chats ORDER BY lastMessageTime DESC LIMIT :limit")
    fun observeChatsWindow(limit: Int): Flow<List<ChatEntity>>

    /**
     * Поиск идёт в базу, а не по загруженному окну: иначе нашлось бы только
     * то, что уже подгружено.
     */
    @Query(
        "SELECT * FROM chats WHERE contactName LIKE '%' || :query || '%' " +
            "OR lastMessage LIKE '%' || :query || '%' " +
            "ORDER BY lastMessageTime DESC LIMIT :limit"
    )
    fun searchChats(query: String, limit: Int): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats")
    fun observeChatCount(): Flow<Int>

    @Query("SELECT * FROM chats ORDER BY lastMessageTime DESC")
    suspend fun getAllChats(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): ChatEntity?

    /** Живой поток чата — шапка лички слушает онлайн-статус через него. */
    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observeChat(chatId: String): Flow<ChatEntity?>

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

    @Query("UPDATE chats SET isContactOnline = :isOnline WHERE contactId = :contactId")
    suspend fun updateContactOnline(contactId: String, isOnline: Boolean)

    /** Удалить чат по идентификатору (меню «⋮» в пузыре чата). */
    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String)

    /** Все чаты с этим собеседником: их бывает больше одного после переустановки. */
    @Query("SELECT * FROM chats WHERE contactId = :contactId")
    suspend fun getChatsByContactId(contactId: String): List<ChatEntity>

    /** Перевесить сообщения со старого чата на оставшийся при склейке дублей. */
    @Query("UPDATE messages SET chatId = :newChatId WHERE chatId = :oldChatId")
    suspend fun moveMessages(oldChatId: String, newChatId: String)

    /** Холодный старт: гасим все точки онлайна, peer_discovered включит живых. */
    @Query("UPDATE chats SET isContactOnline = 0")
    suspend fun setAllOffline()
}
