package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.PostManifestDao
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.swarm.SwarmPolicy
import com.vladimir.messenger.util.InlineImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Комментарии большого канала (рой, этап 4) - вынесены из [GroupRepository]
 * (аудит 2026-10-26, п. 4.1).
 *
 * Здесь весь путь комментария на большом канале: выбор сборщика, опись ветки,
 * точечная досылка, живые комментарии читателям и число комментариев от
 * сборщика. Поведение перенесено один в один.
 *
 * Своих данных класс не держит (кроме памяти на запуск: кто читает ветку и у
 * кого что спрашивали), поэтому зависимости приходят функциями: время, мой
 * узел, веер доставки, получатели и порядок соседей по ярусу. Правила
 * рассылки остаются в [SwarmPolicy].
 *
 * Общие числа роя (`relayFanout`, `commentNeighbours`, `backfillGapMs`,
 * `maxTrackedTopics`) приходят из [GroupRepository]: они одни на весь рой и
 * меняются в одном месте, а не в копиях.
 */
internal class GroupComments(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val manifestDao: PostManifestDao?,
    private val clock: () -> Long,
    private val nodeId: () -> String?,
    private val deliver: suspend (groupId: String, envelope: String, recipients: List<String>) -> Unit,
    private val recipients: suspend (groupId: String, me: String) -> List<String>,
    private val orderPeers: suspend (List<String>) -> List<String>,
    private val relayFanout: Int,
    private val commentNeighbours: Int,
    private val backfillGapMs: Long,
    private val maxTrackedTopics: Int,
) {

    // ── Комментарии большого канала (этап 4) ──────────────────────────────────
    //
    // На канале больше порога подписчиков комментарий не идёт всем: автор шлёт
    // его сборщикам (владельцу и администраторам), читателям ветки и выборке
    // соседей. У сборщиков накапливается полная ветка; читатель, открыв
    // комментарии, получает от сборщика опись (`cids`: сколько всего и начала
    // идентификаторов свежих), сравнивает со своими и просит недостающее
    // (`creq` с `want`), а сборщик отвечает обычными конвертами `msg`. Пока
    // человек держит ветку открытой, сборщик пересылает ему новые комментарии
    // сразу (список читателей - в памяти сборщика, полминуты).

    /** Идёт ли обсуждение этого канала через сборщиков: больше порога подписчиков. */
    suspend fun commentsViaHubs(group: GroupEntity): Boolean =
        group.isChannel && SwarmPolicy.commentsViaHubs(maxOf(group.memberCount, groupDao.countMembers(group.id)))

    /** Владелец и администраторы канала (сборщики комментариев и счётчиков), кроме меня. */
    private suspend fun hubsOf(group: GroupEntity, me: String): List<String> =
        (listOf(group.ownerId) + groupDao.getAdmins(group.id).map { it.nodeId })
            .filter { it.isNotBlank() && it != me }.distinct()

    /**
     * Кому уходит мой комментарий: null - всем (маленький канал, по-старому).
     * На большом канале - сборщикам, тем, кто недавно спрашивал у меня эту
     * ветку (они её читают прямо сейчас), и выборке из лучших по ярусу
     * соседей размером с эстафету манифеста; сборщик, получив комментарий,
     * передаёт его дальше своим читателям ветки ([relayComment]).
     */
    suspend fun commentTargets(group: GroupEntity, me: String, topicId: String): List<String>? {
        if (!commentsViaHubs(group)) return null
        val hubs = hubsOf(group, me)
        val readers = recentReaders(group.id, topicId).filter { it != me && it !in hubs }
        val pool = recipients(group.id, me).filter { it !in hubs && it !in readers }
        val neighbours = orderPeers(pool).take(relayFanout * 2).shuffled().take(commentNeighbours)
        return (hubs + readers + neighbours).distinct()
    }

    /** Кто и когда просил у меня комментарии темы: ключ «группа|тема» → «узел → время». */
    private val commentReaders = HashMap<String, LinkedHashMap<String, Long>>()

    private fun noteReader(groupId: String, topicId: String, nodeId: String, now: Long) {
        synchronized(commentReaders) {
            if (commentReaders.size >= maxTrackedTopics) commentReaders.clear()
            val readers = commentReaders.getOrPut(groupId + '|' + topicId) { LinkedHashMap<String, Long>() }
            readers.remove(nodeId)
            readers[nodeId] = now
            while (readers.size > MAX_TOPIC_READERS) readers.remove(readers.keys.first())
        }
    }

    /** Кто читал тему в последние [READER_TTL_MS]: им живые комментарии идут сразу. */
    private fun recentReaders(groupId: String, topicId: String): List<String> {
        val now = clock()
        return synchronized(commentReaders) {
            val readers = commentReaders[groupId + '|' + topicId]
            if (readers == null) {
                emptyList<String>()
            } else {
                readers.entries.removeAll { now - it.value > READER_TTL_MS }
                readers.keys.toList()
            }
        }
    }

    /** Когда у сборщика в последний раз просили комментарии темы: ключ «группа|тема». */
    private val commentsRequestedAt = HashMap<String, Long>()

    /** Когда сборщик в последний раз отвечал описью по теме: ключ «группа|тема». */
    private val commentsAnsweredAt = HashMap<String, Long>()

    /** Какого по счёту сборщика спрашивать по теме: молчащего меняем на следующего. */
    private val commentHubIndex = HashMap<String, Int>()

    /**
     * Сборщик для темы: один и тот же, пока отвечает (так читатель числится
     * читателем у одного сборщика, и живые комментарии не приходят дважды);
     * не ответил на прошлую просьбу - следующий по кругу.
     */
    private fun pickHub(key: String, hubs: List<String>, lastAsked: Long): String {
        val index = synchronized(commentsAnsweredAt) {
            val answered = commentsAnsweredAt[key] ?: 0L
            // Первый сборщик - случайный: иначе все читатели темы шли бы к одному.
            var current = commentHubIndex[key] ?: (0 until 1_000_000).random()
            if (lastAsked > 0L && answered < lastAsked) current++
            commentHubIndex[key] = current
            current
        }
        return hubs[index % hubs.size]
    }

    /** Просьб о комментариях, которые я обслуживаю как сборщик, в минуту. */
    private val commentServeRate = RateWindow(COMMENT_REPLIES_PER_MINUTE, 60_000L)

    /** Шаблон LIKE служебных кусков (фото и длинного текста): текстовые сообщения темы - всё остальное. */
    private val partPattern: String get() = InlineImage.PART_MARKER + "%"

    /** Пост темы канала - самое раннее текстовое сообщение; null, если поста ещё нет. */
    private suspend fun topicPost(groupId: String, topicId: String): MessageEntity? =
        messageDao.getTopicTextsOldest(groupId, topicId, partPattern, 1).firstOrNull()

    /** Сколько комментариев (текстовых сообщений кроме поста) у меня в теме. */
    private suspend fun commentCount(groupId: String, topicId: String): Int =
        (messageDao.countTopicTexts(groupId, topicId, partPattern) - 1).coerceAtLeast(0)

    /**
     * Комментарии темы от новых к старым: не больше [limit] штук строго между
     * [afterMs] и [beforeMs] (0 - без ограничения), без самого поста.
     */
    private suspend fun commentsNewestFirst(
        groupId: String,
        topicId: String,
        afterMs: Long,
        beforeMs: Long,
        limit: Int,
    ): List<MessageEntity> {
        val post = topicPost(groupId, topicId) ?: return emptyList()
        val until = if (beforeMs > 0L) beforeMs else Long.MAX_VALUE
        // Одним больше: среди выбранных может оказаться сам пост.
        return messageDao.getTopicTextsBetween(groupId, topicId, partPattern, afterMs, until, limit + 1)
            .filter { it.id != post.id }
            .take(limit)
    }

    /** Время самого раннего моего комментария в теме; 0 - комментариев нет. */
    private suspend fun oldestCommentAt(groupId: String, topicId: String): Long =
        messageDao.getTopicTextsOldest(groupId, topicId, partPattern, 2).getOrNull(1)?.timestamp ?: 0L

    /**
     * Комментарий ли это (или кусок длинного комментария) к посту, который у
     * меня уже есть. Пост темы узнаём по манифесту (он у всех, кто получил
     * пост роем), а без манифеста - по самому раннему текстовому сообщению
     * темы; в последнем случае конверт с исходным временем раньше «поста» -
     * на самом деле сам пост, приехавший после живого комментария от соседа.
     * Куски фотографий комментариями не бывают. Без поста комментарий не
     * комментарий: он подождёт манифеста в буфере, как кусок.
     */
    suspend fun isCommentPacket(groupId: String, packet: GroupWire.Packet.Message, localId: String, isPart: Boolean): Boolean {
        var postId = manifestDao?.getByTopic(packet.topicId)?.messageId
        if (postId == null) {
            val oldest = topicPost(groupId, packet.topicId) ?: return false
            if (!isPart && packet.sentAtMs in 1L until oldest.timestamp) return false
            postId = oldest.id
        }
        if (isPart) {
            val head = InlineImage.parseTextPart(packet.text)?.headId ?: return false
            return head != postId
        }
        return localId != postId
    }

    /**
     * Открыли комментарии поста на большом канале: попросить у сборщика
     * последние [GroupWire.MAX_COMMENT_BACKFILL] штук (кроме тех, что уже
     * есть) и опись ветки. Не чаще раза в полминуты на тему; [older] -
     * просьба «показать ещё» старше самого раннего, что есть, - идёт сразу.
     * На маленьком канале и у владельца ничего не делает - там комментарии
     * приходят сами. Сборщик, получив просьбу, помечает меня читателем ветки
     * и полторы минуты пересылает новые комментарии сразу; поэтому открытый
     * экран комментариев повторяет просьбу раз в минуту
     * (см. GroupChatViewModel) - она же продлевает подписку.
     */
    suspend fun requestComments(groupId: String, topicId: String, older: Boolean = false) {
        val me = nodeId() ?: return
        val group = groupDao.getGroupById(groupId) ?: return
        if (!commentsViaHubs(group)) return
        val mine = groupDao.getMember(groupId, me) ?: return
        if (mine.isBanned) return
        // Сборщик тоже спрашивает - у других сборщиков: пока он был не в
        // сети, комментарии шли мимо него, а у остальных они есть. Владелец
        // без администраторов спрашивать не у кого.
        val hubs = hubsOf(group, me)
        if (hubs.isEmpty()) return
        val now = clock()
        val key = groupId + '|' + topicId
        val lastAsked = synchronized(commentsRequestedAt) {
            val last = commentsRequestedAt[key] ?: 0L
            if (!older && now - last < COMMENTS_REQUEST_GAP_MS) {
                -1L
            } else {
                commentsRequestedAt[key] = now
                last
            }
        }
        if (lastAsked < 0L) return
        // Обычная просьба - «что появилось после самого свежего, что у меня
        // есть» (с запасом на разницу часов), кроме перечисленных свежих;
        // «показать ещё» - всё старше самого раннего, что у меня есть.
        val newest = commentsNewestFirst(groupId, topicId, 0L, 0L, GroupWire.MAX_COMMENT_HAVE)
        val beforeMs = if (older) oldestCommentAt(groupId, topicId) else 0L
        val afterMs = if (older) 0L else ((newest.firstOrNull()?.timestamp ?: 0L) - COMMENT_CLOCK_SLACK_MS).coerceAtLeast(0L)
        val have = if (older) emptyList() else newest.map { it.id }
        val hub = pickHub(key, hubs, lastAsked)
        deliver(
            groupId,
            GroupWire.buildCommentsRequest(groupId, topicId, GroupWire.MAX_COMMENT_BACKFILL, beforeMs, afterMs, have),
            listOf(hub),
        )
        Log.i(TAG, "comments requested group=$groupId topic=$topicId older=$older have=${have.size} hub=${hub.takeLast(6)}")
    }

    /**
     * Сборщик: ответить на просьбу о комментариях. Отвечает только владелец
     * или администратор канала, участнику (на открытом канале - любому не
     * забаненному), не чаще [COMMENT_REPLIES_PER_MINUTE] раз в минуту.
     * Сначала опись ветки (`cids`), затем сами комментарии обычными
     * конвертами `msg` с автором и временем - как досылка постов, от новых к
     * старым: получатель верит им, потому что прислал их сборщик. Просьба
     * с `want` - точечная (после сравнения описи): шлём ровно перечисленное.
     */
    suspend fun serveComments(senderId: String, packet: GroupWire.Packet.CommentsRequest) {
        val me = nodeId() ?: return
        if (senderId.isBlank() || senderId == me) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (!group.isChannel) return
        val mine = groupDao.getMember(group.id, me) ?: return
        if (!GroupRole.isAdminOrOwner(mine.role) && group.ownerId != me) return
        val requester = groupDao.getMember(group.id, senderId)
        if (requester?.isBanned == true) return
        if (requester == null && !group.isPublic) return
        val topic = groupDao.getTopicById(packet.topicId) ?: return
        if (topic.groupId != group.id) return
        val now = clock()
        noteReader(group.id, packet.topicId, senderId, now)
        if (!commentServeRate.allow(now)) {
            Log.i(TAG, "comments request throttled group=${group.id} from=${senderId.takeLast(6)}")
            return
        }
        val total = commentCount(group.id, packet.topicId)
        val window: List<MessageEntity>
        if (packet.want.isNotEmpty()) {
            // Точечная просьба после сверки описи: ровно перечисленное. Ищем по
            // началам идентификаторов среди всех текстов темы (одни id - это
            // дёшево), поднимаем из базы только найденное.
            val want = packet.want.toHashSet()
            val post = topicPost(group.id, packet.topicId)
            // Списковые функции здесь inline - внутри можно звать suspend-DAO.
            val ids = messageDao.topicTextIds(group.id, packet.topicId, partPattern)
                .filter { it != post?.id && GroupWire.commentIdKey(it) in want }
                .take(packet.limit)
            window = ids.mapNotNull { messageDao.getMessageById(it) }.sortedByDescending { it.timestamp }
        } else {
            val have = packet.have.toHashSet()
            val after = if (packet.beforeMs > 0L) 0L else packet.afterMs
            window = commentsNewestFirst(group.id, packet.topicId, after, packet.beforeMs, GroupWire.MAX_COMMENT_IDS)
                .filter { GroupWire.commentIdKey(it.id) !in have }
                .take(packet.limit)
            // Опись - первой и только на общую просьбу: по ней читатель видит
            // настоящее число комментариев и просит недостающее точечно. В
            // описи - свежие без оглядки на границы просьбы.
            val inventory = commentsNewestFirst(group.id, packet.topicId, 0L, 0L, GroupWire.MAX_COMMENT_IDS)
            deliver(
                group.id,
                GroupWire.buildCommentIds(group.id, packet.topicId, total, inventory.map { it.id }),
                listOf(senderId),
            )
        }
        var sent = 0
        for (row in window) {
            if (sent >= COMMENT_REPLY_PACKETS) break
            val authorName = groupDao.getMember(group.id, row.senderId)?.displayName.orEmpty()
            // Длинный комментарий едет кусками, как длинный пост: вслед за
            // головой - его куски текста (только от автора головы).
            val rows = ArrayList<MessageEntity>()
            rows.add(row)
            if (InlineImage.textTail(row.content) != null) {
                messageDao.getByContentPattern(group.id, InlineImage.textPartPattern(row.id))
                    .filter { it.senderId == row.senderId && InlineImage.parseTextPart(it.content)?.headId == row.id }
                    .forEach { rows.add(it) }
            }
            for (piece in rows) {
                if (sent >= COMMENT_REPLY_PACKETS) break
                if (sent > 0) delay(backfillGapMs)
                deliver(
                    group.id,
                    GroupWire.buildMessage(
                        groupId = group.id,
                        topicId = packet.topicId,
                        text = piece.content,
                        messageId = piece.id,
                        senderName = authorName,
                        authorId = row.senderId,
                        sentAtMs = piece.timestamp,
                        textColor = row.textColor,
                        senderNameColor = row.senderNameColor,
                    ),
                    listOf(senderId),
                )
                sent++
            }
        }
        Log.i(TAG, "comments served group=${group.id} topic=${packet.topicId} to=${senderId.takeLast(6)} sent=$sent of=$total want=${packet.want.size}")
    }

    /**
     * Сборщик получил живой комментарий: передать его тем, кто эту ветку
     * сейчас читает (недавно просил у меня комментарии), кроме автора, того,
     * от кого он пришёл, и других сборщиков (им автор шлёт сам). Повтор у
     * получателя отбрасывается по id.
     */
    suspend fun relayComment(
        group: GroupEntity,
        packet: GroupWire.Packet.Message,
        authorId: String,
        from: String,
        sentAt: Long,
    ) {
        val me = nodeId() ?: return
        val hubs = hubsOf(group, me)
        val readers = recentReaders(group.id, packet.topicId)
            .filter { it != authorId && it != from && it != me && it !in hubs }
        if (readers.isEmpty()) return
        val authorName = packet.senderName.ifBlank { groupDao.getMember(group.id, authorId)?.displayName.orEmpty() }
        deliver(
            group.id,
            GroupWire.buildMessage(
                groupId = group.id,
                topicId = packet.topicId,
                text = packet.text,
                messageId = packet.messageId,
                senderName = authorName,
                authorId = authorId,
                sentAtMs = sentAt,
                textColor = packet.textColor,
                senderNameColor = packet.senderNameColor,
            ),
            readers,
        )
        Log.i(TAG, "comment relayed group=${group.id} topic=${packet.topicId} to=${readers.size}")
    }

    /**
     * Число комментариев по темам, как его знает сборщик (из `cids` и
     * `cinf`): лента канала показывает его вместо своего - у читателя
     * большого канала на телефоне лишь часть ветки. Живёт в памяти на запуск.
     */
    private val hubCommentCounts = ConcurrentHashMap<String, Int>()

    private val _commentCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Число комментариев по темам от сборщика; темы, о которых он не говорил, отсутствуют. */
    val counts: StateFlow<Map<String, Int>> = _commentCounts

    private fun rememberCommentCount(topicId: String, count: Int) {
        if (hubCommentCounts.put(topicId, count) != count) _commentCounts.value = HashMap(hubCommentCounts)
    }

    /** Доверяем ли пакету о комментариях: от владельца или администратора канала, где я состою. */
    private suspend fun trustedHub(groupId: String, senderId: String, me: String): GroupEntity? {
        val group = groupDao.getGroupById(groupId) ?: return null
        if (!group.isChannel || groupDao.getMember(group.id, me) == null) return null
        if (senderId == group.ownerId) return group
        val sender = groupDao.getMember(group.id, senderId) ?: return null
        return if (GroupRole.isAdminOrOwner(sender.role)) group else null
    }

    suspend fun applyCommentCounts(senderId: String, packet: GroupWire.Packet.CommentCounts) {
        val me = nodeId() ?: return
        val group = trustedHub(packet.groupId, senderId, me) ?: return
        for ((topicId, count) in packet.counts) {
            val topic = groupDao.getTopicById(topicId) ?: continue
            if (topic.groupId == group.id) rememberCommentCount(topicId, count)
        }
    }

    /**
     * Опись ветки от сборщика: запомнить число и попросить точечно то, чего
     * у меня нет среди свежих. Сборщик вслед за описью шлёт окно из
     * [GroupWire.MAX_COMMENT_BACKFILL] комментариев - ждём несколько секунд,
     * пока оно доедет, и только потом сверяем опись с базой: так не просим
     * второй раз то, что уже в пути. Точечная просьба - одно окно за раз;
     * остальное - по «показать ещё» и при следующем открытии ветки.
     */
    suspend fun applyCommentIds(senderId: String, packet: GroupWire.Packet.CommentIds) {
        val me = nodeId() ?: return
        val group = trustedHub(packet.groupId, senderId, me) ?: return
        val topic = groupDao.getTopicById(packet.topicId) ?: return
        if (topic.groupId != group.id) return
        rememberCommentCount(packet.topicId, packet.count)
        synchronized(commentsAnsweredAt) { commentsAnsweredAt[group.id + '|' + packet.topicId] = clock() }
        if (packet.ids.isEmpty()) return
        delay(COMMENT_IDS_SETTLE_MS)
        val mineKeys = messageDao.topicTextIds(group.id, packet.topicId, partPattern)
            .map { GroupWire.commentIdKey(it) }.toHashSet()
        val missing = packet.ids.filter { it !in mineKeys }
        val want = missing.take(GroupWire.MAX_COMMENT_HAVE)
        if (want.isEmpty()) return
        deliver(
            group.id,
            GroupWire.buildCommentsRequest(group.id, packet.topicId, GroupWire.MAX_COMMENT_BACKFILL, want = want),
            listOf(senderId),
        )
        Log.i(TAG, "comment ids applied group=${group.id} topic=${packet.topicId} total=${packet.count} missing=${missing.size} want=${want.size}")
    }

    /** Просьб о счётчиках, на которые я как сборщик отвечаю ещё и числом комментариев, в минуту. */
    private val commentCountRate = RateWindow(COMMENT_REPLIES_PER_MINUTE, 60_000L)

    /**
     * Сборщик: вдогонку сводке счётчиков (`pcnt`) - число комментариев тех
     * же постов (`cinf`). Те же условия, что у сводки: отвечает владелец или
     * администратор, участнику (на открытом канале - любому), только на
     * большом канале - на маленьком комментарии и так у всех.
     */
    suspend fun serveCommentCounts(senderId: String, packet: GroupWire.Packet.CountersRequest) {
        val me = nodeId() ?: return
        if (senderId.isBlank() || senderId == me) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (!commentsViaHubs(group)) return
        val mine = groupDao.getMember(group.id, me) ?: return
        if (!GroupRole.isAdminOrOwner(mine.role) && group.ownerId != me) return
        val requester = groupDao.getMember(group.id, senderId)
        if (requester?.isBanned == true) return
        if (requester == null && !group.isPublic) return
        if (!commentCountRate.allow(clock())) return
        val counts = ArrayList<Pair<String, Int>>()
        for (topicId in packet.topicIds.take(GroupWire.MAX_COUNTER_TOPICS)) {
            val topic = groupDao.getTopicById(topicId) ?: continue
            if (topic.groupId != group.id) continue
            counts.add(topicId to commentCount(group.id, topicId))
        }
        if (counts.isEmpty()) return
        deliver(group.id, GroupWire.buildCommentCounts(group.id, counts), listOf(senderId))
    }

    private companion object {
        private const val TAG = "GroupComments"

        /** Сборщик помнит столько читателей на ветку и столько времени после их просьбы. */
        private const val MAX_TOPIC_READERS = 20
        private const val READER_TTL_MS = 90_000L

        /** Просьба о комментариях - не чаще раза в полминуты на тему (кроме «показать ещё»). */
        private const val COMMENTS_REQUEST_GAP_MS = 30_000L

        /** Сколько просьб о комментариях сборщик обслуживает в минуту. */
        private const val COMMENT_REPLIES_PER_MINUTE = 30

        /** Сколько ждать окно комментариев после описи, прежде чем просить недостающее. */
        private const val COMMENT_IDS_SETTLE_MS = 4_000L

        /** Пакетов (головы и куски длинных комментариев) в одном ответе сборщика. */
        private const val COMMENT_REPLY_PACKETS = 40

        /** Запас на разницу часов автора, сборщика и читателя в границе «новее чем». */
        private const val COMMENT_CLOCK_SLACK_MS = 10_000L
    }
}
