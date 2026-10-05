package com.vladimir.messenger.data.local.dao

import androidx.room.*
import com.vladimir.messenger.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query(
        "SELECT * FROM chats ORDER BY " +
            "CASE WHEN pinnedAtMs IS NULL THEN 1 ELSE 0 END, " +
            "pinnedAtMs DESC, lastMessageTime DESC"
    )
    fun observeAllChats(): Flow<List<ChatEntity>>

    /**
     * Окно списка чатов: сначала закреплённые беседы, затем самые свежие.
     *
     * Главный экран не грузит переписку целиком - берёт верхушку списка и
     * досыпает по мере прокрутки. Порядок тот же, поэтому верхние :limit строк
     * окна совпадают с верхними строками полного списка.
     */
    @Query(
        "SELECT * FROM chats ORDER BY " +
            "CASE WHEN pinnedAtMs IS NULL THEN 1 ELSE 0 END, " +
            "pinnedAtMs DESC, lastMessageTime DESC LIMIT :limit"
    )
    fun observeChatsWindow(limit: Int): Flow<List<ChatEntity>>

    /**
     * Поиск идёт в базу, а не по загруженному окну: иначе нашлось бы только
     * то, что уже подгружено.
     */
    @Query(
        "SELECT * FROM chats WHERE contactName LIKE '%' || :query || '%' " +
            "OR lastMessage LIKE '%' || :query || '%' " +
            "ORDER BY CASE WHEN pinnedAtMs IS NULL THEN 1 ELSE 0 END, " +
            "pinnedAtMs DESC, lastMessageTime DESC LIMIT :limit"
    )
    fun searchChats(query: String, limit: Int): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats")
    fun observeChatCount(): Flow<Int>

    @Query(
        "SELECT * FROM chats ORDER BY " +
            "CASE WHEN pinnedAtMs IS NULL THEN 1 ELSE 0 END, " +
            "pinnedAtMs DESC, lastMessageTime DESC"
    )
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

    // Раунд 150: счётчик непрочитанных личных чатов (на списке чатов бейджа
    // не было - saveIncomingMessage только обновлял lastMessage).
    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE id = :chatId")
    suspend fun incrementUnread(chatId: String)

    @Query("UPDATE chats SET lastMessage = :message, lastMessageTime = :time WHERE id = :chatId")
    suspend fun updateLastMessage(chatId: String, message: String, time: Long)

    /** р239: снять с чата непрочитанные, накрученные мусорными пакетами. */
    @Query("UPDATE chats SET unreadCount = MAX(0, unreadCount - :count) WHERE id = :chatId")
    suspend fun decrementUnread(chatId: String, count: Int)

    @Query("UPDATE chats SET contactName = :name WHERE contactId = :contactId")
    suspend fun updateContactName(contactId: String, name: String)

    @Query("UPDATE chats SET isContactOnline = :isOnline WHERE contactId = :contactId")
    suspend fun updateContactOnline(contactId: String, isOnline: Boolean)

    /** р249: убрать чат в архив или вернуть из него (строка и переписка целы). */
    @Query("UPDATE chats SET archived = :archived WHERE id = :chatId")
    suspend fun setArchived(chatId: String, archived: Boolean)

    /** р249: выключить звук до :untilMs (0 - включить обратно). */
    @Query("UPDATE chats SET mutedUntilMs = :untilMs WHERE id = :chatId")
    suspend fun setMutedUntil(chatId: String, untilMs: Long)

    /**
     * р249: звук выключен прямо сейчас (срок ещё не истёк).
     *
     * Считаем строкой, а не логическим выражением: Room не умеет класть
     * результат сравнения в Boolean, а `COUNT(*)` в Int - умеет (грабли из
     * `docs/AI_HANDOFF.md`: COUNT(*) нельзя проецировать в свой data class,
     * а в скаляр - можно).
     */
    @Query("SELECT COUNT(*) FROM chats WHERE id = :chatId AND mutedUntilMs > :nowMs")
    suspend fun countMuted(chatId: String, nowMs: Long): Int

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

    /** Непрочитанные личные чаты — для вкладки «Не прочитано». */
    @Query(
        "SELECT * FROM chats WHERE unreadCount > 0 ORDER BY " +
            "CASE WHEN pinnedAtMs IS NULL THEN 1 ELSE 0 END, " +
            "pinnedAtMs DESC, lastMessageTime DESC"
    )
    fun observeUnreadChats(): Flow<List<ChatEntity>>
}
