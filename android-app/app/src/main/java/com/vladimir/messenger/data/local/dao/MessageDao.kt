package com.vladimir.messenger.data.local.dao

import androidx.room.*
import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIgnore(message: MessageEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    suspend fun messageExists(messageId: String): Boolean


    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    /**
     * Чужие сообщения в чате - за них отправителю уходит отчёт «прочитано».
     * Берём последние: старые он уже видел отмеченными.
     */
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND isFromMe = 0 " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun recentIncoming(chatId: String, limit: Int): List<MessageEntity>

    /** Отметить свои сообщения прочитанными по отчёту собеседника. */
    @Query(
        "UPDATE messages SET status = 'READ' WHERE id IN (:messageIds) " +
            "AND isFromMe = 1 AND status <> 'READ'"
    )
    suspend fun markReadByIds(messageIds: List<String>): Int

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE isFromMe = 1 AND status IN ('PENDING', 'QUEUED_OFFLINE') ORDER BY timestamp ASC")
    suspend fun getPendingOutgoingMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isFromMe = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOutgoingMessagesForChat(chatId: String, limit: Int): List<MessageEntity>


    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isFromMe = 1 AND status IN ('PENDING', 'QUEUED_OFFLINE', 'SENT') ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getUnconfirmedOutgoingMessages(chatId: String, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET channel = :channel WHERE id = :messageId")
    suspend fun updateMessageChannel(messageId: String, channel: String)


    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    // ── Группы и темы (v8) ──────────────────────────────────────────────────
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId ORDER BY timestamp ASC")
    fun observeTopicMessages(chatId: String, topicId: String): Flow<List<MessageEntity>>

    /**
     * Все сообщения чата разом, без разреза по темам.
     *
     * Нужно ленте канала: пост - это первое сообщение темы, а число
     * комментариев считается по остальным сообщениям той же темы.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeChatMessages(chatId: String): Flow<List<MessageEntity>>

    // Закрепы читаются в разрезе темы: закреп из одной темы не должен висеть
    // вверху другой (фильтр topicId обязателен, иначе закреп общий на группу).
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND isPinned = 1 ORDER BY pinnedAtMs DESC"
    )
    fun observePinnedMessages(chatId: String, topicId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND isPinned = 1 ORDER BY pinnedAtMs DESC"
    )
    suspend fun getPinnedMessages(chatId: String, topicId: String): List<MessageEntity>

    @Query("UPDATE messages SET isPinned = :pinned, pinnedAtMs = :atMs, pinnedBy = :by WHERE id = :messageId")
    suspend fun updatePinned(messageId: String, pinned: Boolean, atMs: Long?, by: String?)

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND topicId = :topicId")
    suspend fun countTopicMessages(chatId: String, topicId: String): Int

    /**
     * Сообщения темы разом, без подписки: нужны владельцу канала, чтобы
     * дослать опоздавшему подписчику пост с фотографиями.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId ORDER BY timestamp ASC")
    suspend fun getTopicMessages(chatId: String, topicId: String): List<MessageEntity>

    /** Время самого свежего сообщения темы (null - тема пуста); без загрузки всей темы. */
    @Query("SELECT MAX(timestamp) FROM messages WHERE chatId = :chatId AND topicId = :topicId")
    suspend fun latestTopicTimestamp(chatId: String, topicId: String): Long?

    // ── Комментарии большого канала (рой, этап 4) ────────────────────────────
    // Текстовые сообщения темы - без служебных кусков фото и длинного текста
    // ([partPattern] = `InlineImage.PART_MARKER + "%"`). В теме канала самое
    // раннее из них - пост, остальные - комментарии. Запросы с пределом:
    // у сборщика ветка может быть на тысячи комментариев, и поднимать её
    // целиком на каждую просьбу нельзя.

    /** Сколько текстовых сообщений (пост + комментарии) в теме. */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND content NOT LIKE :partPattern"
    )
    suspend fun countTopicTexts(chatId: String, topicId: String, partPattern: String): Int

    /** Текстовые сообщения темы между [afterMs] и [beforeMs] (не включая), от новых к старым, не больше [limit]. */
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND content NOT LIKE :partPattern AND timestamp > :afterMs AND timestamp < :beforeMs " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getTopicTextsBetween(
        chatId: String,
        topicId: String,
        partPattern: String,
        afterMs: Long,
        beforeMs: Long,
        limit: Int,
    ): List<MessageEntity>

    /** Самые ранние текстовые сообщения темы (первое - пост), не больше [limit]. */
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND content NOT LIKE :partPattern ORDER BY timestamp ASC LIMIT :limit"
    )
    suspend fun getTopicTextsOldest(chatId: String, topicId: String, partPattern: String, limit: Int): List<MessageEntity>

    /** Идентификаторы всех текстовых сообщений темы - для сверки с описью сборщика. */
    @Query(
        "SELECT id FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND content NOT LIKE :partPattern"
    )
    suspend fun topicTextIds(chatId: String, topicId: String, partPattern: String): List<String>

    /** Правка текста: пост канала редактируется под тем же id. */
    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateContent(messageId: String, content: String)

    /**
     * Служебные строки по шаблону содержимого: куски длинного текста одного
     * сообщения (см. InlineImage.textPartPattern) - при правке куски прежней
     * редакции убираются.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND content LIKE :pattern")
    suspend fun getByContentPattern(chatId: String, pattern: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    /**
     * Стереть все сообщения группы. У messages нет внешнего ключа на groups,
     * поэтому каскад их не убирает — чистим явно при удалении группы.
     */
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteGroupMessages(chatId: String)

}
