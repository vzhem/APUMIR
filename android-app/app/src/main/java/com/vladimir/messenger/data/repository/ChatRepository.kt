package com.vladimir.messenger.data.repository

import com.vladimir.messenger.domain.model.MessageChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.vladimir.messenger.service.CloudflareRelay
import org.json.JSONObject

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
) {
    companion object {
        private const val TAG = "ChatRepository"
    }

    fun observeChats(): Flow<List<Chat>> =
        chatDao.observeAllChats().map { it.map { e -> e.toDomain() } }

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

            // ШАГ 3: Отправить через Rust (MQTT/P2P)
            var usedChannel = MessageChannel.UNKNOWN

            var sent = if (actualRecipientId.isNotBlank()) {
                Log.i(TAG, "🚀 SENDING via Rust: messageId=$messageId recipient=$actualRecipientId")
                val ok = RustBridge.sendMessage(messageId, chatId, actualRecipientId, content)
                Log.i(TAG, "  Rust result: $ok")
                if (ok) usedChannel = MessageChannel.MQTT
                ok
            } else {
                Log.w(TAG, "❌ sendMessage: recipient id is blank for chatId=$chatId")
                false
            }

            // ШАГ 4: Fallback через Cloudflare relay
            if (!sent && actualRecipientId.isNotBlank()) {
                Log.w(TAG, "MQTT failed, trying Cloudflare relay fallback")
                val cfRelay = CloudflareRelay.getInstance()
                if (cfRelay != null) {
                    try {
                        val payload = JSONObject().apply {
                            put("type", "message")
                            put("messageId", messageId)
                            put("chatId", chatId)
                            put("content", content)
                            put("timestamp", timestamp)
                        }.toString()
                        val cfSent = kotlinx.coroutines.runBlocking {
                            cfRelay.sendMessage(actualRecipientId, payload)
                        }
                        if (cfSent) {
                            sent = true
                            usedChannel = MessageChannel.CF
                            Log.i(TAG, "Message sent via Cloudflare relay")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "CF relay send failed", e)
                    }
                }
            }

            Log.i(TAG, "sendMessage sent=$sent messageId=$messageId recipient=$actualRecipientId")

            if (sent) {
                messageDao.updateMessageStatus(messageId, MessageStatus.SENT.name)
                if (usedChannel != MessageChannel.UNKNOWN) {
                    messageDao.updateMessageChannel(messageId, usedChannel.name)
                }

                // Принудительный CF fallback через 5 секунд
                if (usedChannel == MessageChannel.MQTT) {
                    GlobalScope.launch(Dispatchers.IO) {
                        delay(5000)
                        val currentStatus = messageDao.getMessageById(messageId)?.status
                        if (currentStatus == MessageStatus.SENT.name || currentStatus == MessageStatus.PENDING.name) {
                            val cfRelay = CloudflareRelay.getInstance()
                            if (cfRelay != null) {
                                try {
                                    val payload = JSONObject().apply {
                                        put("type", "message")
                                        put("messageId", messageId)
                                        put("chatId", chatId)
                                        put("content", content)
                                        put("timestamp", timestamp)
                                    }.toString()
                                    val cfSent = cfRelay.sendMessage(actualRecipientId, payload)
                                    if (cfSent) {
                                        Log.i(TAG, "Message duplicated via CF relay (MQTT unconfirmed)")
                                        messageDao.updateMessageChannel(messageId, MessageChannel.CF.name)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "CF duplicate failed", e)
                                }
                            }
                        }
                    }
                }
            }

            Result.success(entity.toDomain())
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error", e)
            Result.failure(e)
        }
    }

    suspend fun retryPendingMessagesForPeer(peerId: String): Int {
        val pending = messageDao.getPendingOutgoingMessages()
        var retried = 0

        for (msg in pending) {
            val chat = chatDao.getChatById(msg.chatId) ?: continue
            if (chat.contactId != peerId) continue

            val sent = RustBridge.sendMessage(msg.id, msg.chatId, peerId, msg.content)
            Log.i(TAG, "retryPendingMessagesForPeer peer=$peerId msg=${msg.id} sent=$sent")

            if (sent) {
                messageDao.updateMessageStatus(msg.id, MessageStatus.SENT.name)
                retried++
            }
        }

        if (retried > 0) {
            Log.i(TAG, "Retried $retried pending messages for peer=$peerId")
        }

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
        messageDao.insertMessage(entity)
        chatDao.updateLastMessage(chatId, content, timestamp)
    }

    suspend fun getChatById(chatId: String): Chat? {
        return chatDao.getChatById(chatId)?.toDomain()
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
