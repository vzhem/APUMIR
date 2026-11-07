package com.vladimir.messenger.data.group

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.entity.GroupEntity
import kotlinx.coroutines.delay

/**
 * Досылка «карточка группы + темы + состав» тем, кто её пропустил.
 *
 * Кнопка «Разослать темы и состав заново» лечила участников, вступивших раньше,
 * чем группа научилась присылать эти пакеты, но требовала, чтобы владелец сам
 * вспомнил про неё. Здесь та же досылка работает сама — и при этом не бьёт по
 * сети веером на весь состав:
 *
 * - **адресно по просьбе**: участник, у которого пусто, шлёт маленький
 *   `TopicsRequest`/`RosterRequest`, а владелец или администратор отвечает
 *   только ему ([requestTopics], [requestRoster], [repairIfIncomplete]);
 * - **тихими проходами**: при открытии группы ([autoPass]) и по расписанию в
 *   фоне ([autoPassAll]) администратор досылает не всем сразу, а
 *   [Limits.membersPerPass] участникам за проход, с паузой между пакетами и не
 *   чаще раза в [Limits.autoPassGapMs]. Курсор обхода хранится на группу
 *   ([Store]), поэтому за несколько проходов состав обходится по кругу, а
 *   хранилище не растёт вместе с числом участников;
 * - **кнопкой** ([resyncAll]) — прежнее поведение «разослать всем сейчас»,
 *   для случая «надо прямо сейчас»; после неё тихие проходы ждут свой срок.
 *
 * Приёмник досылку переносит спокойно: карточка обновляет строку группы,
 * темы добавляются только отсутствующие, состав сливается без удаления
 * ([GroupWire.mergeRoster]). Поэтому повторная отправка — не ошибка, а
 * страховка.
 *
 * Числа ([Limits]) — политика бережливости; в JVM-тестах хранилище
 * [MemoryStore], в приложении — [PrefsStore].
 */
internal class GroupInfoResync(
    private val groupDao: GroupDao,
    private val myNodeId: () -> String?,
    private val clock: () -> Long,
    private val deliver: suspend (groupId: String, envelope: String, recipients: List<String>) -> Unit,
    /** Один веер состава: им же пользуется обычная смена ролей. */
    private val publishRoster: suspend (groupId: String) -> Unit,
    private val store: Store = MemoryStore(),
    private val limits: Limits = Limits(),
) {

    /**
     * Бережливость досылки. Все числа — «сколько за проход» и «как часто»,
     * а не «включено/выключено»: автоматика не должна забивать очередь ядра
     * и публичный брокер, даже если в группе тысячи участников.
     */
    data class Limits(
        /** Не чаще раза в 6 часов на группу — авто-проход по открытию/расписанию. */
        val autoPassGapMs: Long = 6L * 60 * 60 * 1000,
        /** Сколько участников обходим за один проход. */
        val membersPerPass: Int = 15,
        /** Пауза между пакетами авто-прохода: один участник — до трёх конвертов. */
        val packetGapMs: Long = 180L,
        /** Только что принятому участнику досылка не нужна: он получил всё при вступлении. */
        val justJoinedGraceMs: Long = 60L * 60 * 1000,
        /** Своя просьба о темах — не чаще раза в 10 минут на группу. */
        val topicsRequestGapMs: Long = 10L * 60 * 1000,
        /** Своя просьба о составе — не чаще раза в 10 минут на группу. */
        val rosterRequestGapMs: Long = 10L * 60 * 1000,
        /** Полный состав в одном конверте имеет смысл только до этого размера. */
        val rosterLimit: Int = GroupRepository.ROSTER_LIMIT,
    )

    /**
     * Что автоматика уже сделала: время последней досылки и место курсора
     * обхода на группу. Отдельного счётчика на участника нет нарочно —
     * в группе на тысячи человек это тысячи ключей в SharedPreferences.
     */
    interface Store {
        fun lastPassAt(groupId: String): Long
        fun markPass(groupId: String, atMs: Long)
        fun cursor(groupId: String): Int
        fun markCursor(groupId: String, cursor: Int)
        fun lastRequestAt(groupId: String, kind: String): Long
        fun markRequest(groupId: String, kind: String, atMs: Long)
    }

    /** Хранилище для JVM-тестов: живёт столько же, сколько объект. */
    class MemoryStore : Store {
        private val passes = HashMap<String, Long>()
        private val cursors = HashMap<String, Int>()
        private val requests = HashMap<String, Long>()

        override fun lastPassAt(groupId: String): Long = passes[groupId] ?: 0L
        override fun markPass(groupId: String, atMs: Long) { passes[groupId] = atMs }
        override fun cursor(groupId: String): Int = cursors[groupId] ?: 0
        override fun markCursor(groupId: String, cursor: Int) { cursors[groupId] = cursor }
        override fun lastRequestAt(groupId: String, kind: String): Long = requests["$kind|$groupId"] ?: 0L
        override fun markRequest(groupId: String, kind: String, atMs: Long) { requests["$kind|$groupId"] = atMs }
    }

    /**
     * Хранилище приложения. Отметки переживают перезапуск: иначе после каждого
     * холодного старта авто-проход начинался бы заново и телефон с открытой
     * группой досылал бы одно и то же по кругу.
     */
    class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun lastPassAt(groupId: String): Long = prefs.getLong("pass|$groupId", 0L)
        override fun markPass(groupId: String, atMs: Long) {
            prefs.edit().putLong("pass|$groupId", atMs).apply()
        }

        override fun cursor(groupId: String): Int = prefs.getInt("cursor|$groupId", 0)
        override fun markCursor(groupId: String, cursor: Int) {
            prefs.edit().putInt("cursor|$groupId", cursor).apply()
        }

        override fun lastRequestAt(groupId: String, kind: String): Long =
            prefs.getLong("req|$kind|$groupId", 0L)

        override fun markRequest(groupId: String, kind: String, atMs: Long) {
            prefs.edit().putLong("req|$kind|$groupId", atMs).apply()
        }
    }

    // ── администратор: тихая досылка ─────────────────────────────────────────

    /**
     * Тихий проход по группе: дослать карточку, темы и состав очередной
     * порции участников. Вызывается при открытии группы и из фоновой задачи;
     * для неадминистратора и при не истёкшем сроке — сразу ноль, без сети.
     *
     * @return сколько участников получило досылку (0 — проход не потребовался).
     */
    suspend fun autoPass(groupId: String): Int {
        val me = myNodeId() ?: return 0
        val group = groupDao.getGroupById(groupId) ?: return 0
        val mine = groupDao.getMember(groupId, me) ?: return 0
        if (mine.isBanned || !GroupRole.isAdminOrOwner(mine.role)) return 0
        val now = clock()
        val last = store.lastPassAt(groupId)
        // Часы могли перевести назад: тогда считаем срок истёкшим, иначе
        // досылка зависла бы навсегда.
        if (last > 0L && now >= last && now - last < limits.autoPassGapMs) return 0
        store.markPass(groupId, now)
        return sendPass(group, me, now)
    }

    /**
     * Тот же проход по всем группам, где я владелец или администратор.
     * Для фоновой задачи: интервал и срок прохода совпадают, поэтому
     * открытое приложение и фоновый запуск делят одно окно и не дублируют
     * отправку.
     */
    suspend fun autoPassAll(): Int {
        val me = myNodeId() ?: return 0
        var sent = 0
        groupDao.getGroups().forEach { group ->
            val mine = groupDao.getMember(group.id, me) ?: return@forEach
            if (mine.isBanned || !GroupRole.isAdminOrOwner(mine.role)) return@forEach
            sent += runCatching { autoPass(group.id) }
                .onFailure { Log.w(TAG, "auto pass failed group=${group.id}: ${it.message}") }
                .getOrDefault(0)
        }
        return sent
    }

    /**
     * Разовая порция досылки: карточка, темы и состав очередным
     * [Limits.membersPerPass] участникам по кругу от курсора. Порядок обхода —
     * по времени вступления, поэтому новички (их досылает и само вступление)
     * попадают в хвост, а давно молчащие — первыми.
     */
    private suspend fun sendPass(group: GroupEntity, me: String, now: Long): Int {
        val members = groupDao.getMembers(group.id)
            .filter { !it.isBanned && it.nodeId != me }
            .sortedWith(compareBy({ it.joinedAtMs }, { it.nodeId }))
        if (members.isEmpty()) return 0
        val info = buildGroupInfo(group)
        val topics = buildTopics(group.id)
        val roster = buildRoster(group.id)
        val start = Math.floorMod(store.cursor(group.id), members.size)
        val take = minOf(limits.membersPerPass, members.size)
        var sent = 0
        var packets = 0
        for (step in 0 until take) {
            val member = members[(start + step) % members.size]
            // Принятому минуту назад досылка не нужна: при вступлении он
            // получил и карточку, и темы, и состав.
            val joinedAgo = now - member.joinedAtMs
            if (joinedAgo in 0 until limits.justJoinedGraceMs) continue
            if (packets > 0) delay(limits.packetGapMs)
            deliver(group.id, info, listOf(member.nodeId))
            packets++
            if (topics != null) {
                delay(limits.packetGapMs)
                deliver(group.id, topics, listOf(member.nodeId))
                packets++
            }
            if (roster != null) {
                delay(limits.packetGapMs)
                deliver(group.id, roster, listOf(member.nodeId))
                packets++
            }
            sent++
        }
        // Курсор двигаем на всю просмотренную порцию, а не на число
        // отправленных: пропущенные новички не должны задерживать обход.
        store.markCursor(group.id, (start + take) % members.size)
        Log.i(TAG, "info auto pass group=${group.id} members=${members.size} sent=$sent")
        return sent
    }

    // ── администратор: кнопка «разослать сейчас» ─────────────────────────────

    /**
     * Прежняя кнопка: карточка и темы всем участникам сразу, затем один веер
     * состава. Без пауз — темп веера уже держит общий бюджет пакетов
     * (`PerMemberFanoutDelivery` + `SwarmBudget`), а человек нажал её осознанно.
     * Отметка прохода ставится, чтобы тихая досылка не повторила ту же работу
     * через секунду.
     */
    suspend fun resyncAll(groupId: String): Result<Int> = runCatching {
        val me = myNodeId() ?: throw IllegalStateException("Идентичность узла ещё не готова")
        val actor = groupDao.getMember(groupId, me)
            ?: throw IllegalStateException("Вы не участник этой группы")
        if (!GroupRole.isAdminOrOwner(actor.role)) {
            throw SecurityException("Рассылка доступна только администраторам")
        }
        val group = groupDao.getGroupById(groupId)
            ?: throw IllegalStateException("Группа не найдена")
        val others = groupDao.getMembers(groupId)
            .filter { !it.isBanned }
            .map { it.nodeId }
            .filter { it != me }
        val info = buildGroupInfo(group)
        val topics = buildTopics(group.id)
        others.forEach { nodeId ->
            deliver(group.id, info, listOf(nodeId))
            if (topics != null) deliver(group.id, topics, listOf(nodeId))
        }
        publishRoster(group.id)
        store.markPass(groupId, clock())
        store.markCursor(groupId, 0)
        Log.i(TAG, "resync sent group=$groupId to=${others.size}")
        others.size
    }

    /** Когда последний раз уходила досылка (авто или по кнопке); 0 — ещё не было. */
    fun lastPassAt(groupId: String): Long = store.lastPassAt(groupId)

    // ── участник: просьба о недостающем ──────────────────────────────────────

    /**
     * Попросить у владельца список тем.
     *
     * Вступивший позже не застал пакеты `TopicCreated` и видит пустой список.
     * Просим не чаще раза в [Limits.topicsRequestGapMs] на группу: пакет
     * маленький, а приёмник добавляет только отсутствующие темы.
     */
    suspend fun requestTopics(groupId: String) {
        val me = myNodeId() ?: return
        val group = groupDao.getGroupById(groupId) ?: return
        if (group.ownerId == me) return
        if (groupDao.getMember(groupId, me) == null) return
        if (!dueForRequest(groupId, KIND_TOPICS, limits.topicsRequestGapMs)) return
        runCatching { deliver(groupId, GroupWire.buildTopicsRequest(groupId), listOf(group.ownerId)) }
            .onFailure { Log.w(TAG, "topics request failed group=$groupId: ${it.message}") }
        Log.i(TAG, "topics requested group=$groupId")
    }

    /**
     * Попросить у владельца или администраторов актуальный состав.
     *
     * Это не только реакция на неизвестное имя: экран чата вызывает метод при
     * открытии, поэтому телефон, который был офлайн во время вступления,
     * самовосстанавливает roster. Адрес владельца берём из карточки группы,
     * а локальные admin-строки добавляем как запасной путь, если владелец
     * временно недоступен.
     */
    suspend fun requestRoster(groupId: String) {
        val me = myNodeId() ?: return
        val group = groupDao.getGroupById(groupId) ?: return
        val mine = groupDao.getMember(groupId, me) ?: return
        if (mine.isBanned || group.ownerId == me) return
        if (!dueForRequest(groupId, KIND_ROSTER, limits.rosterRequestGapMs)) return
        val targets = (
            listOf(group.ownerId) + groupDao.getAdmins(groupId).map { it.nodeId }
        ).filter { it.isNotBlank() && it != me }.distinct()
        if (targets.isEmpty()) return
        runCatching { deliver(groupId, GroupWire.buildRosterRequest(groupId), targets) }
            .onFailure { Log.w(TAG, "roster request failed group=$groupId: ${it.message}") }
        Log.i(TAG, "roster requested group=$groupId from=${targets.size} source(s)")
    }

    /**
     * Само-проверка открытой группы: просим только то, чего действительно не
     * хватает. Пустой список тем в группе с темами и неполный состав малой
     * группы — единственные признаки, которые видно на своей стороне, поэтому
     * просьба ничего не стоит, пока всё на месте.
     *
     * @return true, если запрос ушёл.
     */
    suspend fun repairIfIncomplete(groupId: String): Boolean {
        val me = myNodeId() ?: return false
        val group = groupDao.getGroupById(groupId) ?: return false
        if (group.ownerId == me) return false
        if (groupDao.getMember(groupId, me) == null) return false
        var asked = false
        if (group.topicsEnabled && groupDao.getTopics(groupId).isEmpty()) {
            requestTopics(groupId)
            asked = true
        }
        // Скрытый список участников — решение администратора: полноту состава
        // там не проверяем и не просим. Большая группа в один конверт не
        // помещается, поэтому о её полноте судить нельзя.
        val known = groupDao.getMembers(groupId).size
        if (group.memberListVisible && group.memberCount > known && group.memberCount <= limits.rosterLimit) {
            requestRoster(groupId)
            asked = true
        }
        return asked
    }

    /** Окно частоты просьбы: true, если пора, и тогда отметка ставится сразу. */
    private fun dueForRequest(groupId: String, kind: String, gapMs: Long): Boolean {
        val now = clock()
        val last = store.lastRequestAt(groupId, kind)
        if (last > 0L && now >= last && now - last < gapMs) return false
        store.markRequest(groupId, kind, now)
        return true
    }

    // ── конверты ─────────────────────────────────────────────────────────────

    /** Карточка группы: название, описание, владелец, настройки. */
    private fun buildGroupInfo(group: GroupEntity): String = GroupWire.buildGroupInfo(
        groupId = group.id,
        title = group.title,
        about = group.about,
        ownerId = group.ownerId,
        inviteSlug = group.inviteSlug,
        isPublic = group.isPublic,
        topicsEnabled = group.topicsEnabled,
        isChannel = group.isChannel,
    )

    /** Список тем; null — тем нет, и отдельный конверт не нужен. */
    private suspend fun buildTopics(groupId: String): String? {
        val topics = groupDao.getTopics(groupId)
        if (topics.isEmpty()) return null
        return GroupWire.buildTopics(
            groupId,
            topics.map { GroupWire.TopicEntry(it.id, it.name, it.iconEmoji) },
        )
    }

    /**
     * Снимок состава для адресной досылки. На группе больше
     * [Limits.rosterLimit] участников полный список не влезает в конверт —
     * шлём только администраторов, как и обычный `publishRoster`.
     */
    private suspend fun buildRoster(groupId: String): String? {
        val members = groupDao.getMembers(groupId)
        if (members.isEmpty()) return null
        val entries = if (members.size <= limits.rosterLimit) {
            members
        } else {
            members.filter { GroupRole.isAdminOrOwner(it.role) }
        }
        if (entries.isEmpty()) return null
        return GroupWire.buildRoster(
            groupId,
            entries.map { GroupWire.RosterEntry(it.nodeId, it.displayName, it.role) },
            memberCount = members.size,
        )
    }

    /**
     * Темы группы новому участнику. Без них он попадает в пустой чат: темы
     * создаются пакетом `TopicCreated` в момент создания, и опоздавший их не видел.
     */
    suspend fun sendTopics(groupId: String, nodeId: String) {
        val envelope = buildTopics(groupId) ?: return
        deliver(groupId, envelope, listOf(nodeId))
    }

    /** Карточка группы новому участнику — при вступлении и на просьбу о темах. */
    suspend fun sendGroupInfo(group: GroupEntity, nodeId: String) {
        deliver(group.id, buildGroupInfo(group), listOf(nodeId))
    }

    private companion object {
        private const val TAG = "GroupInfoResync"
        private const val PREFS_NAME = "group_info_resync_v1"
        private const val KIND_TOPICS = "topics"
        private const val KIND_ROSTER = "roster"
    }
}
