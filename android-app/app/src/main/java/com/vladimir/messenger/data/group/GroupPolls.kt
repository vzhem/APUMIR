package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.GroupPollDao
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupPollEntity
import com.vladimir.messenger.data.local.entity.GroupPollVoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Опросы групп, тем и каналов (р250) — вынесены из [GroupRepository]
 * (аудит 2026-10-26, п. 4.1: файл перевалил за 6900 строк).
 *
 * Шов выбран по владению данными: всё, что читает и пишет `group_polls` и
 * `group_poll_votes`, живёт здесь. Сети класса мало: веер голосов и закрытия
 * уходит через [deliver]/[recipients], идентичность и время приходят
 * функциями — поэтому класс не тянет за собой весь репозиторий.
 *
 * ВАЖНО: поведение перенесено один в один, включая тексты ошибок: их видят
 * пользователи (`ui/components/ApuPollCard.kt`), а тесты сверяют сообщения.
 * Ни одна проверка прав здесь не новая — они просто переехали.
 *
 * [pollDao] может быть null: так живут JVM-тесты ядра и сборки без хранилища
 * опросов — тогда сообщения работают, а опросы молча недоступны.
 */
internal class GroupPolls(
    private val pollDao: GroupPollDao?,
    private val groupDao: GroupDao,
    private val clock: () -> Long,
    private val nodeId: () -> String?,
    private val backgroundScope: CoroutineScope,
    private val deliver: suspend (groupId: String, envelope: String, recipients: List<String>) -> Unit,
    private val recipients: suspend (groupId: String, me: String) -> List<String>,
    private val memberMask: (GroupEntity) -> Long,
) {

    /** Опрос всегда привязан к сообщению: id опроса выводится из id сообщения. */
    fun pollIdOf(messageId: String): String = "p:$messageId"

    /** Записать опрос; повторная доставка того же опроса итоги не сбрасывает. */
    suspend fun storePoll(poll: GroupPollEntity) {
        val dao = pollDao ?: return
        if (dao.getPoll(poll.pollId) != null) return
        dao.insertPoll(poll)
    }

    /** Проголосовать (или отозвать голос): [choices] — номера вариантов, пусто — отозвать. */
    suspend fun votePoll(groupId: String, pollId: String, choices: List<Int>): Result<Unit> {
        val dao = pollDao ?: return Result.failure(IllegalStateException("Опросы недоступны"))
        val me = nodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (member.isBanned) return Result.failure(SecurityException("Вы ограничены в этой группе"))
        val poll = dao.getPoll(pollId)
            ?: return Result.failure(IllegalStateException("Опрос не найден"))
        if (poll.groupId != groupId) return Result.failure(IllegalStateException("Опрос из другой группы"))
        if (poll.closed) return Result.failure(SecurityException("Опрос закрыт"))
        val variants = GroupWire.decodeOptions(poll.optionsCsv)
        val clean = choices.filter { it in variants.indices }.distinct()
        if (!poll.multiChoice && clean.size > 1) {
            return Result.failure(IllegalArgumentException("В этом опросе можно выбрать только один вариант"))
        }
        dao.putVote(
            GroupPollVoteEntity(
                pollId = pollId,
                voterId = me,
                voterName = member.displayName,
                choicesCsv = clean.joinToString(","),
                atMs = clock(),
            ),
        )
        backgroundScope.launch {
            runCatching {
                deliver(
                    groupId,
                    GroupWire.buildPollVote(groupId, pollId, me, member.displayName, clean),
                    recipients(groupId, me),
                )
            }.onFailure { Log.w(TAG, "poll vote fanout failed: ${it.message}") }
        }
        return Result.success(Unit)
    }

    /** Закрыть опрос: автор, владелец или администратор. */
    suspend fun closePoll(groupId: String, pollId: String): Result<Unit> {
        val dao = pollDao ?: return Result.failure(IllegalStateException("Опросы недоступны"))
        val me = nodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        val poll = dao.getPoll(pollId)
            ?: return Result.failure(IllegalStateException("Опрос не найден"))
        if (poll.groupId != groupId) return Result.failure(IllegalStateException("Опрос из другой группы"))
        val isAdmin = GroupRole.isAdminOrOwner(member.role)
        if (!isAdmin && poll.creatorId != me) {
            return Result.failure(SecurityException("Закрыть опрос вправе его автор или администратор"))
        }
        if (poll.closed) return Result.success(Unit)
        dao.closePoll(pollId)
        backgroundScope.launch {
            runCatching {
                deliver(groupId, GroupWire.buildPollClose(groupId, pollId), recipients(groupId, me))
            }.onFailure { Log.w(TAG, "poll close fanout failed: ${it.message}") }
        }
        return Result.success(Unit)
    }

    /** Опросы группы (канала) в виде доменных моделей: ключ — id сообщения. */
    fun observePolls(groupId: String): Flow<Map<String, PollSummary>> {
        val dao = pollDao ?: return flowOf<Map<String, PollSummary>>(emptyMap())
        val me = nodeId().orEmpty()
        return combine(
            dao.observeGroupPolls(groupId),
            dao.observeGroupVotes(groupId),
        ) { polls, votes ->
            val byPoll = votes.groupBy { it.pollId }
            polls.associate { row -> row.messageId to toPollSummary(row, byPoll[row.pollId].orEmpty(), me) }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Вместе с сообщением уходит и приклеенный к нему опрос с голосами —
     * иначе карточка осталась бы висеть без своего сообщения.
     */
    suspend fun deleteForMessage(messageId: String) {
        pollDao?.deleteByMessage(messageId)
    }

    /**
     * Опросы и голоса уходят вместе с группой; иначе карточки вернулись бы
     * в ленту при повторном вступлении.
     */
    suspend fun deleteForGroup(groupId: String) {
        pollDao?.deleteGroupPolls(groupId)
    }

    private fun toPollSummary(
        row: GroupPollEntity,
        votes: List<GroupPollVoteEntity>,
        me: String,
    ): PollSummary {
        val options = GroupWire.decodeOptions(row.optionsCsv)
        val counts = IntArray(options.size)
        for (vote in votes) {
            for (index in vote.choices()) {
                if (index in counts.indices) counts[index]++
            }
        }
        val mine = votes.firstOrNull { it.voterId == me }?.choices().orEmpty()
        return PollSummary(
            pollId = row.pollId,
            groupId = row.groupId,
            topicId = row.topicId,
            messageId = row.messageId,
            question = row.question,
            options = options.mapIndexed { index, text -> PollOption(index, text, counts.getOrNull(index) ?: 0) },
            anonymous = row.anonymous,
            multiChoice = row.multiChoice,
            creatorId = row.creatorId,
            createdAtMs = row.createdAtMs,
            closed = row.closed,
            isMine = row.creatorId == me,
            myChoices = mine,
            // Анонимный опрос показывает только числа: имена хранятся, чтобы
            // отличать свой голос от чужих, но наружу не отдаются.
            voters = if (row.anonymous) {
                emptyList()
            } else {
                votes.map { PollVoter(it.voterId, it.voterName, it.choices()) }
            },
        )
    }

    suspend fun handlePoll(senderId: String, packet: GroupWire.Packet.Poll) {
        val dao = pollDao ?: return
        val me = nodeId().orEmpty()
        if (me.isBlank()) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (groupDao.getMember(packet.groupId, me) == null) return
        val author = groupDao.getMember(packet.groupId, senderId)
        val authorIsAdmin = author?.let { GroupRole.isAdminOrOwner(it.role) } == true
        // Право «Опросы» проверяем по автору: администраторы и владелец вне
        // общих ограничений участников (как с закрепами).
        if (!authorIsAdmin) {
            val authorMask = GroupPermissions.effectiveMemberPermissions(
                author?.permissions ?: 0L,
                memberMask(group),
            )
            if (!GroupPermissions.has(authorMask, GroupPermissions.Member.SEND_POLLS)) {
                Log.w(TAG, "poll from member without right dropped poll=${packet.pollId}")
                return
            }
        }
        // Повторная доставка (ретрансляция, досылка новичку) итоги не сбрасывает.
        if (dao.getPoll(packet.pollId) != null) return
        dao.insertPoll(
            GroupPollEntity(
                pollId = packet.pollId,
                groupId = packet.groupId,
                topicId = packet.topicId,
                messageId = packet.messageId,
                question = packet.question,
                optionsCsv = GroupWire.encodeOptions(packet.options),
                anonymous = packet.anonymous,
                multiChoice = packet.multiChoice,
                creatorId = senderId,
                createdAtMs = clock(),
            ),
        )
        Log.i(TAG, "poll in group=${packet.groupId} poll=${packet.pollId} options=${packet.options.size}")
    }

    suspend fun handlePollVote(senderId: String, packet: GroupWire.Packet.PollVote) {
        val dao = pollDao ?: return
        val me = nodeId().orEmpty()
        if (me.isBlank()) return
        if (groupDao.getMember(packet.groupId, me) == null) return
        val poll = dao.getPoll(packet.pollId) ?: return
        if (poll.groupId != packet.groupId) return
        if (poll.closed) return
        // В анонимный опрос имена не показываем, но храним: иначе нельзя
        // отличить переголосование от нового участника.
        val name = if (poll.anonymous) "" else packet.voterName
        val size = GroupWire.decodeOptions(poll.optionsCsv).size
        val clean = if (poll.multiChoice) {
            packet.choices.distinct().filter { it in 0 until size }
        } else {
            packet.choices.take(1).filter { it in 0 until size }
        }
        dao.putVote(
            GroupPollVoteEntity(
                pollId = packet.pollId,
                voterId = packet.voterId,
                voterName = name,
                choicesCsv = clean.joinToString(","),
                atMs = clock(),
            ),
        )
    }

    suspend fun handlePollClose(senderId: String, packet: GroupWire.Packet.PollClose) {
        val dao = pollDao ?: return
        val me = nodeId().orEmpty()
        if (me.isBlank()) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (groupDao.getMember(packet.groupId, me) == null) return
        val poll = dao.getPoll(packet.pollId) ?: return
        if (poll.groupId != packet.groupId) return
        // Закрыть вправе автор опроса, владелец группы и администратор.
        if (senderId != poll.creatorId && senderId != group.ownerId) {
            val sender = groupDao.getMember(packet.groupId, senderId)
            if (sender == null || !GroupRole.isAdminOrOwner(sender.role)) return
        }
        dao.closePoll(packet.pollId)
    }

    private companion object {
        private const val TAG = "GroupPolls"
    }
}
