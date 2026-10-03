package com.vladimir.messenger.data.local.dao

import androidx.room.*
import com.vladimir.messenger.data.local.MessagePinPolicy
import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Результат атомарного изменения закрепа. */
enum class MessagePinMutation {
    UPDATED,
    UNCHANGED,
    LIMIT_REACHED,
    SCOPE_CONFLICT,
    NOT_FOUND,
}

/** р239: агрегат для уборки мусора «печатает…» (чат -> сколько строк). */
data class TypingJunkRow(val chatId: String, val count: Int)

/** Delivery ACK rows written by pre-fix versions (chat -> how many rows). */
data class DeliveryAckJunkRow(val chatId: String, val count: Int)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIgnore(message: MessageEntity): Long

    /** р226: свежесть переписки - обмен «кто отстал» между устройствами. */
    @Query("SELECT MAX(timestamp) FROM messages")
    suspend fun maxTimestamp(): Long?

    /** р226: хвост переписки для догана отставшего устройства зеркала. */
    @Query("SELECT * FROM messages WHERE timestamp > :since ORDER BY timestamp ASC LIMIT :lim")
    suspend fun messagesSince(since: Long, lim: Int): List<MessageEntity>

    /** р226: неотправленные исходящие тени - перевыслать активному партнёру. */
    @Query("SELECT * FROM messages WHERE isFromMe = 1 AND status IN ('PENDING', 'QUEUED_OFFLINE') ORDER BY timestamp ASC LIMIT :lim")
    suspend fun pendingMirrorOutgoing(lim: Int): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    suspend fun messageExists(messageId: String): Boolean


    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    /** A delivery ACK can update only an outgoing message, and must not downgrade READ. */
    @Query(
        "UPDATE messages SET status = 'DELIVERED' WHERE id = :messageId " +
            "AND isFromMe = 1 AND status IN ('PENDING', 'QUEUED_OFFLINE', 'SENT', 'FAILED')"
    )
    suspend fun markOutgoingMessageDelivered(messageId: String): Int

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

    /**
     * р245: содержимое последних строк переписки - для насоса медиа. По нему
     * тень находит ссылки на гифки, байтов которых у неё ещё нет, и просит их
     * у активного устройства.
     */
    @Query("SELECT content FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentContents(limit: Int): List<String>

    /** Раунд 179: вся офлайн-очередь (любой чат) - для периодического слива. */
    @Query("SELECT * FROM messages WHERE isFromMe = 1 AND status = 'QUEUED_OFFLINE' ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getQueuedOfflineMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isFromMe = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOutgoingMessagesForChat(chatId: String, limit: Int): List<MessageEntity>


    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isFromMe = 1 AND status IN ('PENDING', 'QUEUED_OFFLINE', 'SENT') ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getUnconfirmedOutgoingMessages(chatId: String, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET channel = :channel WHERE id = :messageId")
    suspend fun updateMessageChannel(messageId: String, channel: String)


    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    /** A rolling two-minute window; Room re-evaluates SQLite's clock on message-table invalidation. */
    @Query(
        "SELECT * FROM messages WHERE isFromMe = 0 AND " +
            "timestamp >= (CAST(strftime('%s', 'now') AS INTEGER) * 1000 - 120000) " +
            "ORDER BY timestamp DESC, id DESC LIMIT 512"
    )
    fun observeRecentIncomingWindow(): Flow<List<MessageEntity>>

    // ── Группы и темы (v8) ──────────────────────────────────────────────────
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId ORDER BY timestamp ASC, id ASC")
    fun observeTopicMessages(chatId: String, topicId: String): Flow<List<MessageEntity>>

    /**
     * Все сообщения чата разом, без разреза по темам.
     *
     * Нужно ленте канала: пост - это первое сообщение темы, а число
     * комментариев считается по остальным сообщениям той же темы.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC")
    fun observeChatMessages(chatId: String): Flow<List<MessageEntity>>

    // Общие GroupWire-закрепы одной темы: личный pin публикации канала
    // (pinnedBy == null) не попадает в список обсуждения.
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND isPinned = 1 AND pinnedBy IS NOT NULL ORDER BY pinnedAtMs DESC"
    )
    fun observePinnedMessages(chatId: String, topicId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId " +
            "AND isPinned = 1 AND pinnedBy IS NOT NULL ORDER BY pinnedAtMs DESC"
    )
    suspend fun getPinnedMessages(chatId: String, topicId: String): List<MessageEntity>

    // Закрепы личного чата без темы. Публикации канала имеют topicId и
    // отображаются по потоку публикаций, а не этим запросом.
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId " +
            "AND (topicId IS NULL OR topicId = '') AND isPinned = 1 " +
            "AND pinnedBy IS NULL " +
            "ORDER BY pinnedAtMs DESC"
    )
    fun observePinnedChatMessages(chatId: String): Flow<List<MessageEntity>>

    // Личные закрепы канала. Комментарии с общим GroupWire-pin имеют pinnedBy.
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND isPinned = 1 " +
            "AND pinnedBy IS NULL ORDER BY pinnedAtMs DESC"
    )
    fun observePinnedChannelPosts(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND isPinned = 1 AND pinnedBy IS NULL")
    suspend fun countPinnedInChat(chatId: String): Int

    @Query(
        "SELECT COUNT(*) FROM messages WHERE chatId = :chatId " +
            "AND COALESCE(topicId, '') = :topicId AND isPinned = 1 AND pinnedBy IS NOT NULL"
    )
    suspend fun countPinnedInTopic(chatId: String, topicId: String): Int

    /** Первый обычный текст темы — публикация канала; APUIMGP1 — только служебные части. */
    @Query(
        "SELECT id FROM messages WHERE chatId = :chatId " +
            "AND COALESCE(topicId, '') = :topicId AND content NOT LIKE 'APUIMGP1:%' " +
            "ORDER BY timestamp ASC, id ASC LIMIT 1"
    )
    suspend fun firstTextMessageIdInTopic(chatId: String, topicId: String): String?

    /** Количество закреплённых публикаций канала (комментарии не считаются). */
    @Query(
        "SELECT COUNT(*) FROM messages AS pinned WHERE pinned.chatId = :chatId " +
            "AND pinned.isPinned = 1 AND pinned.pinnedBy IS NULL " +
            "AND pinned.topicId IS NOT NULL AND pinned.topicId != '' " +
            "AND pinned.content NOT LIKE 'APUIMGP1:%' AND pinned.id IN (" +
            "SELECT head.id FROM messages AS head WHERE head.chatId = :chatId " +
            "AND head.topicId IS NOT NULL AND head.topicId != '' " +
            "AND head.content NOT LIKE 'APUIMGP1:%' AND head.id = (" +
            "SELECT first.id FROM messages AS first WHERE first.chatId = head.chatId " +
            "AND first.topicId = head.topicId AND first.content NOT LIKE 'APUIMGP1:%' " +
            "ORDER BY first.timestamp ASC, first.id ASC LIMIT 1))"
    )
    suspend fun countPinnedChannelPosts(chatId: String): Int

    @Query("UPDATE messages SET isPinned = :pinned, pinnedAtMs = :atMs, pinnedBy = :by WHERE id = :messageId")
    suspend fun updatePinned(messageId: String, pinned: Boolean, atMs: Long?, by: String?)

    /** Лимит закрепов личного чата: максимум 10 на chatId; лента канала считает публикации отдельно. */
    @Transaction
    suspend fun updatePinnedWithinChatLimit(
        messageId: String,
        pinned: Boolean,
        atMs: Long?,
        by: String?,
    ): MessagePinMutation {
        val message = getMessageById(messageId) ?: return MessagePinMutation.NOT_FOUND
        if (message.isPinned && message.pinnedBy != null) return MessagePinMutation.SCOPE_CONFLICT
        if (message.isPinned == pinned) return MessagePinMutation.UNCHANGED
        if (pinned && !MessagePinPolicy.canAddPin(countPinnedInChat(message.chatId))) {
            return MessagePinMutation.LIMIT_REACHED
        }
        updatePinned(messageId, pinned, atMs, by)
        return MessagePinMutation.UPDATED
    }

    /** Лимит закрепов публикаций канала: максимум 10 на всю ленту, комментарии идут по темам. */
    @Transaction
    suspend fun updatePinnedChannelPostWithinLimit(
        channelId: String,
        messageId: String,
        pinned: Boolean,
        atMs: Long?,
        by: String?,
    ): MessagePinMutation {
        val message = getMessageById(messageId) ?: return MessagePinMutation.NOT_FOUND
        val topicId = message.topicId?.takeIf { it.isNotBlank() }
            ?: return MessagePinMutation.NOT_FOUND
        if (message.chatId != channelId || firstTextMessageIdInTopic(channelId, topicId) != messageId) {
            return MessagePinMutation.NOT_FOUND
        }
        if (message.isPinned && message.pinnedBy != null) return MessagePinMutation.SCOPE_CONFLICT
        if (message.isPinned == pinned) return MessagePinMutation.UNCHANGED
        if (pinned && !MessagePinPolicy.canAddPin(countPinnedChannelPosts(channelId))) {
            return MessagePinMutation.LIMIT_REACHED
        }
        updatePinned(messageId, pinned, atMs, by)
        return MessagePinMutation.UPDATED
    }

    /** Лимит закрепов одной темы группы/обсуждения канала: максимум 10 на пару chatId/topicId. */
    @Transaction
    suspend fun updatePinnedWithinTopicLimit(
        chatId: String,
        topicId: String,
        messageId: String,
        pinned: Boolean,
        atMs: Long?,
        by: String?,
    ): MessagePinMutation {
        val message = getMessageById(messageId) ?: return MessagePinMutation.NOT_FOUND
        if (message.chatId != chatId || message.topicId.orEmpty() != topicId) {
            return MessagePinMutation.NOT_FOUND
        }
        if (message.isPinned && message.pinnedBy == null) return MessagePinMutation.SCOPE_CONFLICT
        if (message.isPinned == pinned) return MessagePinMutation.UNCHANGED
        if (pinned && !MessagePinPolicy.canAddPin(countPinnedInTopic(chatId, topicId))) {
            return MessagePinMutation.LIMIT_REACHED
        }
        updatePinned(messageId, pinned, atMs, by)
        return MessagePinMutation.UPDATED
    }

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND topicId = :topicId")
    suspend fun countTopicMessages(chatId: String, topicId: String): Int

    /**
     * Сообщения темы разом, без подписки: нужны владельцу канала, чтобы
     * дослать опоздавшему подписчику пост с фотографиями.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND topicId = :topicId ORDER BY timestamp ASC, id ASC")
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
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
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
            "AND content NOT LIKE :partPattern ORDER BY timestamp ASC, id ASC LIMIT :limit"
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
     * р239: служебные пакеты «печатает…», ошибочно сохранённые как сообщения
     * (r235-r238). Возвращает, сколько строк удалено.
     */
    @Query("DELETE FROM messages WHERE content LIKE 'APUTYP1|%'")
    suspend fun deleteTypingJunk(): Int

    /** р240: сколько своих сообщений ждёт отправки (для диагностики). */
    @Query("SELECT COUNT(*) FROM messages WHERE isFromMe = 1 AND status = 'PENDING'")
    suspend fun countPendingOutgoing(): Int

    /** р239: в каких чатах лежит этот мусор и по скольку строк. */
    @Query(
        "SELECT chatId AS chatId, COUNT(*) AS count FROM messages " +
            "WHERE content LIKE 'APUTYP1|%' GROUP BY chatId"
    )
    suspend fun chatsWithTypingJunk(): List<TypingJunkRow>

    /** Exact UUID-shaped ACK rows accidentally stored as incoming chat messages by older clients. */
    @Query(
        "SELECT chatId AS chatId, COUNT(*) AS count FROM messages " +
            "WHERE isFromMe = 0 AND trim(content) GLOB :pattern GROUP BY chatId"
    )
    suspend fun chatsWithDeliveryAckJunk(pattern: String): List<DeliveryAckJunkRow>

    @Query("SELECT content FROM messages WHERE isFromMe = 0 AND trim(content) GLOB :pattern")
    suspend fun deliveryAckJunkContents(pattern: String): List<String>

    @Query("DELETE FROM messages WHERE isFromMe = 0 AND trim(content) GLOB :pattern")
    suspend fun deleteDeliveryAckJunk(pattern: String): Int

    /**
     * Раунд 135: последнее сообщение чата - пересчёт превью после удаления.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(chatId: String): MessageEntity?

    /**
     * Раунд 135: чужая просьба «удали у всех». Стирает ТОЛЬКО сообщение
     * этого отправителя в этом чате: подделать чужое удаление нельзя,
     * свои сообщения чужой конверт не трогает. Возвращает сколько стёрто.
     */
    @Query(
        "DELETE FROM messages WHERE id = :messageId AND chatId = :chatId " +
            "AND senderId = :senderId"
    )
    suspend fun deleteByIdChatAndSender(messageId: String, chatId: String, senderId: String): Int

    /**
     * Стереть все сообщения группы. У messages нет внешнего ключа на groups,
     * поэтому каскад их не убирает — чистим явно при удалении группы.
     */
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteGroupMessages(chatId: String)

}
