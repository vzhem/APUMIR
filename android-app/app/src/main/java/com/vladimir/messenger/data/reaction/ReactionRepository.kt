package com.vladimir.messenger.data.reaction

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageReactionDao
import com.vladimir.messenger.data.local.entity.MessageReactionEntity
import com.vladimir.messenger.data.swarm.SwarmBudget
import com.vladimir.messenger.data.swarm.SwarmLane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.vladimir.messenger.data.mirror.MirrorHub

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
    private val swarmBudget: SwarmBudget,
    private val counters: com.vladimir.messenger.data.channel.PostCounterRepository,
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
     * Поставить или снять один свой значок. На сообщении может стоять до
     * [MAX_PER_USER] разных своих реакций: повторное нажатие на тот же значок
     * снимает только его, остальные не трогаются. Шестой разный значок не
     * встанет — сначала нужно снять один из пяти.
     */
    suspend fun toggle(chatId: String, messageId: String, emoji: String) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val mine = reactionDao.getAllForUser(messageId, SELF)
            val added = when {
                mine.any { it.emoji == emoji } -> {
                    reactionDao.removeByEmoji(messageId, SELF, emoji)
                    false
                }
                mine.size >= MAX_PER_USER -> {
                    Log.i(TAG, "reaction limit: already $MAX_PER_USER on $messageId")
                    return@withContext
                }
                else -> {
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
            }
            runCatching { broadcast(chatId, messageId, emoji, added, now) }
                .onFailure { Log.w(TAG, "reaction send failed: " + it.message) }
        }
    }

    /** Снять все свои реакции с сообщения, сколько бы их ни стояло. */
    suspend fun removeMine(chatId: String, messageId: String) {
        withContext(Dispatchers.IO) {
            val mine = reactionDao.getAllForUser(messageId, SELF)
            if (mine.isEmpty()) return@withContext
            reactionDao.remove(messageId, SELF)
            for (row in mine) {
                runCatching {
                    broadcast(chatId, messageId, row.emoji, false, System.currentTimeMillis())
                }.onFailure { Log.w(TAG, "reaction remove send failed: " + it.message) }
            }
        }
    }

    /** Мои значки на этом сообщении (до пяти). */
    suspend fun myEmojis(messageId: String): Set<String> =
        withContext(Dispatchers.IO) {
            reactionDao.getAllForUser(messageId, SELF).map { it.emoji }.toSet()
        }

    /** Мой первый значок на этом сообщении или null (старым местам). */
    suspend fun myEmoji(messageId: String): String? =
        withContext(Dispatchers.IO) { reactionDao.get(messageId, SELF)?.emoji }

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
        if (group == null) {
            // Личный чат: один собеседник, бюджет роя не трогаем.
            val peer = chatDao.getChatById(chatId)?.contactId?.takeIf { it.isNotBlank() } ?: return
            // р228: устройство-зеркало отдаёт действие активному - сессии у
            // него нет, а у активного она одна на аккаунт.
            if (MirrorHub.deliverAction(peerId = peer, groupId = "", chatId = chatId, text = envelope)) return
            RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
            // р229: и своё действие активного - партнёрскому устройству.
            MirrorHub.publishOwnAction(peerId = peer, groupId = "", chatId = chatId, text = envelope)
            return
        }
        // р228: групповое действие тени - активный разошлёт участникам.
        if (MirrorHub.deliverAction(peerId = "", groupId = chatId, chatId = chatId, text = envelope)) return
        // р229: групповое действие, сделанное здесь, - и партнёрскому устройству.
        MirrorHub.publishOwnAction(peerId = "", groupId = chatId, chatId = chatId, text = envelope)
        // Группа: реакция - служебный пакет. Сначала своим и проверенным; когда
        // служебный бюджет телефона исчерпан, остальные её не получат - это
        // лучше, чем задерживать посты и сообщения ради значка. На большом
        // канале (рой, этап 3) получатели - только владелец и администраторы:
        // у них счётчик сходится, остальные спрашивают у них сводку.
        val recipients = counters.signalTargets(group, me)
        var sent = 0
        for (peer in recipients) {
            if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) break
            RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
            sent++
        }
        if (sent < recipients.size) {
            Log.i(TAG, "reaction fanout capped: $sent/${recipients.size} (signal budget)")
        }
    }

    /**
     * р228: реакцию поставили на ПАРТНЁРСКОМ устройстве той же личности -
     * активное устройство записывает её у себя как СВОЮ (иначе значок не
     * появится в его интерфейсе). В сеть ничего не уходит: конверт отправляет
     * вызывающий.
     */
    suspend fun applyMirrorOutgoing(peerId: String, groupId: String, text: String): Boolean {
        if (!ReactionWire.isReactionPacket(text)) return false
        val packet = ReactionWire.parse(text) ?: return true
        withContext(Dispatchers.IO) {
            val localChatId = if (groupId.isNotBlank()) {
                groupId
            } else {
                chatDao.getChatByContactId(peerId)?.id ?: packet.chatId
            }
            if (packet.added) {
                reactionDao.put(
                    MessageReactionEntity(
                        messageId = packet.messageId,
                        nodeId = SELF,
                        chatId = localChatId,
                        emoji = packet.emoji,
                        atMs = packet.atMs,
                    )
                )
            } else {
                reactionDao.removeByEmoji(packet.messageId, SELF, packet.emoji)
            }
        }
        return true
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
                // У отправителя тоже не больше пяти разных значков: шестой от
                // сломанного или вредного клиента не принимаем.
                val theirs = reactionDao.getAllForUser(packet.messageId, senderId)
                if (theirs.none { it.emoji == packet.emoji } && theirs.size >= MAX_PER_USER) {
                    return@withContext
                }
                // chatId из пакета - идентификатор чата ОТПРАВИТЕЛЯ, у нас он
                // другой: личные чаты заводятся на каждом телефоне отдельно.
                // Раньше реакция сохранялась с чужим идентификатором и не
                // показывалась - экран ищет реакции по своему чату. Для группы
                // идентификатор общий, поэтому он и подходит; для лички
                // переводим в свой чат по отправителю.
                val localChatId = groupDao.getGroupById(packet.chatId)?.let { packet.chatId }
                    ?: chatDao.getChatByContactId(senderId)?.id
                    ?: packet.chatId
                reactionDao.put(
                    MessageReactionEntity(
                        messageId = packet.messageId,
                        nodeId = senderId,
                        chatId = localChatId,
                        emoji = packet.emoji,
                        atMs = packet.atMs,
                    )
                )
            } else {
                reactionDao.removeByEmoji(packet.messageId, senderId, packet.emoji)
            }
        }
        return true
    }

    private companion object {
        const val TAG = "ReactionRepository"

        /** Своя реакция помечается так же, как свои сообщения в базе. */
        const val SELF = "self"

        /** Сколько разных значков один человек может поставить на сообщение. */
        const val MAX_PER_USER = 5
    }
}
