package com.vladimir.messenger.data.channel

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MessageReactionDao
import com.vladimir.messenger.data.local.dao.PostViewDao
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageReactionEntity
import com.vladimir.messenger.data.local.entity.PostViewEntity
import com.vladimir.messenger.data.swarm.SwarmBudget
import com.vladimir.messenger.data.swarm.SwarmLane
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import com.vladimir.messenger.data.swarm.SwarmPolicy
import com.vladimir.messenger.util.InlineImage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Счётчики постов большого канала через владельца (рой, этап 3).
 *
 * До этого этапа каждый читатель слал свой просмотр и свою реакцию ВСЕМ
 * подписчикам: на канале в 10 000 человек один пост давал 10 000 × 10 000
 * пакетов. Теперь на канале больше [SwarmPolicy.COUNTERS_VIA_HUBS_FROM]
 * подписчиков сигналы идут только «узлам-сборщикам» - владельцу и
 * администраторам ([hubsOf]); у них счётчики сходятся, а остальные, открывая
 * канал, спрашивают сводные числа ([requestCounters] → `pcreq`) и получают их
 * одним-двумя пакетами ([serve] → `pcnt`).
 *
 * Сводка ложится в те же таблицы, что и живые сигналы, под служебными
 * «читателями»: просмотры - строки с viewerId `hub:<k>`; реакции - строки
 * `hub:<значок>:<k>` на сообщение. Так лента считает их без единого
 * изменения (она группирует строки), а живые сигналы - своя реакция, свой
 * просмотр и то, что дошло от соседей, - не теряются и не задваиваются:
 * служебных строк добавляется ровно столько, сколько не хватает до сводки.
 *
 * Сборщик отвечает не чаще [REPLIES_PER_MINUTE] раз в минуту и не больше
 * [GroupWire.MAX_COUNTER_CELLS] постов в пакете; просьба без известных тем
 * остаётся без ответа. Маленький канал (до порога) работает по-старому.
 */
@Singleton
class PostCounterRepository @Inject constructor(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val postViewDao: PostViewDao,
    private val reactionDao: MessageReactionDao,
    private val swarmBudget: SwarmBudget,
    private val swarmDirectory: SwarmPeerDirectory,
) {

    /** Стекать ли сигналы этого сообщества к сборщикам (большой канал), а не ко всем. */
    suspend fun viaHubs(group: GroupEntity): Boolean {
        if (!group.isChannel) return false
        val members = maxOf(group.memberCount, groupDao.countMembers(group.id))
        return SwarmPolicy.countersViaHubs(members)
    }

    /** Владелец и администраторы канала, кроме меня: им стекаются сигналы, у них спрашивают сводку. */
    suspend fun hubsOf(group: GroupEntity, me: String): List<String> {
        val admins = groupDao.getAdmins(group.id).map { it.nodeId }
        return (listOf(group.ownerId) + admins).filter { it.isNotBlank() && it != me }.distinct()
    }

    /**
     * Кому слать служебный сигнал (просмотр, реакцию) в сообществе: на большом
     * канале - сборщикам, иначе - всем участникам в порядке яруса.
     */
    suspend fun signalTargets(group: GroupEntity, me: String): List<String> {
        if (viaHubs(group)) return hubsOf(group, me)
        val members = groupDao.getMembers(group.id)
            .filter { !it.isBanned }
            .map { it.nodeId }
            .filter { it.isNotBlank() && it != me }
        return runCatching { swarmDirectory.order(members) }.getOrDefault(members)
    }

    // ── Читатель: спросить сводку ────────────────────────────────────────────

    /** Когда у сборщиков канала в последний раз просили счётчики. */
    private val askedAt = HashMap<String, Long>()

    /**
     * Попросить у одного из сборщиков сводные счётчики последних постов.
     * Зовётся при открытии канала; не чаще раза в [ASK_GAP_MS] на канал.
     * Сборщик выбирается случайно из владельца и администраторов, чтобы
     * владелец не отвечал за всех один. Маленький канал ничего не шлёт.
     */
    suspend fun requestCounters(channelId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val me = RustBridge.nodeId().orEmpty()
                if (me.isBlank()) return@runCatching
                val group = groupDao.getGroupById(channelId) ?: return@runCatching
                if (!viaHubs(group)) return@runCatching
                val myRow = groupDao.getMember(channelId, me) ?: return@runCatching
                // Сборщику спрашивать не у кого: сигналы и так стекаются к нему.
                if (GroupRole.isAdminOrOwner(myRow.role)) return@runCatching
                val hubs = hubsOf(group, me)
                if (hubs.isEmpty()) return@runCatching
                if (!markAsked(channelId, System.currentTimeMillis())) return@runCatching
                val topics = groupDao.getTopics(channelId)
                    .sortedByDescending { it.createdAtMs }
                    .take(GroupWire.MAX_COUNTER_TOPICS)
                    .map { it.id }
                if (topics.isEmpty()) return@runCatching
                if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) return@runCatching
                val hub = hubs.random()
                RustBridge.sendMessage(
                    UUID.randomUUID().toString(),
                    channelId,
                    hub,
                    GroupWire.buildCountersRequest(channelId, topics),
                )
                Log.i(TAG, "counters requested group=$channelId topics=${topics.size} hub=${hub.takeLast(6)}")
            }.onFailure { Log.w(TAG, "counters request failed: ${it.message}") }
        }
    }

    /** Пора ли снова спрашивать этот канал; заодно запоминает время. */
    private fun markAsked(channelId: String, now: Long): Boolean {
        synchronized(askedAt) {
            val last = askedAt[channelId] ?: 0L
            if (now - last < ASK_GAP_MS) return false
            askedAt[channelId] = now
            return true
        }
    }

    // ── Сборщик: ответить сводкой ────────────────────────────────────────────

    private val replyStamps = ArrayDeque<Long>()

    private fun allowReply(now: Long): Boolean {
        synchronized(replyStamps) {
            while (replyStamps.isNotEmpty() && now - replyStamps.first() > 60_000L) replyStamps.removeFirst()
            if (replyStamps.size >= REPLIES_PER_MINUTE) return false
            replyStamps.addLast(now)
            return true
        }
    }

    /**
     * Просьба о счётчиках. Отвечает только сборщик (владелец или
     * администратор) и только участнику канала (на открытом канале - любому:
     * там список участников у сборщика может быть неполным, а вступить может
     * каждый). Сводка идёт пакетами по [GroupWire.MAX_COUNTER_CELLS] постов.
     */
    suspend fun serve(senderId: String, packet: GroupWire.Packet.CountersRequest) {
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank() || senderId.isBlank() || senderId == me) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (!group.isChannel) return
        val myRow = groupDao.getMember(group.id, me) ?: return
        if (!GroupRole.isAdminOrOwner(myRow.role)) return
        val requester = groupDao.getMember(group.id, senderId)
        if (requester?.isBanned == true) return
        if (requester == null && !group.isPublic) return
        val now = System.currentTimeMillis()
        if (!allowReply(now)) {
            Log.i(TAG, "counters request throttled group=${group.id} from=${senderId.takeLast(6)}")
            return
        }
        val cells = ArrayList<GroupWire.PostCounters>()
        for (topicId in packet.topicIds) {
            val topic = groupDao.getTopicById(topicId) ?: continue
            if (topic.groupId != group.id) continue
            val post = messageDao.getTopicMessages(group.id, topicId)
                .firstOrNull { !InlineImage.isPart(it.content) } ?: continue
            cells.add(countersOf(topicId, post.id))
        }
        if (cells.isEmpty()) return
        var sent = 0
        for (chunk in cells.chunked(GroupWire.MAX_COUNTER_CELLS)) {
            if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) break
            RustBridge.sendMessage(
                UUID.randomUUID().toString(),
                group.id,
                senderId,
                GroupWire.buildCounters(group.id, chunk),
            )
            sent++
        }
        Log.i(TAG, "counters served group=${group.id} to=${senderId.takeLast(6)} posts=${cells.size} packets=$sent")
    }

    /**
     * Сводка одного поста у сборщика: сколько разных читателей его открыли
     * (включая самого сборщика) и сколько реакций каждого значка стоит
     * (своя реакция сборщика - тоже настоящая). Строки чужих сводок `hub:`
     * не считаются: сборщик отвечает только за то, что видел сам.
     */
    private suspend fun countersOf(topicId: String, messageId: String): GroupWire.PostCounters {
        val views = postViewDao.getForTopic(topicId).count { !it.viewerId.startsWith(HUB_PREFIX) }
        val reactions = reactionDao.getForMessage(messageId)
            .filter { !it.nodeId.startsWith(HUB_PREFIX) }
            .groupingBy { it.emoji }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
        return GroupWire.PostCounters(topicId, messageId, views, reactions)
    }

    // ── Читатель: применить сводку ───────────────────────────────────────────

    /**
     * Сводка от сборщика. Принимается только от владельца или администратора
     * канала, где я состою. Сводные значения раскладываются на служебные
     * строки `hub:…` так, чтобы итог у меня сошёлся с присланным числом:
     * живые строки (своя и от соседей, что всё же дошли) остаются, а
     * недостающее добивается служебными. Ниже живых строк итог не опускается.
     * Просмотры только растут; реакции могут и убывать (значок сняли) -
     * лишние служебные строки тогда убираются.
     */
    suspend fun applyCounters(senderId: String, packet: GroupWire.Packet.Counters) {
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank() || senderId.isBlank()) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (!group.isChannel) return
        if (groupDao.getMember(group.id, me) == null) return
        val sender = groupDao.getMember(group.id, senderId)
        val trusted = senderId == group.ownerId || (sender != null && GroupRole.isAdminOrOwner(sender.role))
        if (!trusted) return
        val now = System.currentTimeMillis()
        var applied = 0
        for (cell in packet.cells) {
            val topic = groupDao.getTopicById(cell.topicId) ?: continue
            if (topic.groupId != group.id) continue
            applyViews(cell.topicId, cell.views, now)
            if (cell.messageId.isNotBlank()) applyReactions(group.id, cell.messageId, cell.reactions, now)
            applied++
        }
        Log.i(TAG, "counters applied group=${group.id} from=${senderId.takeLast(6)} posts=$applied")
    }

    /**
     * Просмотры: живых строк (своя и от соседей) плюс служебных столько,
     * чтобы итог был [total]. Свой просмотр у сборщика уже учтён (он ему
     * послан), поэтому своя строка входит в живые и отдельно не прибавляется.
     */
    private suspend fun applyViews(topicId: String, total: Int, now: Long) {
        val rows = postViewDao.getForTopic(topicId)
        val live = rows.count { !it.viewerId.startsWith(HUB_PREFIX) }
        val hubRows = rows.count { it.viewerId.startsWith(HUB_PREFIX) }
        val wanted = (total - live).coerceAtLeast(0)
        if (hubRows >= wanted) return
        for (k in hubRows until wanted) {
            postViewDao.put(PostViewEntity(topicId = topicId, viewerId = HUB_PREFIX + k, atMs = now))
        }
    }

    /**
     * Реакции: по каждому значку служебных строк столько, чтобы итог был
     * присланным. Своя реакция входит в живые строки (сборщику она послана и
     * в его числе есть). Значки, которых в сводке нет, теряют служебные
     * строки: их больше никто не держит.
     */
    private suspend fun applyReactions(chatId: String, messageId: String, summary: List<Pair<String, Int>>, now: Long) {
        val rows = reactionDao.getForMessage(messageId)
        val totals = summary.toMap()
        val emojis = LinkedHashSet<String>()
        emojis.addAll(totals.keys)
        rows.filter { it.nodeId.startsWith(HUB_PREFIX) }.forEach { emojis.add(it.emoji) }
        for (emoji in emojis) {
            val total = totals[emoji] ?: 0
            val live = rows.count { it.emoji == emoji && !it.nodeId.startsWith(HUB_PREFIX) }
            val hubRows = rows.filter { it.emoji == emoji && it.nodeId.startsWith(HUB_PREFIX) }
            val wanted = (total - live).coerceAtLeast(0)
            if (hubRows.size > wanted) {
                for (row in hubRows.drop(wanted)) reactionDao.remove(messageId, row.nodeId)
            } else {
                for (k in hubRows.size until wanted) {
                    reactionDao.put(
                        MessageReactionEntity(
                            messageId = messageId,
                            nodeId = HUB_PREFIX + hubKey(emoji) + ":" + k,
                            chatId = chatId,
                            emoji = emoji,
                            atMs = now,
                        )
                    )
                }
            }
        }
    }

    /** Значок в идентификаторе служебной строки - в кодах, чтобы не зависеть от `:` внутри эмодзи. */
    private fun hubKey(emoji: String): String =
        emoji.codePoints().toArray().joinToString("-") { Integer.toHexString(it) }

    companion object {
        private const val TAG = "PostCounters"

        /** Служебные «читатели» и «авторы реакций», под которыми лежит сводка сборщика. */
        const val HUB_PREFIX = "hub:"

        /** Просьба о счётчиках - не чаще раза в полминуты на канал (как просьба о постах). */
        private const val ASK_GAP_MS = 30_000L

        /** Сколько просьб о счётчиках сборщик обслуживает в минуту. */
        private const val REPLIES_PER_MINUTE = 30
    }
}
