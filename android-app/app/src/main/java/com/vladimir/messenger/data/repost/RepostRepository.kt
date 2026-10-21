package com.vladimir.messenger.data.repost

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageRepostDao
import com.vladimir.messenger.data.local.entity.MessageRepostEntity
import com.vladimir.messenger.data.mirror.MirrorHub
import com.vladimir.messenger.data.swarm.SwarmBudget
import com.vladimir.messenger.data.swarm.SwarmLane
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Счётчик репостов: сколько раз сообщение переслали дальше.
 *
 * Устроен как реакции и просмотры постов: факт пересылки — строка в таблице,
 * счётчик — количество строк. Пересылающий записывает факт у себя и шлёт
 * служебный конверт [RepostWire] тем, у кого сообщение живёт: собеседнику
 * личного чата или участникам группы/канала (на большом канале — только
 * сборщикам, остальные получат число в сводке счётчиков постов).
 */
@Singleton
class RepostRepository @Inject constructor(
    private val repostDao: MessageRepostDao,
    private val chatDao: ChatDao,
    private val groupDao: GroupDao,
    private val swarmBudget: SwarmBudget,
    private val counters: com.vladimir.messenger.data.channel.PostCounterRepository,
) {

    /** Счётчики репостов чата: id сообщения → сколько раз его переслали. */
    fun observeCounts(chatId: String): Flow<Map<String, Int>> =
        repostDao.observeForChat(chatId)
            .map { rows -> rows.groupingBy { it.messageId }.eachCount() }
            .flowOn(Dispatchers.IO)

    /**
     * Я только что переслал это сообщение: записать факт и разослать сигнал.
     * Зовётся после успешной отправки пересланной копии.
     */
    suspend fun noteForward(chatId: String, messageId: String) {
        if (chatId.isBlank() || messageId.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val eventId = UUID.randomUUID().toString()
                repostDao.put(
                    MessageRepostEntity(
                        messageId = messageId,
                        chatId = chatId,
                        eventId = eventId,
                        nodeId = RustBridge.nodeId().orEmpty().ifBlank { SELF },
                        atMs = now,
                    )
                )
                broadcast(chatId, messageId, eventId, now)
            }.onFailure { Log.w(TAG, "repost note failed: " + it.message) }
        }
    }

    /** Разослать сигнал: в группе - участникам/сборщикам, в личном чате - собеседнику. */
    private suspend fun broadcast(chatId: String, messageId: String, eventId: String, atMs: Long) {
        val envelope = RepostWire.build(chatId, messageId, eventId, atMs) ?: return
        val me = RustBridge.nodeId().orEmpty()
        val group = groupDao.getGroupById(chatId)
        if (group == null) {
            val peer = chatDao.getChatById(chatId)?.contactId?.takeIf { it.isNotBlank() } ?: return
            if (MirrorHub.deliverAction(peerId = peer, groupId = "", chatId = chatId, text = envelope)) return
            RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
            MirrorHub.publishOwnAction(peerId = peer, groupId = "", chatId = chatId, text = envelope)
            return
        }
        if (MirrorHub.deliverAction(peerId = "", groupId = chatId, chatId = chatId, text = envelope)) return
        MirrorHub.publishOwnAction(peerId = "", groupId = chatId, chatId = chatId, text = envelope)
        val recipients = counters.signalTargets(group, me)
        var sent = 0
        for (peer in recipients) {
            if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) break
            RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
            sent++
        }
        if (sent < recipients.size) {
            Log.i(TAG, "repost fanout capped: $sent/${recipients.size} (signal budget)")
        }
    }

    /**
     * Входящий конверт репоста. Возвращает true, если это был он и его
     * обработали - тогда служба не сохраняет текст как обычное сообщение.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!RepostWire.isRepostPacket(text)) return false
        val packet = RepostWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "repost packet from $senderId is malformed, dropped")
            return true
        }
        if (senderId.isBlank()) return true
        withContext(Dispatchers.IO) {
            // chatId в пакете - идентификатор чата ОТПРАВИТЕЛЯ; для группы он
            // общий, для личного чата переводим в свой по отправителю.
            val localChatId = groupDao.getGroupById(packet.chatId)?.let { packet.chatId }
                ?: chatDao.getChatByContactId(senderId)?.id
                ?: packet.chatId
            repostDao.put(
                MessageRepostEntity(
                    messageId = packet.messageId,
                    chatId = localChatId,
                    eventId = packet.eventId,
                    nodeId = senderId,
                    atMs = packet.atMs,
                )
            )
        }
        return true
    }

    /**
     * Пересылку сделали на партнёрском устройстве этой же личности:
     * записываем факт как свой и ничего в сеть не шлём (конверт разошлёт
     * вызывающий, когда устройство станет активным).
     */
    suspend fun applyMirrorOutgoing(peerId: String, groupId: String, text: String): Boolean {
        if (!RepostWire.isRepostPacket(text)) return false
        val packet = RepostWire.parse(text) ?: return true
        withContext(Dispatchers.IO) {
            val localChatId = if (groupId.isNotBlank()) {
                groupId
            } else {
                chatDao.getChatByContactId(peerId)?.id ?: packet.chatId
            }
            repostDao.put(
                MessageRepostEntity(
                    messageId = packet.messageId,
                    chatId = localChatId,
                    eventId = packet.eventId,
                    nodeId = SELF,
                    atMs = packet.atMs,
                )
            )
        }
        return true
    }

    private companion object {
        const val TAG = "RepostRepository"
        const val SELF = "self"
    }
}
