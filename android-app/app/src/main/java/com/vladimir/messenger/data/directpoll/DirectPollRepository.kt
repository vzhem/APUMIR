package com.vladimir.messenger.data.directpoll

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.group.PollOption
import com.vladimir.messenger.data.group.PollSummary
import com.vladimir.messenger.data.group.PollVoter
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.DirectPollDao
import com.vladimir.messenger.data.local.entity.DirectPollEntity
import com.vladimir.messenger.data.local.entity.DirectPollVoteEntity
import com.vladimir.messenger.data.mirror.MirrorHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Опросы личных чатов (ранг 3).
 *
 * Устройство нарочно простое: вопрос — обычное сообщение, а варианты, автор и
 * голоса живут в [DirectPollDao]. Ничего не пересылается «внутри» сообщения,
 * поэтому офлайн-очередь, зеркало и старые версии продолжают работать: телефон
 * прежней сборки увидит просто вопрос.
 *
 * Обмен итогами — конверты [DirectPollWire] (open / vote / close). Каждый
 * телефон хранит свой голос как `self`, голос собеседника — под его узлом,
 * поэтому итоги сходятся без сервера и переживают перезапуск.
 */
@Singleton
class DirectPollRepository @Inject constructor(
    private val pollDao: DirectPollDao,
    private val chatDao: ChatDao,
) {

    /** Карточки опросов чата, готовые к отрисовке. */
    fun observeChat(chatId: String): Flow<Map<String, PollSummary>> =
        combine(
            pollDao.observeChatPolls(chatId),
            pollDao.observeChatVotes(chatId),
        ) { polls, votes ->
            val byPoll = votes.groupBy { it.pollId }
            polls.associate { row ->
                row.pollId to toSummary(row, byPoll[row.pollId].orEmpty())
            }
        }.flowOn(Dispatchers.IO)

    /** Опрос по id сообщения (карточка ищет его по строке переписки). */
    suspend fun pollForMessage(messageId: String): PollSummary? = withContext(Dispatchers.IO) {
        val row = pollDao.getPoll(DirectPollWire.pollIdOf(messageId)) ?: return@withContext null
        toSummary(row, pollDao.votesFor(row.pollId))
    }

    /**
     * Создать опрос: сначала локальная карточка (её видно сразу), затем
     * конверт `open` собеседнику. Сам вопрос отправляет вызывающий экран —
     * так сообщение остаётся обычным и попадает во все очереди доставки.
     *
     * @return описатель опроса для отправки, или null, если черновик не годен
     */
    suspend fun create(
        chatId: String,
        messageId: String,
        question: String,
        options: List<String>,
        anonymous: Boolean,
        creatorId: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = DirectPollTally.normalize(question, options)
            ?: return@withContext Result.failure(IllegalArgumentException("poll draft is not valid"))
        val (cleanQuestion, cleanOptions) = clean
        val pollId = DirectPollWire.pollIdOf(messageId)
        val now = System.currentTimeMillis()
        pollDao.insertPoll(
            DirectPollEntity(
                pollId = pollId,
                chatId = chatId,
                messageId = messageId,
                question = cleanQuestion,
                optionsCsv = cleanOptions.joinToString(",") { DirectPollWire.enc(it) },
                anonymous = anonymous,
                creatorId = creatorId,
                createdAtMs = now,
            ),
        )
        val envelope = DirectPollWire.buildOpen(
            pollId = pollId,
            messageId = messageId,
            chatId = chatId,
            question = cleanQuestion,
            options = cleanOptions,
            anonymous = anonymous,
            atMs = now,
        )
        Result.success(envelope)
    }

    /**
     * Голос: пустой [choices] — отозвать свой голос. Свой голос пишется сразу,
     * собеседнику уходит конверт `vote`.
     *
     * @return конверт для отправки или null, если голосовать нельзя
     */
    suspend fun vote(chatId: String, pollId: String, choices: List<Int>): String? =
        withContext(Dispatchers.IO) {
            val poll = pollDao.getPoll(pollId) ?: return@withContext null
            if (poll.closed) return@withContext null
            val size = decodeOptions(poll.optionsCsv).size
            val clean = choices.distinct().filter { DirectPollTally.isValidChoice(size, it) }
            // Один выбор: опрос личного чата не поддерживает «несколько
            // вариантов» — это осознанное упрощение, чтобы итоги двух голосов
            // оставались читаемыми.
            val final = if (clean.isEmpty()) emptyList() else listOf(clean.first())
            val now = System.currentTimeMillis()
            pollDao.putVote(
                DirectPollVoteEntity(
                    pollId = poll.pollId,
                    voterId = SELF,
                    choicesCsv = final.joinToString(","),
                    atMs = now,
                ),
            )
            DirectPollWire.buildVote(
                pollId = poll.pollId,
                messageId = poll.messageId,
                chatId = chatId,
                choices = final,
                atMs = now,
            )
        }

    /** Мой текущий выбор по опросу (для окна результатов и тестов). */
    suspend fun myChoices(pollId: String): List<Int> = withContext(Dispatchers.IO) {
        pollDao.votesFor(pollId).firstOrNull { it.voterId == SELF }?.choices().orEmpty()
    }

    /** Закрыть опрос: только автор (в личном чате — отправитель вопроса). */
    suspend fun close(pollId: String): String? = withContext(Dispatchers.IO) {
        val poll = pollDao.getPoll(pollId) ?: return@withContext null
        if (poll.closed) return@withContext null
        pollDao.closePoll(poll.pollId)
        DirectPollWire.buildClose(
            pollId = poll.pollId,
            messageId = poll.messageId,
            chatId = poll.chatId,
            atMs = System.currentTimeMillis(),
        )
    }

    /**
     * Входящий конверт опроса. Возвращает true, если это был опрос и его
     * обработали — тогда служба не сохраняет строку как текст в переписке.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!DirectPollWire.isPollPacket(text)) return false
        if (senderId.isBlank()) return true
        DirectPollWire.parseOpen(text)?.let { packet ->
            withContext(Dispatchers.IO) { applyOpen(senderId, packet) }
            return true
        }
        DirectPollWire.parseVote(text)?.let { packet ->
            withContext(Dispatchers.IO) { applyVote(senderId, packet) }
            return true
        }
        DirectPollWire.parseClose(text)?.let { packet ->
            withContext(Dispatchers.IO) { applyClose(senderId, packet) }
            return true
        }
        Log.w(TAG, "direct poll packet from $senderId is malformed, dropped")
        return true
    }

    /**
     * Своё действие, сделанное на партнёрском устройстве: записываем от своего
     * имени. В сеть ничего не уходит — конверт отправляет вызывающий.
     */
    suspend fun applyMirrorOutgoing(peerId: String, text: String): Boolean {
        if (!DirectPollWire.isPollPacket(text)) return false
        withContext(Dispatchers.IO) {
            val localChatId = chatDao.getChatByContactId(peerId)?.id
            DirectPollWire.parseOpen(text)?.let { applyOpen(peerId, it, forcedChatId = localChatId) }
                ?: DirectPollWire.parseVote(text)?.let { applyVote(peerId, it, voteAsSelf = true) }
                ?: DirectPollWire.parseClose(text)?.let { applyClose(peerId, it) }
        }
        return true
    }

    private suspend fun applyOpen(
        senderId: String,
        packet: DirectPollWire.Open,
        forcedChatId: String? = null,
    ) {
        if (pollDao.getPoll(packet.pollId) != null) return
        // chatId в конверте — идентификатор автора: у нас чат заводится
        // отдельно, поэтому переводим его в свой по узлу отправителя.
        val localChatId = forcedChatId
            ?: chatDao.getChatByContactId(senderId)?.id
            ?: packet.chatId
        pollDao.insertPoll(
            DirectPollEntity(
                pollId = packet.pollId,
                chatId = localChatId,
                messageId = packet.messageId,
                question = packet.question.take(DirectPollTally.MAX_QUESTION_CHARS),
                optionsCsv = packet.options
                    .take(DirectPollTally.MAX_OPTIONS)
                    .joinToString(",") { DirectPollWire.enc(it) },
                anonymous = packet.anonymous,
                creatorId = senderId,
                createdAtMs = packet.atMs,
            ),
        )
        Log.i(TAG, "direct poll open poll=${packet.pollId} options=${packet.options.size}")
    }

    private suspend fun applyVote(
        senderId: String,
        packet: DirectPollWire.Vote,
        voteAsSelf: Boolean = false,
    ) {
        val poll = pollDao.getPoll(packet.pollId) ?: return
        if (poll.closed) return
        val size = decodeOptions(poll.optionsCsv).size
        val clean = packet.choices.distinct().filter { DirectPollTally.isValidChoice(size, it) }
        val final = if (clean.isEmpty()) emptyList() else listOf(clean.first())
        pollDao.putVote(
            DirectPollVoteEntity(
                pollId = poll.pollId,
                voterId = if (voteAsSelf) SELF else senderId,
                choicesCsv = final.joinToString(","),
                atMs = packet.atMs,
            ),
        )
    }

    private suspend fun applyClose(senderId: String, packet: DirectPollWire.Close) {
        val poll = pollDao.getPoll(packet.pollId) ?: return
        // Закрыть вправе автор; чужое «закрыть» игнорируем.
        if (poll.creatorId.isNotBlank() && poll.creatorId != senderId) return
        pollDao.closePoll(poll.pollId)
    }

    /** Опрос закрывается вместе с сообщением: висящая карточка хуже пустоты. */
    suspend fun deleteForMessage(messageId: String) = withContext(Dispatchers.IO) {
        pollDao.deleteByMessage(messageId)
    }

    private fun toSummary(
        row: DirectPollEntity,
        votes: List<DirectPollVoteEntity>,
    ): PollSummary {
        val options = decodeOptions(row.optionsCsv)
        val counts = IntArray(options.size)
        for (vote in votes) {
            for (index in vote.choices()) {
                if (index in counts.indices) counts[index]++
            }
        }
        val mine = votes.firstOrNull { it.voterId == SELF }?.choices().orEmpty()
        return PollSummary(
            pollId = row.pollId,
            // В личном чате группы нет: поле остаётся пустым, а карточка
            // опирается на messageId.
            groupId = "",
            topicId = "",
            messageId = row.messageId,
            question = row.question,
            options = options.mapIndexed { index, text ->
                PollOption(index, text, counts.getOrNull(index) ?: 0)
            },
            anonymous = row.anonymous,
            multiChoice = false,
            creatorId = row.creatorId,
            createdAtMs = row.createdAtMs,
            closed = row.closed,
            isMine = row.creatorId.isNotBlank() && row.creatorId == nodeId(),
            myChoices = mine,
            voters = if (row.anonymous) {
                emptyList()
            } else {
                // В личном чате имена не нужны: участников двое, и подпись
                // «проголосовали N из 2» точнее любого списка.
                votes.filter { it.choices().isNotEmpty() }
                    .map { PollVoter(it.voterId, "", it.choices()) }
            },
        )
    }

    private fun decodeOptions(csv: String): List<String> =
        if (csv.isBlank()) emptyList()
        else csv.split(',').mapNotNull { DirectPollWire.dec(it)?.trim() }.filter { it.isNotBlank() }

    private fun nodeId(): String = runCatching { RustBridge.nodeId() }.getOrNull().orEmpty()

    /**
     * Отправить конверт собеседнику. Личный чат — один получатель, поэтому
     * бюджет роя не трогаем: опрос важнее фонового значка.
     */
    suspend fun deliver(chatId: String, peerId: String, envelope: String): Boolean {
        val peer = peerId.takeIf { it.isNotBlank() }
            ?: chatDao.getChatById(chatId)?.contactId?.takeIf { it.isNotBlank() }
            ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                // Устройство-зеркало отдаёт действие активному: сессии у него нет.
                if (MirrorHub.deliverAction(peerId = peer, groupId = "", chatId = chatId, text = envelope)) {
                    return@runCatching true
                }
                RustBridge.sendMessage(UUID.randomUUID().toString(), chatId, peer, envelope)
                MirrorHub.publishOwnAction(peerId = peer, groupId = "", chatId = chatId, text = envelope)
                true
            }.onFailure { Log.w(TAG, "direct poll send failed: ${it.message}") }
                .getOrDefault(false)
        }
    }

    private companion object {
        private const val TAG = "DirectPollRepository"

        /** Свой голос помечается так же, как свои сообщения в базе. */
        const val SELF = "self"
    }
}
