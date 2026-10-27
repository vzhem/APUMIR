package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupJoinRequestEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Заявки на вступление - вынесены из [GroupRepository] (аудит 2026-10-26,
 * п. 4.1).
 *
 * Заявка живёт дольше одного пакета: телефон хранит её в очереди
 * ([GroupJoinOutbox]) и в истории «Мои заявки» ([MyJoinRequestStore]),
 * досылает, пока владелец не ответит, а решение разносится по рою так, чтобы
 * каждый телефон применил его ровно один раз. Здесь вся эта жизнь: отправка,
 * повтор, снятие, решения и ответ на «решена ли моя заявка».
 *
 * Поведение перенесено один в один. Вступление в группу (создание участника,
 * роспись, счётчики) осталось в репозитории: оно приходит функцией [admit],
 * потому что это уже про состав группы, а не про заявку.
 */
internal class GroupJoinRequests(
    private val groupDao: GroupDao,
    private val joinOutbox: GroupJoinOutbox?,
    private val myJoinStore: MyJoinRequestStore?,
    private val clock: () -> Long,
    private val myNodeId: () -> String?,
    private val myName: () -> String,
    private val backgroundScope: CoroutineScope,
    private val deliver: suspend (groupId: String, envelope: String, recipients: List<String>) -> Unit,
    private val admit: suspend (group: GroupEntity, nodeId: String, displayName: String, slug: String) -> Unit,
    private val noteLimit: Int,
) {

    /**
     * Заявка на вступление: сначала в очередь телефона, потом первая попытка.
     * Неудачная попытка не теряет заявку - помпа повторит её, когда адресат
     * появится в сети (или через рой соседей).
     */
    suspend fun sendJoinRequestDurable(
        groupId: String,
        displayName: String,
        note: String,
        slug: String,
        recipients: List<String>,
        isChannel: Boolean = false,
        knownTitle: String = "",
    ) {
        val targets = recipients.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        val me = myNodeId() ?: return
        val now = clock()
        // История - лишь для экрана «Мои заявки». Её сбой не должен мешать отправке заявки.
        runCatching {
            myJoinStore?.upsert(
                MyJoinRequestStore.Entry(
                    groupId = groupId,
                    title = knownTitle,
                    isChannel = isChannel,
                    note = note,
                    requestedAtMs = now,
                    recipients = targets,
                )
            )
        }.onFailure { Log.w(TAG, "join history save failed: ${it.javaClass.simpleName}: ${it.message}") }
        touchMyJoin()
        // Свою копию храним на этом телефоне: видно, что заявка отправлена, и что с ней решили.
        // ВАЖНО: group_join_requests имеет FK на groups. Для чужой записи из
        // сетевого каталога строки groups ещё нет, поэтому её нельзя писать в
        // эту таблицу — именно это раньше роняло APU при «Вступить»/«Подписаться».
        if (joinRequestStorage(groupDao.getGroupById(groupId) != null) == JoinRequestStorage.LOCAL_GROUP_REQUESTS) {
            groupDao.insertJoinRequest(
                GroupJoinRequestEntity(
                    groupId = groupId,
                    nodeId = me,
                    displayName = displayName,
                    note = note,
                    requestedAtMs = now,
                    status = "SENT",
                )
            )
        } else {
            Log.i(TAG, "join request kept in history/outbox: group is not local yet, group=$groupId")
        }
        joinOutbox?.put(GroupJoinOutbox.Entry(groupId, displayName, note, slug, targets, now, 0L, 0))
        val envelope = GroupWire.buildJoinRequest(
            groupId = groupId, displayName = displayName, note = note, slug = slug, requestedAtMs = now,
        )
        runCatching { deliver(groupId, envelope, targets) }
        joinOutbox?.markTried(groupId, clock())
    }

    /** Ключи решений, которые этот телефон уже обработал или разослал (защита от кругов по рою). */
    private val seenJoinDecisions = LinkedHashSet<String>()

    /** Меняется при каждой правке истории заявок: экран «Мои заявки» перечитывает её. */
    private val myJoinVersion = MutableStateFlow(0L)

    fun touchMyJoin() {
        myJoinVersion.value = myJoinVersion.value + 1
    }

    /** Повтор заявки старше решения: её не возрождаем. Без времени (старый образец) - не отбрасываем. */
    fun isStaleJoinRequest(requestedAtMs: Long, decidedAtMs: Long?): Boolean =
        requestedAtMs > 0L && decidedAtMs != null && requestedAtMs <= decidedAtMs

    /** Решение владельца, которое этот телефон отправляет: помечаем ключ, чтобы не получить его эхо. */
    private suspend fun broadcastJoinDecision(groupId: String, nodeId: String, approved: Boolean) {
        val me = myNodeId() ?: return
        synchronized(seenJoinDecisions) { seenJoinDecisions.add("$groupId|$nodeId|$approved") }
        relayJoinDecision(groupId, nodeId, approved, exclude = setOf(me))
    }

    /**
     * Чужое решение по заявке: если я участник группы, обновляю свою копию заявки
     * и передаю решение дальше - адресату и нескольким участникам. Каждый телефон
     * ретранслирует одно решение один раз.
     */
    suspend fun handleForeignDecision(packet: GroupWire.Packet.JoinDecision, senderId: String) {
        val me = myNodeId() ?: return
        val key = "${packet.groupId}|${packet.nodeId}|${packet.approved}"
        val fresh = synchronized(seenJoinDecisions) {
            val added = seenJoinDecisions.add(key)
            while (seenJoinDecisions.size > MAX_SEEN_JOIN_DECISIONS) {
                seenJoinDecisions.remove(seenJoinDecisions.first())
            }
            added
        }
        if (!fresh) return
        groupDao.getMember(packet.groupId, me) ?: return
        // Свою копию заявки меняем только по решению от владельца или админа (ретрансляции
        // случайных участников не меняют статус), но передаём дальше, как и раньше.
        val senderMember = groupDao.getMember(packet.groupId, senderId)
        val trustedSender = senderMember != null && GroupPermissions.canInvite(senderMember.role, senderMember.permissions, 0L)
        if (trustedSender) groupDao.getJoinRequest(packet.groupId, packet.nodeId)?.takeIf { it.status == "PENDING" }?.let {
            groupDao.updateJoinRequestStatus(
                packet.groupId, packet.nodeId, if (packet.approved) "APPROVED" else "REJECTED", clock(), senderId,
            )
        }
        relayJoinDecision(packet.groupId, packet.nodeId, packet.approved, exclude = setOf(me, senderId))
    }

    /** Решение уходит адресату и не больше чем трём участникам группы. */
    private suspend fun relayJoinDecision(groupId: String, nodeId: String, approved: Boolean, exclude: Set<String>) {
        val members = groupDao.getMembers(groupId)
            .filter { !it.isBanned && it.nodeId !in exclude && it.nodeId != nodeId }
            .map { it.nodeId }
            .take(MAX_JOIN_DECISION_RELAY)
        val targets = (listOf(nodeId) + members).filter { it.isNotBlank() && it !in exclude }.distinct()
        if (targets.isEmpty()) return
        runCatching { deliver(groupId, GroupWire.buildJoinDecision(groupId, nodeId, approved), targets) }
    }

    /** Снять свою заявку: очередь перестаёт её досылать, история и запись в базе удаляются. */
    suspend fun cancelMyJoinRequest(groupId: String) {
        val me = myNodeId() ?: return
        joinOutbox?.remove(groupId)
        myJoinStore?.remove(groupId)
        groupDao.deleteJoinRequest(groupId, me)
        touchMyJoin()
    }

    /** Отправить заявку снова прямо сейчас (не дожидаясь помпы). false - заявки в очереди нет. */
    suspend fun resendMyJoinRequest(groupId: String): Boolean {
        val outbox = joinOutbox ?: return false
        val entry = outbox.all().firstOrNull { it.groupId == groupId } ?: return false
        val now = clock()
        val envelope = GroupWire.buildJoinRequest(
            groupId = entry.groupId,
            displayName = entry.displayName,
            note = entry.note,
            slug = entry.slug,
            requestedAtMs = entry.atMs,
        )
        runCatching { deliver(entry.groupId, envelope, entry.recipients) }
        outbox.markTried(entry.groupId, now)
        touchMyJoin()
        return true
    }

    /** Мои заявки, новые сверху. Перечитываются при каждом изменении истории. */
    fun observeMyJoinRequests(): Flow<List<MyJoinRequestSummary>> =
        myJoinVersion.map { withContext(Dispatchers.IO) { loadMyJoinRequests() } }

    private fun loadMyJoinRequests(): List<MyJoinRequestSummary> {
        val store = myJoinStore ?: return emptyList()
        val queued = joinOutbox?.all().orEmpty().associateBy { it.groupId }
        return store.all().sortedByDescending { it.requestedAtMs }.map { e ->
            val entry = queued[e.groupId]
            MyJoinRequestSummary(
                groupId = e.groupId,
                title = e.title,
                isChannel = e.isChannel,
                memberCount = e.memberCount,
                ownerName = e.ownerName,
                groupCreatedAtMs = e.groupCreatedAtMs,
                note = e.note,
                requestedAtMs = e.requestedAtMs,
                status = e.status,
                decidedAtMs = e.decidedAtMs,
                ownerReceivedAtMs = e.ownerReceivedAtMs,
                queued = entry != null,
                queueAttempts = entry?.attempts ?: 0,
                lastTryMs = entry?.lastTryMs ?: 0L,
                expiresAtMs = if (entry != null) entry.atMs + JOIN_TTL_MS else 0L,
            )
        }
    }

    /**
     * Помпа заявок на вступление: досылает сохранённые заявки владельцам и
     * администраторам, пока те не ответят. Заявка снимается, если абонент уже
     * участник этой группы, или если срок хранения (7 дней) истёк.
     */
    /** Адресаты моей заявки: владелец и админы, которым я её отправлял. */
    fun isAddresseeOfMyRequest(groupId: String, nodeId: String): Boolean {
        val stored = myJoinStore?.get(groupId)?.recipients.orEmpty()
        val queued = joinOutbox?.all()?.firstOrNull { it.groupId == groupId }?.recipients.orEmpty()
        return nodeId in stored || nodeId in queued
    }

    /** Спросить адресатов напрямую, решена ли моя заявка. Ответ придёт прямым решением. */
    suspend fun requestJoinStatus(groupId: String) {
        val me = myNodeId() ?: return
        val stored = myJoinStore?.get(groupId)?.recipients.orEmpty()
        val queued = joinOutbox?.all()?.firstOrNull { it.groupId == groupId }?.recipients.orEmpty()
        val targets = (stored + queued).filter { it.isNotBlank() && it != me }.distinct()
        if (targets.isEmpty()) return
        runCatching { deliver(groupId, GroupWire.buildJoinStatusQuery(groupId, me), targets) }
    }

    suspend fun pumpJoinRequests() {
        val outbox = joinOutbox ?: return
        val now = clock()
        val me = myNodeId() ?: return
        // Офлайн-заявки: при выходе в сеть спрашиваем адресатов, решены ли они.
        myJoinStore?.all()
            ?.filter { it.status == MyJoinRequestStore.PENDING && it.requestedAtMs + JOIN_TTL_MS > now }
            ?.forEach { requestJoinStatus(it.groupId) }
        for (entry in outbox.due(now, JOIN_RETRY_MS, JOIN_TTL_MS)) {
            if (groupDao.getMember(entry.groupId, me) != null) {
                outbox.remove(entry.groupId)
                continue
            }
            val envelope = GroupWire.buildJoinRequest(
                groupId = entry.groupId,
                displayName = entry.displayName,
                note = entry.note,
                slug = entry.slug,
                requestedAtMs = entry.atMs,
            )
            runCatching { deliver(entry.groupId, envelope, entry.recipients) }
            outbox.markTried(entry.groupId, now)
        }
    }

    // ── Заявки на вступление ──────────────────────────────────────────────────

    fun observeJoinRequests(groupId: String): Flow<List<JoinRequestSummary>> =
        groupDao.observePendingRequests(groupId).map { list ->
            list.map { JoinRequestSummary(it.groupId, it.nodeId, it.displayName, it.note, it.requestedAtMs) }
        }.flowOn(Dispatchers.IO)

    /** Шаг 1 для частного вступления: заявка уходит администраторам группы. */
    suspend fun sendJoinRequest(groupId: String, note: String): Result<Unit> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val admins = groupDao.getAdmins(groupId)
        if (admins.isEmpty()) return Result.failure(IllegalStateException("В группе нет администраторов"))
        sendJoinRequestDurable(
            groupId = groupId,
            displayName = myName(),
            note = note.trim().take(noteLimit),
            slug = groupDao.getGroupById(groupId)?.inviteSlug.orEmpty(),
            recipients = admins.map { it.nodeId }.filter { it != me },
            isChannel = groupDao.getGroupById(groupId)?.isChannel == true,
            knownTitle = groupDao.getGroupById(groupId)?.title.orEmpty(),
        )
        return Result.success(Unit)
    }

    suspend fun decideJoinRequest(
        groupId: String,
        nodeId: String,
        approve: Boolean,
    ): Result<Unit> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canInvite(member.role, member.permissions, 0L)) {
            return Result.failure(SecurityException("Нет права одобрять заявки"))
        }
        val request = groupDao.getJoinRequest(groupId, nodeId)
            ?: return Result.failure(IllegalStateException("Заявка не найдена"))
        if (request.status != "PENDING") return Result.failure(IllegalStateException("Заявка уже решена"))

        groupDao.updateJoinRequestStatus(
            groupId, nodeId, if (approve) "APPROVED" else "REJECTED", clock(), me,
        )
        if (approve) {
            val group = groupDao.getGroupById(groupId)
                ?: return Result.failure(IllegalStateException("Группа не найдена"))
            admit(group, nodeId, request.displayName, "")
            broadcastJoinDecision(groupId, nodeId, true)
            // р250: новичок не знает медленного режима - конверт об изменении
            // он пропустил (его здесь не было). Говорим сразу, иначе режим
            // для него начался бы только со следующей правки настройки.
            if (group.slowModeSeconds > 0) {
                backgroundScope.launch {
                    runCatching {
                        deliver(
                            groupId,
                            GroupWire.buildSlowMode(groupId, group.slowModeSeconds),
                            listOf(nodeId),
                        )
                    }.onFailure { Log.w(TAG, "slow mode for newcomer failed: ${it.message}") }
                }
            }
            // Настройки кнопки SOS новичок тоже пропустил: говорим сразу,
            // если они отличаются от принятых по умолчанию, иначе его
            // телефон показал бы кнопку не так, как видит вся группа.
            if (!group.sosEnabled || GroupSos.clampCount(group.sosMessageCount) != GroupSos.DEFAULT_COUNT ||
                GroupSos.normalizePressRole(group.sosPressRole) != GroupSos.PRESS_ALL
            ) {
                backgroundScope.launch {
                    runCatching {
                        deliver(
                            groupId,
                            GroupWire.buildSosSettings(
                                groupId, group.sosEnabled,
                                GroupSos.clampCount(group.sosMessageCount),
                                GroupSos.normalizePressRole(group.sosPressRole),
                            ),
                            listOf(nodeId),
                        )
                    }.onFailure { Log.w(TAG, "sos settings for newcomer failed: ${it.message}") }
                }
            }
            // Свой текст тревоги новичок тоже не слышал: говорим, если задан.
            if (group.sosText.isNotBlank()) {
                backgroundScope.launch {
                    runCatching {
                        deliver(
                            groupId,
                            GroupWire.buildSosText(groupId, group.sosText),
                            listOf(nodeId),
                        )
                    }.onFailure { Log.w(TAG, "sos text for newcomer failed: ${it.message}") }
                }
            }
        } else {
            broadcastJoinDecision(groupId, nodeId, false)
        }
        return Result.success(Unit)
    }

    /**
     * Карточка группы пришла адресату заявки раньше, чем он вступил: имя,
     * размер, владелец и дата создания - чтобы «Мои заявки» выглядели
     * осмысленно ещё до решения. Принимаем только от тех, кому сами писали.
     */
    suspend fun handleJoinInfo(senderId: String, packet: GroupWire.Packet.JoinInfo) {
        val me = myNodeId() ?: return
        if (packet.requesterId != me) return
        val store = myJoinStore ?: return
        val known = store.get(packet.groupId) ?: return
        if (senderId !in known.recipients) return
        store.update(packet.groupId) {
            it.copy(
                title = packet.title,
                isChannel = packet.isChannel,
                memberCount = packet.memberCount,
                ownerName = packet.ownerName,
                groupCreatedAtMs = packet.createdAtMs,
                ownerReceivedAtMs = if (it.ownerReceivedAtMs > 0L) it.ownerReceivedAtMs else clock(),
            )
        }
        touchMyJoin()
    }

    /** Очередь перестаёт досылать заявку, а история помечает решение владельца. */
    fun acceptMyDecision(groupId: String, senderId: String, approved: Boolean) {
        joinOutbox?.remove(groupId)
        val store = myJoinStore ?: return
        if (store.get(groupId)?.let { senderId in it.recipients } != true) return
        store.update(groupId) { entry ->
            entry.copy(
                status = if (approved) MyJoinRequestStore.APPROVED else MyJoinRequestStore.REJECTED,
                decidedAtMs = clock(),
            )
        }
        touchMyJoin()
    }

    private companion object {
        private const val TAG = "GroupJoinRequests"

        /** Заявки на вступление: повтор раз в 10 минут, хранение до 7 дней. */
        private const val JOIN_RETRY_MS = 10L * 60 * 1000
        private const val JOIN_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Сколько участников получают ретрансляцию решения по заявке с одного телефона. */
        private const val MAX_JOIN_DECISION_RELAY = 3
        private const val MAX_SEEN_JOIN_DECISIONS = 512
    }
}
