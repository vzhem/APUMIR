package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.PostManifestDao
import com.vladimir.messenger.data.local.dao.PostSignerDao
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.PostManifestEntity
import com.vladimir.messenger.data.swarm.PostManifest
import com.vladimir.messenger.util.InlineImage
import kotlinx.coroutines.delay

/**
 * Досылка: старые посты канала и пропущенные сообщения большой группы -
 * вынесены из [GroupRepository] (аудит 2026-10-26, п. 4.1).
 *
 * Вступивший позже видел пустую ленту: тексты и фотографии постов до него не
 * доходили, а в большой группе сообщение до отсутствовавшего доезжает, только
 * если он попадёт в чью-то волну. Здесь обе стороны досылки: как попросить
 * (`requestPosts`, `requestGroupMessages`) и как ответить сиду
 * (`backfillPosts`, `serveGroupMessages`).
 *
 * Поведение перенесено один в один, включая порядок пакетов и паузы между
 * ними: свежие посты идут первыми, текст раньше своих фото, а простой
 * участник отдаёт только то, что у него собрано целиком и подписано
 * манифестом. Окно частоты ([seedRate]) общее с ответом на просьбы о постах и
 * приходит снаружи - свой счётчик в каждой половине удвоил бы бюджет.
 *
 * Числа (сколько постов досылать, сколько соседей спрашивать, паузы) приходят
 * из [GroupRepository] одной таблицей [BackfillLimits]: они одни на всю
 * досылку и меняются в одном месте.
 */
internal class GroupBackfill(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val manifestDao: PostManifestDao?,
    private val signerDao: PostSignerDao?,
    private val clock: () -> Long,
    private val myNodeId: () -> String?,
    private val deliver: suspend (groupId: String, envelope: String, recipients: List<String>) -> Unit,
    private val recipients: suspend (groupId: String, me: String) -> List<String>,
    private val orderPeers: suspend (List<String>) -> List<String>,
    private val swarmGroup: suspend (group: GroupEntity) -> Boolean,
    private val toManifest: (row: PostManifestEntity) -> PostManifest?,
    private val seedRate: RateWindow,
    private val limits: BackfillLimits,
) {

    /** Числа досылки: одни на всю досылку, приходят из репозитория. */
    internal data class BackfillLimits(
        val postsRequestGapMs: Long,
        val postsRequestSlackMs: Long,
        val backfillPosts: Int,
        val swarmRequestPool: Int,
        val swarmRequestPeers: Int,
        val ownerAskOneIn: Int,
        val rosterLimit: Int,
        val repeatMs: Long,
        val maxPackets: Int,
        val packetGapMs: Long,
        val maxManifests: Int,
        val manifestSentPrefix: String,
        val trackedTopics: Int,
    )

    /** Когда у владельца канала в последний раз просили старые посты. */
    private val postsRequestedAt = HashMap<String, Long>()

    /**
     * Попросить у владельца канала последние посты.
     *
     * Вступивший позже получает от владельца только СПИСОК тем (названия),
     * а тексты и фотографии постов - нет: лента у него пустая, комментировать
     * нечего. В запросе перечислены темы, чьи посты уже есть: владелец шлёт
     * только недостающее, поэтому повторный запрос при полной ленте стоит
     * один маленький пакет. Не чаще раза в полминуты на канал.
     */
    suspend fun requestPosts(groupId: String) {
        val me = myNodeId() ?: return
        val group = groupDao.getGroupById(groupId) ?: return
        if (!group.isChannel || group.ownerId == me) return
        if (groupDao.getMember(groupId, me) == null) return
        val now = clock()
        synchronized(postsRequestedAt) {
            val last = postsRequestedAt[groupId] ?: 0L
            if (now - last < limits.postsRequestGapMs) return
            postsRequestedAt[groupId] = now
        }
        // «Есть» - значит есть и текст поста, и все обещанные в нём фото:
        // пост с недоехавшими кусками просим целиком, лишние пакеты получатель
        // отбросит по id.
        val recent = groupDao.getTopics(groupId)
            .sortedByDescending { it.createdAtMs }
            .take(limits.backfillPosts)
            .map { it.id }
        val have = recent.filter { topicId -> isPostComplete(groupId, topicId) }
        val missing = recent.filter { it !in have }
        // Рой: ту же просьбу получают ещё несколько соседей по каналу - любой
        // участник с полной копией и проверенным манифестом ответит. Новичок
        // получает посты, даже когда владелец не в сети, а нагрузка досылки
        // расползается. Недостающие посты делятся между соседями полосами
        // (каждому - своя доля списка), чтобы не получать четыре копии всего.
        // Соседей берём случайно из лучших по ярусу: иначе все подписчики
        // спрашивали бы одних и тех же трёх «самых надёжных».
        val neighbours = orderPeers(
            groupDao.getMembers(groupId)
                .asSequence()
                .map { it.nodeId }
                .filter { it != me && it != group.ownerId }
                .toList(),
        ).take(limits.swarmRequestPool).shuffled()
            .take(if (missing.isEmpty()) 1 else limits.swarmRequestPeers)
        // Владельцу - просьба как раньше: у него есть всё. Но на большом канале
        // (этап 2) владельца бережём: тысячи подписчиков не должны стучаться к
        // нему каждые полминуты - его спрашивают, когда соседей нет или лишь
        // изредка, когда чего-то не хватает.
        val big = group.memberCount > limits.rosterLimit && neighbours.isNotEmpty()
        val askOwner = !big || (missing.isNotEmpty() && (0 until limits.ownerAskOneIn).random() == 0)
        if (askOwner) {
            deliver(
                groupId,
                GroupWire.buildPostsRequest(groupId, limits.backfillPosts, have),
                listOf(group.ownerId),
            )
        }
        neighbours.forEachIndexed { k, peer ->
            val stripeHave = have + missing.filterIndexed { i, _ -> i % neighbours.size != k }
            deliver(
                groupId,
                GroupWire.buildPostsRequest(groupId, limits.backfillPosts, stripeHave),
                listOf(peer),
            )
        }
        Log.i(TAG, "posts requested group=$groupId have=${have.size} missing=${missing.size} owner=$askOwner peers=${neighbours.size}")
    }

    /**
     * Дослать участнику [nodeId] последние посты канала: текст поста и куски
     * его фотографий, каждый под своим исходным id. Автор и время идут в
     * конверте, чтобы у получателя пост числился за настоящим автором и
     * стоял на своём месте в ленте, а не «сейчас от владельца».
     *
     * Только владелец: у него полная копия. Работает в фоне - веер по сети
     * не должен держать поток приёма пакетов.
     */
    suspend fun backfillPosts(group: GroupEntity, nodeId: String, limit: Int, have: Set<String>) {
        val me = myNodeId() ?: return
        if (!group.isChannel || nodeId == me) return
        // Владелец шлёт всё, что у него есть. Остальные участники (рой) - только
        // посты с проверенным манифестом: без него получатель чужую пересылку
        // не примет, и пакеты ушли бы впустую.
        val isOwner = group.ownerId == me
        if (!isOwner && manifestDao == null) return
        // Что этому человеку слали в последние секунды, второй раз не шлём:
        // при вступлении владелец шлёт посты сам, а подписчик, открыв канал,
        // тут же просит их ещё раз - второй веер был бы копией первого. Окно
        // короткое нарочно: если первый веер разминулся с карточкой канала и
        // пропал, следующий запрос (список have отсекает дошедшее) должен
        // сработать, а не упереться в «уже слали». Дубли получатель всё
        // равно отбрасывает по id - речь только об экономии сети.
        val key = group.id + '|' + nodeId
        val now = clock()
        val recent: Set<String> = synchronized(backfillSent) {
            val last = backfillSent[key]
            if (last != null && now - last.first < limits.repeatMs) last.second else emptySet()
        }
        // Последние по времени СОЗДАНИЯ поста, а не по свежести комментариев.
        // Сначала берём последние N, и только потом вычитаем уже имеющиеся:
        // иначе подписчик с полной лентой получал бы следующую порцию более
        // старых постов при каждом открытии канала.
        val topics = groupDao.getTopics(group.id)
            .sortedByDescending { it.createdAtMs }
            .take(limit.coerceIn(1, limits.backfillPosts))
            .filter { it.id !in have }
        val sentIds = HashSet<String>(recent)
        var sent = 0
        // От новых к старым: если связь оборвётся на середине, свежие посты
        // уже дошли. Порядок в ленте задаёт исходное время в конверте.
        for (topic in topics) {
            if (sent >= limits.maxPackets) break
            val thread = messageDao.getTopicMessages(group.id, topic.id)
            val post = thread.firstOrNull { !InlineImage.isPart(it.content) } ?: continue
            val parts = thread.filter { InlineImage.isPart(it.content) && it.senderId == post.senderId }
            val authorName = groupDao.getMember(group.id, post.senderId)?.displayName.orEmpty()
            // Манифест - первым: по нему получатель примет текст и куски от
            // меня, даже если я не владелец. Не владелец без манифеста поста
            // этот пост пропускает; пост с недоехавшими кусками тоже (сид
            // раздаёт только то, что у него целиком).
            val manifest = manifestDao?.get(post.id)?.let { row -> toManifest(row) }
            if (!isOwner) {
                if (manifest == null) continue
                val partTexts = parts.map { it.content }
                if (!InlineImage.textComplete(post.id, post.content, partTexts)) continue
                if (InlineImage.assemble(partTexts).size < InlineImage.photoCount(post.content)) continue
            }
            if (manifest != null && sentIds.add(limits.manifestSentPrefix + post.id)) {
                if (sent > 0) delay(limits.packetGapMs)
                deliver(group.id, GroupWire.buildPostManifest(manifest), listOf(nodeId))
                sent++
            }
            for (row in listOf(post) + parts) {
                if (sent >= limits.maxPackets) break
                if (!sentIds.add(row.id)) continue
                // Пауза между пакетами: сотня публикаций залпом забивает
                // очередь ядра и раздражает публичный брокер.
                if (sent > 0) delay(limits.packetGapMs)
                deliver(
                    group.id,
                    GroupWire.buildMessage(
                        groupId = group.id,
                        topicId = topic.id,
                        text = row.content,
                        messageId = row.id,
                        senderName = authorName,
                        authorId = post.senderId,
                        // Время поста - из подписанного манифеста: получатель
                        // всё равно поверит только ему, а у сида, получившего
                        // пост вживую, в строке стоит время приёма.
                        sentAtMs = if (row.id == post.id && manifest != null) manifest.sentAtMs else row.timestamp,
                        textColor = row.textColor,
                        senderNameColor = row.senderNameColor,
                    ),
                    listOf(nodeId),
                )
                sent++
            }
        }
        synchronized(backfillSent) { backfillSent[key] = now to sentIds }
        Log.i(TAG, "posts backfilled group=${group.id} to=$nodeId topics=${topics.size} packets=$sent owner=$isOwner")
    }

    /** Кому, когда и какие сообщения досылали: ключ «группа|узел». */
    private val backfillSent = HashMap<String, Pair<Long, Set<String>>>()

    /** Темы групп, в которых мы уже просили дослать сообщения: ключ - тема, значение - когда. */
    private val groupMessagesAsked = HashMap<String, Long>()

    /**
     * Добрать сообщения темы большой группы, пропущенные в офлайне (этап 5).
     *
     * Вызывается при открытии темы. В маленькой группе, у владельца которой
     * нет никакой особой роли, сообщения приходят всем напрямую и добирать
     * нечего; в большой сообщение до отсутствовавшего дойдёт, только если он
     * попадёт в чью-то волну или эстафету - поэтому спрашиваем соседей сами.
     * Спрашиваем последние [limits.backfillPosts] сообщений новее последнего
     * известного нам, полосами по времени у [limits.swarmRequestPeers] соседей
     * (умеющих рой, лучших по ярусу), не чаще раза в [limits.postsRequestGapMs]
     * на тему. Соседи отвечают манифестом и конвертами с автором: получатель
     * верит только подписи, так что просить можно кого угодно.
     */
    suspend fun requestGroupMessages(groupId: String, topicId: String) {
        val me = myNodeId() ?: return
        val dao = signerDao ?: return
        val group = groupDao.getGroupById(groupId) ?: return
        if (group.isChannel || !swarmGroup(group)) return
        if (groupDao.getMember(groupId, me) == null) return
        val now = clock()
        synchronized(groupMessagesAsked) {
            val last = groupMessagesAsked[topicId]
            if (last != null && now - last < limits.postsRequestGapMs) return
            if (groupMessagesAsked.size >= limits.trackedTopics) groupMessagesAsked.clear()
            groupMessagesAsked[topicId] = now
        }
        val capable = dao.allSelfAttestedIds().toHashSet()
        val peers = orderPeers(recipients(groupId, me).filter { it in capable })
        if (peers.isEmpty()) return
        // Из первых limits.swarmRequestPool по ярусу берём limits.swarmRequestPeers
        // случайных: одни и те же лучшие узлы не должны обслуживать всех.
        val chosen = peers.take(limits.swarmRequestPool).shuffled().take(limits.swarmRequestPeers)
        // «Новее моего последнего» с запасом: соседи могли получить сообщение
        // чуть раньше меня, а повтор получатель отбрасывает по id.
        val latest = messageDao.latestTopicTimestamp(groupId, topicId) ?: 0L
        val afterMs = (latest - limits.postsRequestSlackMs).coerceAtLeast(0L)
        chosen.forEachIndexed { k, peer ->
            deliver(
                groupId,
                GroupWire.buildManifestsRequest(groupId, topicId, afterMs, limits.backfillPosts, k, chosen.size),
                listOf(peer),
            )
        }
        Log.i(TAG, "group messages requested group=$groupId topic=$topicId after=$afterMs peers=${chosen.size}")
    }

    /**
     * Дослать соседу сообщения темы большой группы по его просьбе `mreq`
     * (этап 5): те, что у меня собраны целиком и подписаны манифестом,
     * новее указанного времени и из его полосы. Порядок и паузы - как у
     * досылки постов канала; сколько чужих просьб обслуживаю в минуту,
     * ограничивает [seedRate].
     */
    suspend fun serveGroupMessages(senderId: String, packet: GroupWire.Packet.ManifestsRequest) {
        val dao = manifestDao ?: return
        val me = myNodeId() ?: return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (group.isChannel || !swarmGroup(group)) return
        if (groupDao.getMember(packet.groupId, me) == null) return
        val requester = groupDao.getMember(packet.groupId, senderId) ?: return
        if (requester.isBanned) return
        if (!seedRate.allow(clock())) return
        // Самые новые из полосы просителя, но шлём от старых к новым: так
        // они лягут в ленту по порядку, а превью группы останется за самым
        // свежим. Оборвётся связь - следующая просьба спросит остаток.
        val rows = dao.latestForTopic(packet.topicId, limits.maxManifests)
            .filter { it.groupId == group.id && it.sentAtMs > packet.afterMs }
            .filter { packet.stripes == 1 || Math.floorMod(it.sentAtMs, packet.stripes.toLong()).toInt() == packet.stripe }
            .take(packet.limit.coerceIn(1, GroupWire.MAX_MANIFESTS_REQUEST))
            .reversed()
        var sent = 0
        for (row in rows) {
            if (sent >= limits.maxPackets) break
            val manifest = toManifest(row) ?: continue
            val head = messageDao.getMessageById(manifest.messageId) ?: continue
            val partIds = manifest.parts.map { it.messageId }
            val parts = partIds.mapNotNull { messageDao.getMessageById(it) }
            // Сид раздаёт только то, что у него целиком.
            if (parts.size != partIds.size) continue
            val authorName = groupDao.getMember(group.id, manifest.authorId)?.displayName.orEmpty()
            if (sent > 0) delay(limits.packetGapMs)
            deliver(group.id, GroupWire.buildPostManifest(manifest), listOf(senderId))
            sent++
            for (msg in listOf(head) + parts) {
                if (sent >= limits.maxPackets) break
                delay(limits.packetGapMs)
                deliver(
                    group.id,
                    GroupWire.buildMessage(
                        groupId = group.id,
                        topicId = manifest.topicId,
                        text = msg.content,
                        messageId = msg.id,
                        senderName = authorName,
                        authorId = manifest.authorId,
                        sentAtMs = if (msg.id == manifest.messageId) manifest.sentAtMs else msg.timestamp,
                        textColor = msg.textColor,
                        senderNameColor = msg.senderNameColor,
                    ),
                    listOf(senderId),
                )
                sent++
            }
        }
        Log.i(TAG, "group messages served group=${group.id} topic=${packet.topicId} to=$senderId messages=${rows.size} packets=$sent")
    }

    /** Пост темы на месте целиком: текст есть (со всеми кусками) и все обещанные фото собрались. */
    private suspend fun isPostComplete(groupId: String, topicId: String): Boolean {
        val thread = messageDao.getTopicMessages(groupId, topicId)
        val post = thread.firstOrNull { !InlineImage.isPart(it.content) } ?: return false
        if (!InlineImage.expectsParts(post.content)) return true
        val parts = thread.filter { InlineImage.isPart(it.content) && it.senderId == post.senderId }.map { it.content }
        if (!InlineImage.textComplete(post.id, post.content, parts)) return false
        return InlineImage.assemble(parts).size >= InlineImage.photoCount(post.content)
    }

    private companion object {
        private const val TAG = "GroupBackfill"
    }
}
