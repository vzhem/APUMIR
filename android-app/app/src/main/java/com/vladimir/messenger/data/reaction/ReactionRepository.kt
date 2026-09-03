package com.vladimir.messenger.data.reaction

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageReactionDao
import com.vladimir.messenger.data.local.entity.MessageReactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Сводка реакций одного сообщения: значок, сколько раз и ставил ли я. */
data class ReactionSummary(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/**
 * Реакции на сообщения: и в личных чатах, и в группах и каналах.
 *
 * Своя реакция сначала пишется в базу (её видно сразу, даже без сети), затем
 * уходит собеседнику или всем участникам группы обычным сообщением-конвертом.
 * Приём разбирается до сохранения текста, поэтому конверт не попадает в чат.
 */
@Singleton
class ReactionRepository @Inject constructor(
    private val reactionDao: MessageReactionDao,
    private val chatDao: ChatDao,
    private val groupDao: GroupDao,
) {

    /** Реакции всего чата, разложенные по сообщениям. */
    fun observeChat(chatId: String): Flow<Map<String, List<ReactionSummary>>> =
        reactionDao.observeForChat(chatId)
            .map { list -> summarize(list) }
            .flowOn(Dispatchers.IO)

    private fun summarize(list: List<MessageReactionEntity>): Map<String, List<ReactionSummary>> =
        list.groupBy { it.messageId }.mapValues { (_, forMessage) ->
            forMessage.groupBy { it.emoji }
                .map { (emoji, rows) ->
                    ReactionSummary(
                        emoji = emoji,
                        count = rows.size,
                        mine = rows.any { it.nodeId == SELF },
                    )
                }
                .sortedByDescending { it.count }
        }

    /**
     * Поставить или снять свою реакцию. Повторное нажатие на тот же значок
     * снимает его - так же, как в привычных мессенджерах.
     */
    suspend fun toggle(chatId: String, messageId: String, emoji: String) {
        withContext(Dispatchers.IO) {
            val existing = reactionDao.get(messageId, SELF)
            val now = System.currentTimeMillis()
            val added = if (existing != null && existing.emoji == emoji) {
                reactionDao.remove(messageId, SELF)
                false
            } else {
                reactionDao.put(
                    MessageReactionEntity(
                        messageId = messageId,
                        nodeId = SELF,
                        chatId = chatId,
                        emoji = emoji,
                        atMs = now,
                    )
                )
                true
            }
            runCatching { broadcast(chatId, messageId, emoji, added, now) }
                .onFailure { Log.w(TAG, "reaction send failed: " + it.message) }
        }
    }

    /** Разослать реакцию: в группе - всем участникам, в личном чате - собеседнику. */
    private suspend fun broadcast(
        chatId: String,
        messageId: String,
        emoji: String,
        added: Boolean,
        atMs: Long,
    ) {
        val envelope = ReactionWire.build(chatId, messageId, emoji, added, atMs)
        val me = RustBridge.nodeId().orEmpty()
        val group = groupDao.getGroupById(chatId)
        val recipients = if (group != null) {
            groupDao.getMembers(chatId).filter { !it.isBanned }.map { it.nodeId }
                .filter { it.isNotBlank() && it != me }
        } else {
            listOfNotNull(chatDao.getChatById(chatId)?.contactId?.takeIf { it.isNotBlank() })
        }
        for (peer in recipients) {
            RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
        }
    }

    /**
     * Входящий конверт реакции. Возвращает true, если это была реакция и её
     * обработали - тогда служба не сохраняет текст как обычное сообщение.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!ReactionWire.isReactionPacket(text)) return false
        val packet = ReactionWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "reaction packet from $senderId is malformed, dropped")
            return true
        }
        if (senderId.isBlank()) return true
        withContext(Dispatchers.IO) {
            if (packet.added) {
                reactionDao.put(
                    MessageReactionEntity(
                        messageId = packet.messageId,
                        nodeId = senderId,
                        chatId = packet.chatId,
                        emoji = packet.emoji,
                        atMs = packet.atMs,
                    )
                )
            } else {
                reactionDao.remove(packet.messageId, senderId)
            }
        }
        return true
    }

    private companion object {
        const val TAG = "ReactionRepository"

        /** Своя реакция помечается так же, как свои сообщения в базе. */
        const val SELF = "self"
    }
}
