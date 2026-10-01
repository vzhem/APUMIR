package com.vladimir.messenger.data.repository

import com.vladimir.messenger.domain.model.MessageChannel

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorHub
import com.vladimir.messenger.data.mirror.MirrorRow
import com.vladimir.messenger.data.receipt.DeliveryAckWire
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.util.NodeIds
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
    // Полная досылка неподтверждённого - вещь дорогая (до 50 сообщений разом),
    // поэтому одному собеседнику не чаще раза в 5 минут.
    private val FULL_SYNC_COOLDOWN_MS = 300_000L
    companion object {
        private const val TAG = "ChatRepository"
    }

    fun observeChats(): Flow<List<Chat>> =
        chatDao.observeAllChats().map { it.map { e -> e.toDomain() } }

    /** Верхушка списка чатов: ровно [limit] самых свежих. */
    fun observeChatsWindow(limit: Int): Flow<List<Chat>> =
        chatDao.observeChatsWindow(limit).map { it.map { e -> e.toDomain() } }

    /** Поиск по всей таблице чатов, а не по загруженному окну. */
    fun searchChats(query: String, limit: Int): Flow<List<Chat>> =
        chatDao.searchChats(query, limit).map { it.map { e -> e.toDomain() } }

    /** Сколько всего чатов - чтобы знать, есть ли что досыпать. */
    fun observeChatCount(): Flow<Int> = chatDao.observeChatCount()

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

    /** Раунд 173: закрепы личного чата (без тем). */
    fun observePinnedChatMessages(chatId: String): Flow<List<Message>> =
        messageDao.observePinnedChatMessages(chatId).map { list -> list.map { e -> e.toDomain() } }

    /** Раунд 173: закрепить/открепить сообщение (личка и канальные посты). */
    suspend fun setMessagePinned(messageId: String, pinned: Boolean) {
        messageDao.updatePinned(
            messageId,
            pinned,
            if (pinned) System.currentTimeMillis() else null,
            null,
        )
    }

    /** Раунд 203: адресаты «Поделиться в APU» - друзья из списка чатов. */
    suspend fun forwardFriends(): List<com.vladimir.messenger.data.local.entity.ChatEntity> =
        chatDao.getAllChats()

    /** Раунд 203: чат по id - узел источника для маркера пересылки. */
    suspend fun forwardChatById(chatId: String): com.vladimir.messenger.data.local.entity.ChatEntity? =
        chatDao.getChatById(chatId)

    /** Раунд 203: чат друга по его узлу - тап по источнику пересылки. */
    suspend fun forwardChatByContact(contactId: String): com.vladimir.messenger.data.local.entity.ChatEntity? {
        if (contactId.isBlank()) return null
        chatDao.getChatByContactId(contactId)?.let { return it }
        return chatDao.getAllChats().firstOrNull { it.contactId.contains(contactId) }
    }

    suspend fun sendMessage(
        chatId: String,
        recipientId: String,
        content: String,
        /** р226: чужая строка идёт с СОБСТВЕННЫМ id - одинаковые id на обоих устройствах. */
        fixedMessageId: String? = null,
        /** р226: true - строка пришла от зеркала-партнёра, эхо назад не слать. */
        fromMirror: Boolean = false,
    ): Result<Message> {
        return try {
            val messageId = fixedMessageId ?: UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            // р226: это устройство - зеркало и активный партнёр в сети.
            // Шифросессия у узла одна, поэтому отправляет партнёр, а строка
            // остаётся здесь как PENDING до его эха «отправлено».
            if (!fromMirror) {
                val mirrorChannel = MirrorHub.routeOutgoing()
                if (mirrorChannel != null) {
                    val mirrorChat = chatDao.getChatById(chatId)
                    val mirrorRecipient = if (recipientId.isNotBlank()) recipientId else mirrorChat?.contactId ?: ""
                    if (mirrorRecipient.isNotBlank()) {
                        val mirrorEntity = MessageEntity(
                            id = messageId,
                            chatId = chatId,
                            senderId = "self",
                            content = content,
                            timestamp = timestamp,
                            isFromMe = true,
                            status = MessageStatus.PENDING.name,
                            channel = MessageChannel.UNKNOWN.name,
                            recipientId = mirrorRecipient,
                        )
                        messageDao.insertMessage(mirrorEntity)
                        chatDao.updateLastMessage(chatId, com.vladimir.messenger.util.ChatPreviews.human(content) ?: content, timestamp)
                        val offered = mirrorChannel.publishOutgoing(
                            MirrorRow(
                                id = messageId, chatId = chatId, contactName = mirrorChat?.contactName ?: "",
                                senderId = "self", content = content, timestamp = timestamp,
                                mine = true, recipientId = mirrorRecipient, status = MessageStatus.PENDING.name,
                            )
                        )
                        if (offered) {
                            Log.i(TAG, "🪞 sent via mirror partner: $messageId")
                            return Result.success(mirrorEntity.toDomain())
                        }
                        // Партнёр мигнул в момент отправки: строка останется
                        // PENDING и уйдёт обычным путём, когда это устройство
                        // само станет активным (насос р179).
                    }
                }
            }

            // ШАГ 1: Определить recipientId
            val chat = chatDao.getChatById(chatId)
            val rawId = if (recipientId.isBlank()) chat?.contactId ?: "" else recipientId
            
            
            val actualRecipientId = when {
                rawId.startsWith("pk_") -> rawId
                rawId.contains("node=pk_") -> "pk_" + rawId.substringAfter("node=pk_").substringBefore("&")
                else -> rawId
            }
            
            // Раунд 183 (аудит-4): отладочная роспись на КАЖДОЕ отправленное
            // сообщение - только в debug-сборке (в release строковые склейки
            // и logcat на каждое сообщение были лишними).
            if (com.vladimir.messenger.BuildConfig.DEBUG) {
                Log.i(TAG, "📨 ROUTING: chatId=$chatId '$recipientId' -> '$actualRecipientId'")
            }

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
            chatDao.updateLastMessage(chatId, com.vladimir.messenger.util.ChatPreviews.human(content) ?: content, timestamp)

            // ШАГ 3: Rust owns direct QUIC and the bounded persistent MQTT/mesh offline path.
            val sentDirectly = if (actualRecipientId.isNotBlank()) {
                Log.i(TAG, "🚀 SENDING via Rust: messageId=$messageId recipient=$actualRecipientId")
                // В IO: вызов ядра блокирующий (QUIC до 10 с), а сюда приходят
                // из viewModelScope, то есть с главного потока.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    RustBridge.sendMessage(messageId, chatId, actualRecipientId, content)
                }
            } else {
                Log.w(TAG, "❌ sendMessage: recipient id is blank for chatId=$chatId")
                false
            }

            Log.i(TAG, "sendMessage direct=$sentDirectly messageId=$messageId recipient=$actualRecipientId")

            // р242: отметка для диагностики - когда последний раз отправляли.
            com.vladimir.messenger.data.mirror.MirrorHub.noteOutgoing()
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

            // р226: отразить отправленное на зеркале второго устройства.
            if (!fromMirror) {
                MirrorHub.publishSentEcho(
                    id = messageId,
                    chatId = chatId,
                    content = content,
                    ts = timestamp,
                    recipientId = actualRecipientId,
                    status = if (sentDirectly) MessageStatus.SENT.name else MessageStatus.QUEUED_OFFLINE.name,
                )
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

    /**
     * Раунд 179: мягкий слив офлайн-очереди БЕЗ привязки к presence.
     * Раньше досыл запускался только «тяжёлым» пульсом обнаружения, а при
     * живой связи пульсы чаще 30 с считались лёгкими и пропусками - очередь
     * могла висеть, пока связь не мигнёт. Теперь служебный насос раз в
     * минуту пробует отправить до [limit] хвостов; пустая очередь - один
     * дешёвый индексный запрос, нагрузки почти нет. Дубли у получателя
     * сняты дедупликацией по id сообщения.
     */
    suspend fun pumpQueuedOffline(limit: Int = 20): Int {
        var sent = 0
        try {
            val queued = messageDao.getQueuedOfflineMessages(limit)
            for (msg in queued) {
                val chat = chatDao.getChatById(msg.chatId) ?: continue
                val peer = chat.contactId
                if (peer.isBlank()) continue
                val ok = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        RustBridge.sendMessage(msg.id, msg.chatId, peer, msg.content)
                    }
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    messageDao.updateMessageStatus(msg.id, MessageStatus.SENT.name)
                    sent++
                } else {
                    // Раунд 182 (аудит-3): первая же неудача - скорее всего
                    // адресат ещё недоступен, а попытка стоит до ~10 с.
                    // Остаток очереди - следующая минута, вместо десятков
                    // попыток подряд.
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pumpQueuedOffline failed: " + e.message)
        }
        return sent
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
            // Проверка паузы: не досылать всё подряд одному и тому же собеседнику чаще, чем раз в 5 минут.
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

    // ── р226: живое зеркало устройств одной личности ────────────────────────

    /** Свежесть переписки - обмен «кто отстал» между устройствами. */
    suspend fun maxMessageTimestamp(): Long = messageDao.maxTimestamp() ?: 0L

    /** Хвост переписки для догана отставшего устройства. */
    suspend fun mirrorRowsSince(since: Long, limit: Int): List<MirrorRow> =
        messageDao.messagesSince(since, limit)
            // Do not replay ACK rows left by an older install to a mirror peer.
            .filterNot { DeliveryAckWire.isPacket(it.content) }
            .map { e ->
                MirrorRow(
                    id = e.id,
                    chatId = e.chatId,
                    contactName = chatDao.getChatById(e.chatId)?.contactName ?: "",
                    senderId = e.senderId,
                    content = e.content,
                    timestamp = e.timestamp,
                    mine = e.isFromMe,
                    recipientId = e.recipientId,
                    status = e.status,
                )
            }

    /** Неотправленные исходящие тени - перевыслать активному партнёру. */
    suspend fun mirrorPendingOutgoing(limit: Int): List<MirrorRow> =
        messageDao.pendingMirrorOutgoing(limit).map { e ->
            MirrorRow(
                id = e.id,
                chatId = e.chatId,
                contactName = chatDao.getChatById(e.chatId)?.contactName ?: "",
                senderId = "self",
                content = e.content,
                timestamp = e.timestamp,
                mine = true,
                recipientId = e.recipientId,
                status = e.status,
            )
        }

    /**
     * Входящее с зеркала. @return (chatId, имя чата) - для уведомления.
     * Чат ищется по узлу отправителя: id чатов на устройствах разные.
     */
    suspend fun applyMirrorIncoming(row: MirrorRow): Pair<String, String> {
        // р239: служебный пакет с партнёра тоже не сообщение.
        if (com.vladimir.messenger.util.ChatPreviews.isServicePacket(row.content)) {
            DeliveryAckWire.messageId(row.content)?.let { markOutgoingMessageDelivered(it) }
            return Pair("", "")
        }
        val chat = getOrCreateChat(
            row.senderId,
            row.contactName.ifBlank { com.vladimir.messenger.util.NodeIds.autoName(row.senderId) },
        )
        saveIncomingMessage(
            chatId = chat.id,
            senderId = row.senderId,
            messageId = row.id,
            content = row.content,
            timestamp = row.timestamp,
            channel = MessageChannel.UNKNOWN,
            recipientId = row.recipientId,
        )
        return Pair(chat.id, chat.contactName)
    }

    /** Отправленное с зеркала (эхо или доган): своя строка «отправлено». */
    suspend fun applyMirrorSent(row: MirrorRow) {
        // Служебный пакет не показываем и как своё сообщение. Если он всё же
        // попал в журнал зеркала, ACK по-прежнему обновляет исходящую строку.
        if (com.vladimir.messenger.util.ChatPreviews.isServicePacket(row.content)) {
            DeliveryAckWire.messageId(row.content)?.let { markOutgoingMessageDelivered(it) }
            return
        }
        // id чатов на устройствах разные - чат ищем по узлу получателя.
        val chat: Chat = if (row.recipientId.isNotBlank()) {
            getChatByContactId(row.recipientId)
                ?: getOrCreateChat(row.recipientId, row.contactName.ifBlank { com.vladimir.messenger.util.NodeIds.autoName(row.recipientId) })
        } else {
            val entity = chatDao.getChatById(row.chatId) ?: return
            Chat(id = entity.id, contactId = entity.contactId, contactName = entity.contactName)
        }
        // Статус уважаем чужой: эхо может принести и QUEUED_OFFLINE.
        val mirrorStatus = when (row.status) {
            MessageStatus.SENT.name, MessageStatus.QUEUED_OFFLINE.name, MessageStatus.PENDING.name -> row.status
            else -> MessageStatus.SENT.name
        }
        if (messageDao.messageExists(row.id)) {
            if (getMessageById(row.id)?.isFromMe == true) {
                messageDao.updateMessageStatus(row.id, mirrorStatus)
            }
            return
        }
        messageDao.insertMessageIgnore(
            MessageEntity(
                id = row.id,
                chatId = chat.id,
                senderId = "self",
                content = row.content,
                timestamp = row.timestamp,
                isFromMe = true,
                status = mirrorStatus,
                channel = MessageChannel.LOCAL.name,
                recipientId = row.recipientId,
            )
        )
        chatDao.updateLastMessage(chat.id, com.vladimir.messenger.util.ChatPreviews.human(row.content) ?: row.content, row.timestamp)
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
        // р239/р247: страховка на самом сохранении. Служебный пакет приложения
        // (например, «печатает…» или delivery ACK) в переписку не попадает ни
        // при каком пути приёма: даже если транспортный разборщик его не узнал,
        // мусорной строки не будет.
        if (com.vladimir.messenger.util.ChatPreviews.isServicePacket(content)) {
            // Последний общий страж: даже если входящий ACK обошёл разборщик
            // конкретного транспорта, он подтверждает строку, но не сохраняется.
            DeliveryAckWire.messageId(content)?.let { markOutgoingMessageDelivered(it) }
            val typing = com.vladimir.messenger.data.typing.TypingWire.parse(content)
            if (typing == true) {
                com.vladimir.messenger.data.typing.TypingPeer.peerTyping(senderId)
            } else if (typing == false) {
                com.vladimir.messenger.data.typing.TypingPeer.peerStopped(senderId)
            }
            Log.i(TAG, "service packet not saved to chat: " + content.take(16))
            return
        }
        // Защита от дубликатов (FULL SYNC может прислать то же сообщение повторно)
        val exists = messageDao.messageExists(messageId)
        if (exists) {
            Log.i(TAG, "⏩ SKIP duplicate msg $messageId (already in DB)")
            return
        }
        
        Log.i(TAG, "💾 saveIncomingMessage: chatId=$chatId msgId=$messageId ts=$timestamp")
        // р235: собеседник прислал сообщение - «печатает…» гаснет сразу, не
        // дожидаясь, пока индикатор протухнет сам (до 6 с).
        runCatching { com.vladimir.messenger.data.typing.TypingPeer.peerStopped(senderId) }
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
        chatDao.updateLastMessage(chatId, com.vladimir.messenger.util.ChatPreviews.human(content) ?: content, timestamp)
        // Раунд 150: бейдж непрочитанных на пузыре личного чата.
        // Раунд 156: служебные конверты роя (стикеры/миниатюры) - не
        // сообщения, непрочитанные не считают (владелец).
        if (!com.vladimir.messenger.util.ChatPreviews.isServiceEnvelope(content)) {
            runCatching { chatDao.incrementUnread(chatId) }
        }
    }

    /**
     * р242: досылка НЕДООТПРАВЛЕННОГО (PENDING) без опоры на события сети.
     *
     * Раньше досылка шла только по событию «увидели собеседника». Если событие
     * пропущено (перезапуск движка, смена роли зеркала, тихий реконнект), строка
     * так и висела «в ожидании» — со стороны это выглядит как «сообщение не
     * дошло». Теперь раз в минуту проходим по всем PENDING и пробуем снова.
     * Ошибки глушим: движка нет - просто попробуем в следующий раз.
     */
    suspend fun pumpPendingOutgoing(): Int {
        val pending = runCatching { messageDao.getPendingOutgoingMessages() }.getOrDefault(emptyList())
        if (pending.isEmpty()) return 0
        var sent = 0
        for (msg in pending) {
            val chat = runCatching { chatDao.getChatById(msg.chatId) }.getOrNull() ?: continue
            val peer = chat.contactId.ifBlank { msg.recipientId }
            if (peer.isBlank() || !peer.startsWith("pk_")) continue
            val ok = runCatching { RustBridge.sendMessage(msg.id, msg.chatId, peer, msg.content) }
                .getOrDefault(false)
            if (ok) {
                runCatching { messageDao.updateMessageStatus(msg.id, MessageStatus.SENT.name) }
                sent++
            }
        }
        if (sent > 0) Log.i(TAG, "pending pump: отправлено $sent из ${pending.size}")
        return sent
    }

    /**
     * р239: убрать из переписки служебные пакеты «печатает…», которые успели
     * сохраниться как сообщения (r235-r238). Заодно чинятся превью списка
     * чатов и счётчик непрочитанных.
     */
    suspend fun cleanupTypingJunk(): Int {
        val chats = runCatching { messageDao.chatsWithTypingJunk() }.getOrDefault(emptyList())
        val deleted = runCatching { messageDao.deleteTypingJunk() }.getOrDefault(0)
        if (deleted > 0) {
            Log.i(TAG, "typing junk removed: $deleted message(s) in ${chats.size} chat(s)")
            for (row in chats) {
                runCatching {
                    chatDao.decrementUnread(row.chatId, row.count)
                    val last = messageDao.getLatest(row.chatId)
                    chatDao.updateLastMessage(
                        row.chatId,
                        last?.let { com.vladimir.messenger.util.ChatPreviews.human(it.content) ?: it.content }.orEmpty(),
                        last?.timestamp ?: 0L,
                    )
                }
            }
        }
        return deleted
    }

    /**
     * Remove UUID-shaped delivery ACKs written as chat rows by receivers that
     * predate the direct-ACK handler. Repair the unread count and chat preview
     * so an update also cleans up the visible residue from that protocol bug.
     */
    suspend fun cleanupDeliveryAckJunk(): Int {
        val pattern = DeliveryAckWire.persistedUuidAckGlobPattern
        val chats = runCatching { messageDao.chatsWithDeliveryAckJunk(pattern) }
            .getOrDefault(emptyList())
        val oldAcks = runCatching { messageDao.deliveryAckJunkContents(pattern) }
            .getOrDefault(emptyList())
        // Preserve the receipt's meaning while removing its accidental chat row.
        oldAcks.forEach { content ->
            if (DeliveryAckWire.isPersistedUuidAck(content)) {
                DeliveryAckWire.messageId(content)?.let { markOutgoingMessageDelivered(it) }
            }
        }
        val deleted = runCatching { messageDao.deleteDeliveryAckJunk(pattern) }.getOrDefault(0)
        if (deleted > 0) {
            Log.i(TAG, "delivery ACK junk removed: $deleted message(s) in ${chats.size} chat(s)")
            for (row in chats) {
                runCatching {
                    chatDao.decrementUnread(row.chatId, row.count)
                    val last = messageDao.getLatest(row.chatId)
                    chatDao.updateLastMessage(
                        row.chatId,
                        last?.let { com.vladimir.messenger.util.ChatPreviews.human(it.content) ?: it.content }.orEmpty(),
                        last?.timestamp ?: 0L,
                    )
                }
            }
        }
        return deleted
    }

    /** р240: сколько своих сообщений ещё не ушло (диагностика синхронизации). */
    suspend fun countPendingOutgoing(): Int =
        runCatching { messageDao.countPendingOutgoing() }.getOrDefault(-1)

    suspend fun getChatById(chatId: String): Chat? {
        return chatDao.getChatById(chatId)?.toDomain()
    }

    /** Раунд 158: все личные чаты - выбор адресатов «Отправить в APU». */
    suspend fun getAllChats(): List<com.vladimir.messenger.domain.model.Chat> =
        chatDao.getAllChats().map { it.toDomain() }

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
            chatDao.updateLastMessage(chatId, com.vladimir.messenger.util.ChatPreviews.human(content) ?: content, timestamp)
        }
        return inserted != -1L
    }

    /**
     * Раунд 128: моя ССЫЛКА на гифку в чате (от моего лица). В чате карточка
     * одна; байты каждый телефон тихо подтягивает с хранителей. Превью в
     * списке чатов - аккуратное, без служебной строки.
     */
    /**
     * р245: содержимое свежих строк переписки - для насоса медиа в сервисе. Сам
     * разбор (какие это гифки и есть ли уже байты) живёт в сервисе: ему
     * доступен контекст, а библиотеке гифок он нужен.
     */
    suspend fun recentMessageContents(limit: Int = 24): List<String> =
        runCatching { messageDao.recentContents(limit) }.getOrDefault(emptyList())

    /**
     * р245: отправить ССЫЛКУ на гифку, когда сети у этого устройства нет.
     *
     * Раньше гифка с телефона-зеркала уходила вызовом ядра напрямую: движка
     * здесь нет, отправка возвращала false, строка вставала в очередь
     * «в ожидании» - и так и висела, потому что своей сети у тени не будет
     * никогда. Теперь кадр уходит партнёру-активному, он и отправляет.
     *
     * @return true - кадр принят партнёром (строка остаётся здесь как PENDING
     *         до его эха «отправлено»).
     */
    suspend fun sendGifRefViaMirror(
        chatId: String,
        recipientId: String,
        sha256: String,
        messageId: String,
    ): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val channel = com.vladimir.messenger.data.mirror.MirrorHub.routeOutgoing() ?: return@withContext false
            val chat = chatDao.getChatById(chatId) ?: return@withContext false
            val peer = recipientId.ifBlank { chat.contactId }
            if (peer.isBlank()) return@withContext false
            val content = com.vladimir.messenger.data.gif.GifLibrary.refContent(sha256)
            val timestamp = System.currentTimeMillis()
            val inserted = insertGifRefMessage(
                chatId = chatId,
                recipientId = peer,
                messageId = messageId,
                sha256 = sha256,
                timestamp = timestamp,
                status = MessageStatus.PENDING.name,
            )
            if (!inserted) return@withContext false
            val offered = channel.publishOutgoing(
                com.vladimir.messenger.data.mirror.MirrorRow(
                    id = messageId,
                    chatId = chatId,
                    contactName = chat.contactName,
                    senderId = "self",
                    content = content,
                    timestamp = timestamp,
                    mine = true,
                    recipientId = peer,
                    status = MessageStatus.PENDING.name,
                ),
            )
            if (offered) {
                Log.i(TAG, "🪞 gif ref sent via mirror partner: $messageId")
                // р245: и сразу байты - активный положит их в свою библиотеку и
                // объявит рой, иначе и у него, и у собеседника карточка пустая.
                com.vladimir.messenger.data.mirror.MirrorHub.pushGifBytes(sha256)
            }
            offered
        }

    suspend fun insertGifRefMessage(
        chatId: String,
        recipientId: String,
        messageId: String,
        sha256: String,
        timestamp: Long,
        /** Раунд 178: QUEUED_OFFLINE - собеседник офлайн, досылаем сами. */
        status: String = "LOCAL_FILE",
    ): Boolean {
        if (messageDao.messageExists(messageId)) return false
        val content = com.vladimir.messenger.data.gif.GifLibrary.refContent(sha256)
        val entity = MessageEntity(
            id = messageId,
            chatId = chatId,
            senderId = "self",
            content = content,
            timestamp = timestamp,
            isFromMe = true,
            status = status,
            channel = MessageChannel.STORE_FORWARD.name,
            recipientId = recipientId,
        )
        val inserted = messageDao.insertMessageIgnore(entity)
        if (inserted != -1L) {
            chatDao.updateLastMessage(chatId, "\ud83d\uddbc Гифка", timestamp)
        }
        return inserted != -1L
    }

    /** Раунд 128: пришла ссылка на гифку от собеседника - карточка в чате. */
    suspend fun insertReceivedGifRefMessage(
        chatId: String,
        senderId: String,
        messageId: String,
        sha256: String,
        timestamp: Long,
    ): Boolean {
        if (messageDao.messageExists(messageId)) return false
        val content = com.vladimir.messenger.data.gif.GifLibrary.refContent(sha256)
        val entity = MessageEntity(
            id = messageId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            timestamp = timestamp,
            isFromMe = false,
            status = "RECEIVED",
            channel = MessageChannel.STORE_FORWARD.name,
        )
        val inserted = messageDao.insertMessageIgnore(entity)
        if (inserted != -1L) {
            chatDao.updateLastMessage(chatId, "\ud83d\uddbc Гифка", timestamp)
            runCatching { chatDao.incrementUnread(chatId) }
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

    /**
     * Убрать чаты-призраки: собеседник - не узел, имя - заглушка от его же id
     * (см. [NodeIds.isStrayAutoContact]). Читаем весь список, пишем только
     * при находке. Возвращает число удалённых чатов.
     */
    suspend fun deleteStrayChats(): Int {
        var removed = 0
        for (chat in chatDao.getAllChats()) {
            if (!NodeIds.isStrayAutoContact(chat.contactId, chat.contactName)) continue
            messageDao.deleteMessagesForChat(chat.id)
            chatDao.deleteChatById(chat.id)
            removed++
            Log.i(TAG, "чат-призрак удалён: " + chat.contactName)
        }
        return removed
    }

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

    /** р230: есть ли уже строка с таким идентификатором (для зеркала файлов). */
    suspend fun messageExists(messageId: String): Boolean = messageDao.messageExists(messageId)

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status.name)
    }

    /** ACKs only acknowledge our outgoing rows; an old ACK cannot downgrade READ. */
    suspend fun markOutgoingMessageDelivered(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        return messageDao.markOutgoingMessageDelivered(messageId) > 0
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
        // Тема нужна уведомлениям: тап ведёт в место сообщения.
        topicId = topicId,
        isPinned = isPinned,
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
