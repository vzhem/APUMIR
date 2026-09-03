package com.vladimir.messenger.data.repository

import com.vladimir.messenger.domain.model.MessageChannel

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val referralAttribution: com.vladimir.messenger.data.referral.ReferralAttributionSender,
) {
    // Защита от повторного FULL SYNC в течение 30 секунд
    private val lastFullSyncTime = mutableMapOf<String, Long>()
    private val FULL_SYNC_COOLDOWN_MS = 300_000L  // 30 секунд
    companion object {
        private const val TAG = "ChatRepository"
    }

    fun observeChats(): Flow<List<Chat>> =
        chatDao.observeAllChats().map { it.map { e -> e.toDomain() } }

    /** Живой поток ОДНОГО чата: шапка переписки подписывается на онлайн. */
    fun observeChat(chatId: String): Flow<ChatEntity?> = chatDao.observeChat(chatId)

    /** Холодный старт: старые точки онлайна из прошлого запуска гасим. */
    suspend fun setAllContactsOffline() = chatDao.setAllOffline()

    /** Онлайн-статус контакта в чатовой таблице (точка в списке чатов и шапка лички). */
    suspend fun updateContactOnlineStatus(contactId: String, isOnline: Boolean) =
        chatDao.updateContactOnline(contactId, isOnline)

    /** Contact IDs of every chat; used by the file HELLO sweep to bootstrap missing pins. */
    suspend fun getAllContactIds(): List<String> =
        chatDao.getAllChats().map { it.contactId }.filter { it.isNotBlank() }.distinct()

    fun observeMessages(chatId: String): Flow<List<Message>> =
        messageDao.observeMessages(chatId).map { it.map { e -> e.toDomain() } }

    suspend fun sendMessage(chatId: String, recipientId: String, content: String): Result<Message> {
        return try {
            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            // ШАГ 1: Определить recipientId
            val chat = chatDao.getChatById(chatId)
            val rawId = if (recipientId.isBlank()) chat?.contactId ?: "" else recipientId
            
            Log.i(TAG, "📨 ROUTING DEBUG:")
            Log.i(TAG, "  chatId=$chatId")
            Log.i(TAG, "  input recipientId='$recipientId'")
            Log.i(TAG, "  chat.contactId='${chat?.contactId}'")
            Log.i(TAG, "  chat.contactName='${chat?.contactName}'")
            Log.i(TAG, "  rawId='$rawId'")
            
            val actualRecipientId = when {
                rawId.startsWith("pk_") -> rawId
                rawId.contains("node=pk_") -> "pk_" + rawId.substringAfter("node=pk_").substringBefore("&")
                else -> rawId
            }
            
            Log.i(TAG, "  actualRecipientId='$actualRecipientId'")

            // ШАГ 2: Создать entity с recipientId
            val entity = MessageEntity(
                id = messageId,
                chatId = chatId,
                senderId = "self",
                content = content,
                timestamp = timestamp,
                isFromMe = true,
                status = MessageStatus.PENDING.name,
                channel = MessageChannel.UNKNOWN.name,
                recipientId = actualRecipientId,
            )
            messageDao.insertMessage(entity)
            chatDao.updateLastMessage(chatId, content, timestamp)

            // ШАГ 3: Rust owns direct QUIC and the bounded persistent MQTT/mesh offline path.
            val sentDirectly = if (actualRecipientId.isNotBlank()) {
                Log.i(TAG, "🚀 SENDING via Rust: messageId=$messageId recipient=$actualRecipientId")
                RustBridge.sendMessage(messageId, chatId, actualRecipientId, content)
            } else {
                Log.w(TAG, "❌ sendMessage: recipient id is blank for chatId=$chatId")
                false
            }

            Log.i(TAG, "sendMessage direct=$sentDirectly messageId=$messageId recipient=$actualRecipientId")

            if (sentDirectly) {
                messageDao.updateMessageStatus(messageId, MessageStatus.SENT.name)
                messageDao.updateMessageChannel(messageId, MessageChannel.LOCAL.name)
            } else if (actualRecipientId.isNotBlank()) {
                // No transport confirmed delivery. Room remains the phone-owned persistent outbox;
                // Rust may also retain the compatible relay in its bounded mesh queue. Until a
                // recipient receipt arrives this is queued, never SENT.
                messageDao.updateMessageStatus(messageId, MessageStatus.QUEUED_OFFLINE.name)
                messageDao.updateMessageChannel(messageId, MessageChannel.STORE_FORWARD.name)
                Log.i(TAG, "Message queued offline in phone-owned mesh: $messageId")
            }

            // Реферальная атрибуция: если контакт добавлен по пригласительной
            // ссылке, пригласившему уходит отдельный служебный конверт (один раз
            // на контакт, повторная отправка помечается и не выполняется).
            // На само сообщение это не влияет: сбой атрибуции не должен мешать
            // переписке, поэтому исключение глотается.
            try {
                val contactId = chat?.contactId.orEmpty()
                if (contactId.isNotBlank()) {
                    referralAttribution.sendPending(chatId, contactId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "referral attribution failed: ${e.message}")
            }

            Result.success(entity.toDomain())
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error", e)
            Result.failure(e)
        }
    }

    suspend fun retryPendingMessagesForPeer(peerId: String): Int {
        var retried = 0

        // ШАГ 1: Классический retry PENDING (если такие есть)
        try {
            val pending = messageDao.getPendingOutgoingMessages()
            Log.i(TAG, "🔍 retryPending: found ${pending.size} PENDING messages total")
            for (msg in pending) {
                val chat = chatDao.getChatById(msg.chatId) ?: continue
                if (chat.contactId != peerId) continue

                Log.i(TAG, "  📤 retry PENDING msg=${msg.id.take(8)} content=${msg.content.take(20)}")
                val sent = RustBridge.sendMessage(msg.id, msg.chatId, peerId, msg.content)
                Log.i(TAG, "  📤 retry result: sent=$sent")

                if (sent) {
                    messageDao.updateMessageStatus(msg.id, MessageStatus.SENT.name)
                    retried++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ retryPending step 1 failed: ${e.message}", e)
        }

        // ШАГ 2: FULL SYNC - отправить последние 50 сообщений этому peer
        try {
            // Проверка cooldown: не делать FULL SYNC чаще чем раз в 30 секунд для одного peer
            val now = System.currentTimeMillis()
            val lastSync = lastFullSyncTime[peerId] ?: 0L
            val timeSinceLastSync = now - lastSync
            if (timeSinceLastSync < FULL_SYNC_COOLDOWN_MS) {
                Log.i(TAG, "⏱ FULL SYNC: cooldown активен для peer=$peerId (прошло ${timeSinceLastSync}ms из ${FULL_SYNC_COOLDOWN_MS}ms)")
                return retried
            }
            lastFullSyncTime[peerId] = now
            
            Log.i(TAG, "🔍 FULL SYNC: ищем чат для peer=$peerId")
            val chat = chatDao.getChatByContactId(peerId)
            if (chat != null) {
                Log.i(TAG, "✅ Найден чат: id=${chat.id}")
                val recentMessages = messageDao.getUnconfirmedOutgoingMessages(chat.id, 50)
                Log.i(TAG, "🔄 FULL SYNC: found ${recentMessages.size} unconfirmed (PENDING/SENT) messages")
                
                for (msg in recentMessages) {
                    try {
                        Log.i(TAG, "  🚀 sync msg=${msg.id.take(8)} content=${msg.content.take(20)}")
                        val sent = RustBridge.sendMessage(msg.id, msg.chatId, peerId, msg.content)
                        Log.i(TAG, "  📤 sync sent=$sent")
                        if (sent) retried++
                    } catch (e: Exception) {
                        Log.e(TAG, "  ❌ sync failed: ${e.message}", e)
                    }
                }
            } else {
                Log.w(TAG, "⚠ No chat found for peer=$peerId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ FULL SYNC step 2 failed: ${e.message}", e)
        }

        Log.i(TAG, "✅ Synced $retried messages with peer=$peerId")
        return retried
    }

    suspend fun updateContactName(contactId: String, name: String) {
        chatDao.updateContactName(contactId, name)
    }

    suspend fun retryAllPendingMessages(): Int {
        val pending = messageDao.getPendingOutgoingMessages()
        var retried = 0

        for (msg in pending) {
            val chat = chatDao.getChatById(msg.chatId) ?: continue
            val peerId = chat.contactId
            if (peerId.isBlank()) continue

            val sent = RustBridge.sendMessage(msg.id, msg.chatId, peerId, msg.content)
            Log.i(TAG, "retryAllPendingMessages peer=$peerId msg=${msg.id} sent=$sent")

            if (sent) {
                messageDao.updateMessageStatus(msg.id, MessageStatus.SENT.name)
                retried++
            }
        }

        if (retried > 0) {
            Log.i(TAG, "Retried $retried pending messages total")
        }

        return retried
    }

    suspend fun saveIncomingMessage(
        chatId: String,
        senderId: String,
        messageId: String,
        content: String,
        timestamp: Long,
        channel: MessageChannel = MessageChannel.UNKNOWN,
        recipientId: String = "",
    ) {
        // Защита от дубликатов (FULL SYNC может прислать то же сообщение повторно)
        val exists = messageDao.messageExists(messageId)
        if (exists) {
            Log.i(TAG, "⏩ SKIP duplicate msg $messageId (already in DB)")
            return
        }
        
        Log.i(TAG, "💾 saveIncomingMessage: chatId=$chatId msgId=$messageId ts=$timestamp")
        val entity = MessageEntity(
            id = messageId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            timestamp = timestamp,
            isFromMe = false,
            status = MessageStatus.DELIVERED.name,
            recipientId = recipientId,
        )
        messageDao.insertMessageIgnore(entity)
        chatDao.updateLastMessage(chatId, content, timestamp)
    }

    suspend fun getChatById(chatId: String): Chat? {
        return chatDao.getChatById(chatId)?.toDomain()
    }

    /**
     * Local-only outgoing file placeholder: it never rides the text transport (the file packets
     * are the transport); the row exists so the chat shows the transfer and its delivery state.
     * LOCAL_FILE status is outside the retry paths' sets, so FULL SYNC never re-sends it as a
     * text message; the chat renders the transfer bubble in its place.
     */
    suspend fun insertLocalFileMessage(
        chatId: String,
        recipientId: String,
        messageId: String,
        content: String,
        timestamp: Long,
    ): Boolean {
        if (messageDao.messageExists(messageId)) return false
        val entity = MessageEntity(
            id = messageId,
            chatId = chatId,
            senderId = "self",
            content = content,
            timestamp = timestamp,
            isFromMe = true,
            status = "LOCAL_FILE",
            channel = MessageChannel.STORE_FORWARD.name,
            recipientId = recipientId,
        )
        val inserted = messageDao.insertMessageIgnore(entity)
        if (inserted != -1L) {
            chatDao.updateLastMessage(chatId, content, timestamp)
        }
        return inserted != -1L
    }

    suspend fun getChatByContactId(contactId: String): Chat? {
        return chatDao.getChatByContactId(contactId)?.toDomain()
    }

    suspend fun createChat(contactId: String, contactName: String): Chat {
        val existing = chatDao.getChatByContactId(contactId)
        if (existing != null) return existing.toDomain()

        val chatId = UUID.randomUUID().toString()
        val entity = ChatEntity(
            id = chatId,
            contactId = contactId,
            contactName = contactName,
        )
        chatDao.insertChat(entity)
        Log.i(TAG, "createChat chatId=$chatId contactName=$contactName contactId=$contactId")
        return entity.toDomain()
    }

    suspend fun getOrCreateChat(contactId: String, contactName: String): Chat {
        return createChat(contactId, contactName)
    }

    /**
     * Свести дубли чатов с одним собеседником в один.
     *
     * Дубли появлялись, когда чат создавался сразу в нескольких местах (обмен
     * QR-кодом, входящее сообщение, приглашение) - `getChatByContactId` берёт
     * первый попавшийся, поэтому второй оставался висеть отдельной строкой с
     * тем же именем. Оставляем чат с самой свежей перепиской, переписку из
     * остальных переносим в него, чтобы ничего не потерялось.
     */
    suspend fun mergeDuplicateChats(contactId: String): Chat? {
        if (contactId.isBlank()) return null
        val chats = chatDao.getChatsByContactId(contactId)
        if (chats.size < 2) return chats.firstOrNull()?.toDomain()

        val keep = chats.maxByOrNull { it.lastMessageTime ?: 0L } ?: return null
        var unread = 0
        for (chat in chats) {
            unread += chat.unreadCount
            if (chat.id == keep.id) continue
            chatDao.moveMessages(chat.id, keep.id)
            chatDao.deleteChatById(chat.id)
            Log.i(TAG, "mergeDuplicateChats: ${chat.id} слит в ${keep.id} для $contactId")
        }
        val merged = keep.copy(unreadCount = unread)
        chatDao.updateChat(merged)
        return merged.toDomain()
    }

    /**
     * Перевесить переписку на новый идентификатор собеседника.
     *
     * Человек переустановил приложение и получил новый node_id: старый чат
     * писал бы на мёртвый адрес. Переносим историю в чат с живым адресом,
     * пустой старый убираем.
     */
    suspend fun absorbChatOf(oldContactId: String, newContactId: String) {
        if (oldContactId.isBlank() || newContactId.isBlank()) return
        if (oldContactId == newContactId) return
        val target = chatDao.getChatByContactId(newContactId) ?: return
        for (chat in chatDao.getChatsByContactId(oldContactId)) {
            chatDao.moveMessages(chat.id, target.id)
            chatDao.deleteChatById(chat.id)
            Log.i(TAG, "absorbChatOf: история $oldContactId перенесена в $newContactId")
        }
    }

    /** Удалить чат вместе с историей сообщений (меню «⋮» в пузыре). */
    suspend fun deleteChat(chatId: String) {
        messageDao.deleteMessagesForChat(chatId)
        chatDao.deleteChatById(chatId)
    }

    /** Сколько чатов заведено с этим собеседником: 0, 1 или больше при дублях. */
    suspend fun chatCountOf(contactId: String): Int =
        if (contactId.isBlank()) 0 else chatDao.getChatsByContactId(contactId).size

    /** Убрать все чаты с этим собеседником вместе с перепиской. */
    suspend fun deleteChatsOf(contactId: String) {
        for (chat in chatDao.getChatsByContactId(contactId)) {
            messageDao.deleteMessagesForChat(chat.id)
            chatDao.deleteChatById(chat.id)
        }
    }

    /** Очистить переписку, сам чат остаётся в списке. */
    suspend fun clearHistory(chatId: String) {
        messageDao.deleteMessagesForChat(chatId)
        val chat = chatDao.getChatById(chatId) ?: return
        chatDao.updateChat(chat.copy(lastMessage = null, lastMessageTime = null, unreadCount = 0))
    }

    suspend fun markAsRead(chatId: String) {
        chatDao.markAsRead(chatId)
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status.name)
    }

    private fun ChatEntity.toDomain() = Chat(
        id = id,
        contactId = contactId,
        contactName = contactName,
        lastMessage = lastMessage,
        lastMessageTime = lastMessageTime,
        unreadCount = unreadCount,
        isContactOnline = isContactOnline,
    )

    private fun MessageEntity.toDomain() = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        timestamp = timestamp,
        isFromMe = isFromMe,
        status = try { MessageStatus.valueOf(status) } catch (_: Exception) { MessageStatus.PENDING },
    )

    suspend fun getMessageById(messageId: String): Message? {
        return messageDao.getMessageById(messageId)?.toDomain()
    }


    /**
     * Наблюдать за всеми сообщениями во всех чатах (для notifications).
     */
    fun observeAllMessages(): Flow<List<Message>> {
        return messageDao.observeAll().map { entities ->
            val myNodeId = RustBridge.nodeId() ?: "unknown"
            entities.filter { entity ->
                // P2P архитектура: показать только свои + адресованные мне
                // Широковещательные сообщения шифруются E2E — другие узлы их не видят
                val isForMe = entity.isFromMe || 
                              entity.recipientId == myNodeId ||
                              entity.recipientId.isBlank()  // legacy messages
                
                Log.d(TAG, "MESSAGE FILTER: id=${entity.id.take(8)} isFromMe=${entity.isFromMe} recipient=${entity.recipientId.take(16)} myNode=${myNodeId.take(16)} isForMe=$isForMe")
                
                isForMe
            }.map { it.toDomain() }
        }
    }

}
