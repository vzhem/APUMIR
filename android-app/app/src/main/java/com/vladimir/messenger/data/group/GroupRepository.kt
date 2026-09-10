package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.ui.theme.AvatarStore
import com.vladimir.messenger.data.local.dao.DirectoryDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.AvatarDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import com.vladimir.messenger.data.local.entity.AvatarEntity
import com.vladimir.messenger.data.local.entity.NicknameEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupInviteEntity
import com.vladimir.messenger.data.local.entity.GroupJoinRequestEntity
import com.vladimir.messenger.data.local.entity.GroupMemberEntity
import com.vladimir.messenger.data.local.entity.GroupMessageStatEntity
import com.vladimir.messenger.data.local.entity.GroupTopicEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.local.dao.PostManifestDao
import com.vladimir.messenger.data.local.dao.PostSignerDao
import com.vladimir.messenger.data.local.entity.PostManifestEntity
import com.vladimir.messenger.data.local.entity.PostSignerEntity
import com.vladimir.messenger.data.swarm.Ed25519
import com.vladimir.messenger.data.swarm.ManifestPart
import com.vladimir.messenger.data.swarm.PostManifest
import com.vladimir.messenger.data.swarm.SwarmBuffer
import com.vladimir.messenger.util.InlineImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Вся логика раздела «Группы»: создание, темы, заявки, приглашения, права,
 * закрепы и статистика.
 *
 * Транспорт намеренно вынесен в [GroupDelivery], а идентификация и имя — во
 * внедрённые лямбды, поэтому класс проверяется обычными JVM-тестами без
 * Android и без ядра.
 */
class GroupRepository(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val delivery: GroupDelivery,
    private val myNodeId: () -> String?,
    private val myDisplayName: () -> String,
    /** Порог ранга: создание групп доступно с ранга «Проводник» и выше. */
    private val canCreateGroups: () -> Boolean,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val directoryDao: DirectoryDao,
    private val contactIds: suspend () -> List<String>,
    private val nicknameDao: NicknameDao,
    /** Моё @имя без собаки (null - имени ещё нет). */
    private val myUsername: () -> String?,
    /** Время моей регистрации имени. */
    private val myRegisteredAt: () -> Long,
    /** Система сняла моё имя из-за более раннего владельца: UI предложит новое. */
    private val onUsernameConflict: () -> Unit,
    /** Присланные аватары контактов. */
    private val avatarDao: AvatarDao,
    /** Мой сжатый аватар (JPEG base64) или null, если аватара нет. */
    private val myAvatarB64: () -> String?,
    /**
     * Узнали настоящее @имя узла: контакт с заглушкой вместо имени должен
     * переименоваться, а его двойники от прежних установок - схлопнуться.
     * По умолчанию ничего не делает, чтобы JVM-тесты обходились без Android.
     */
    private val onNicknameLearned: suspend (ownerId: String, name: String) -> Unit =
        { _, _ -> },
    /**
     * Куда уходят фоновые рассылки (каталог, @имя, аватар).
     *
     * Раньше создание группы ЖДАЛО, пока каталог разойдётся по всем контактам:
     * на слабой сети это десятки секунд с крутилкой на экране, хотя сама
     * группа уже была записана в базу. Теперь рассылка живёт своей жизнью, а
     * экран открывается сразу.
     */
    private val backgroundScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Рой постов (этап 1, docs/CHANNEL_SWARM_DESIGN.md): манифесты постов,
     * закреплённые ключи подписантов и подпись моих манифестов. Без
     * хранилищ (null) всё работает по-старому: посты без манифестов, досылка
     * только от владельца.
     */
    private val manifestDao: PostManifestDao? = null,
    private val signerDao: PostSignerDao? = null,
    /** Подписать манифест моим ключом личности; null - подпись недоступна. */
    private val signManifest: (PostManifest) -> PostManifest? = { null },
    /** Мой открытый ключ подписи (32 байта) или null. */
    private val myPostKey: () -> ByteArray? = { null },
    /** Соседи по рою в порядке предпочтения (свои, проверенные, стабильные…). */
    private val orderPeers: suspend (List<String>) -> List<String> = { it },
    /**
     * Счётчики через владельца (рой, этап 3): просьба о сводке `pcreq` и сама
     * сводка `pcnt` разбираются здесь, а считает и применяет их
     * PostCounterRepository. Без обработчиков пакеты молча отбрасываются.
     */
    private val onCountersRequest: suspend (senderId: String, packet: GroupWire.Packet.CountersRequest) -> Unit = { _, _ -> },
    private val onCounters: suspend (senderId: String, packet: GroupWire.Packet.Counters) -> Unit = { _, _ -> },
) {

    /**
     * Мой идентификатор, спрошенный у ядра ОДИН раз.
     *
     * `myNodeId()` уходит в ядро и ждёт его внутренний замок. Он вызывается в
     * горячих местах: `toSummary` зовёт его для КАЖДОЙ группы при каждом
     * обновлении списка, а левая колонка на экране группы показывает все
     * группы разом. На входе в группу это давало пачку вызовов в ядро подряд
     * и «Приложение не отвечает». Идентификатор за время работы не меняется,
     * поэтому первое ненулевое значение запоминаем навсегда.
     */
    @Volatile
    private var cachedNodeId: String? = null

    private fun myId(): String? {
        cachedNodeId?.let { return it }
        val fresh = myNodeId()?.takeIf { it.isNotBlank() }
        if (fresh != null) cachedNodeId = fresh
        return fresh
    }

    /**
     * Узлы, для которых состав уже запрашивали: повторно не спрашиваем,
     * чтобы одно неизвестное имя не породило поток запросов.
     */
    private val rosterAsked: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * Когда последний раз рассылали каталог, @имя и аватар.
     *
     * Эти три рассылки ничего не решают в переписке: они лишь помогают
     * знакомым увидеть мои публичные группы, моё @имя и мою картинку. Раньше
     * каталог уходил при КАЖДОМ открытии раздела «Группы», то есть по десятку
     * раз за вечер и всегда по мобильному интернету. Теперь между рассылками
     * выдерживается пауза, а важные события (создал группу, сменил имя или
     * аватар) шлют сразу через force = true.
     */
    private var lastDirectoryPublishMs: Long = 0L
    private var lastNicknamePublishMs: Long = 0L
    private var lastAvatarPublishMs: Long = 0L
    /** Когда последний раз просили конкретный узел представиться. */
    private val lastWhoIsAskedMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Для экранов: доступно ли текущему рангу создание групп. */
    fun canCreateGroupsNow(): Boolean = canCreateGroups()

    /**
     * Создание группы. Возвращает ошибку, если ранг не даёт права создавать
     * группы, — правило из MASTER_PLAN и FileTransferRankPolicy.canCreateGroup.
     */
    suspend fun createGroup(
        title: String,
        about: String,
        isPublic: Boolean,
        topicsEnabled: Boolean,
        /**
         * true - создаём канал: та же доставка и те же участники, но посты
         * пишут администраторы, а обсуждение живёт в комментариях под постом.
         */
        isChannel: Boolean = false,
    ): Result<GroupSummary> {
        if (!canCreateGroups()) {
            return Result.failure(
                IllegalStateException("Создание групп доступно с ранга «Проводник»")
            )
        }
        val me = myId()
            ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))

        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            return Result.failure(IllegalArgumentException("Название группы не может быть пустым"))
        }
        if (cleanTitle.length > MAX_TITLE_CHARS) {
            return Result.failure(IllegalArgumentException("Название длиннее $MAX_TITLE_CHARS символов"))
        }

        val now = clock()
        val groupId = idFactory()
        val slug = GroupInviteLinks.newSlug()
        val myName = myDisplayName()

        groupDao.insertGroup(
            GroupEntity(
                id = groupId,
                title = cleanTitle,
                about = about.trim().take(MAX_ABOUT_CHARS),
                ownerId = me,
                ownerName = myName,
                isPublic = isPublic,
                topicsEnabled = topicsEnabled,
                isChannel = isChannel,
                createdAtMs = now,
                memberCount = 1,
                inviteSlug = slug,
                memberPermissions = GroupPermissions.Member.DEFAULT,
            )
        )
        groupDao.insertMember(
            GroupMemberEntity(
                groupId = groupId,
                nodeId = me,
                displayName = myName,
                role = GroupRole.OWNER,
                joinedAtMs = now,
                permissions = GroupPermissions.Admin.ALL,
            )
        )
        groupDao.insertInvite(
            GroupInviteEntity(
                slug = slug,
                groupId = groupId,
                createdBy = me,
                createdAtMs = now,
                // В частной группе даже по ссылке нужно одобрение администратора.
                requestApproval = !isPublic,
            )
        )
        if (topicsEnabled) {
            groupDao.insertTopic(
                GroupTopicEntity(
                    id = idFactory(),
                    groupId = groupId,
                    name = GENERAL_TOPIC_NAME,
                    ownerId = me,
                    ownerName = myName,
                    createdAtMs = now,
                    isGeneral = true,
                )
            )
        }

        Log.i(TAG, "group created id=$groupId public=$isPublic topics=$topicsEnabled")
        // Рассылка каталога - в фон. Группа уже в базе, ждать сеть незачем.
        if (isPublic) {
            backgroundScope.launch {
                runCatching { publishMyDirectory(force = true) }
                    .onFailure { Log.w(TAG, "directory publish failed: ${it.message}") }
            }
        }
        return runCatching { requireSummary(groupId) }
    }

    // ── Список групп ──────────────────────────────────────────────────────────

    // ВАЖНО: `map` у холодного потока исполняется на потоке СОБИРАТЕЛЯ, а
    // toSummary ходит в базу (участник, счётчик заявок). Без flowOn это чтение
    // с диска происходило на главном потоке и подвешивало экран - тот же класс
    // аварии, что и в ObserveNetworkStatusUseCase.
    fun observeGroups(): Flow<List<GroupSummary>> =
        groupDao.observeGroups()
            .map { list -> list.map { toSummary(it) } }
            .flowOn(Dispatchers.IO)

    fun observeGroup(groupId: String): Flow<GroupSummary?> =
        groupDao.observeGroup(groupId)
            .map { it?.let { g -> toSummary(g) } }
            .flowOn(Dispatchers.IO)

    suspend fun summary(groupId: String): GroupSummary? =
        groupDao.getGroupById(groupId)?.let { toSummary(it) }

    private suspend fun requireSummary(groupId: String): GroupSummary =
        toSummary(groupDao.getGroupById(groupId) ?: error("group $groupId vanished"))

    /** Каналы этого телефона - для раздела «Каналы» на главном экране. */
    fun observeChannels(): Flow<List<GroupSummary>> =
        groupDao.observeChannels()
            .map { list -> list.map { toSummary(it) } }
            .flowOn(Dispatchers.IO)

    /** Сетевой каталог: чужие публичные группы и каналы для поиска. */
    fun observeDirectory(): Flow<List<DirectoryEntity>> = directoryDao.observeAll()

    /**
     * Роевая публикация каталога: владелец рассказывает контактам о своих
     * публичных группах и каналах, контакты передают запись дальше (hops не
     * больше [MAX_DIR_HOPS) - так поиск находит созданное другими людьми без
     * центрального сервера.
     */
    suspend fun publishMyDirectory(force: Boolean = false) {
        if (!force && !dueNow(lastDirectoryPublishMs, GOSSIP_MIN_INTERVAL_MS)) {
            Log.i(TAG, "dir publish skipped: too soon")
            return
        }
        val me = myId() ?: return
        val ids = contactIds()
        if (ids.isEmpty()) return
        for (g in groupDao.getOwnPublishable(me)) {
            val approval = groupDao.getInviteBySlug(g.inviteSlug)?.requestApproval ?: !g.isPublic
            val envelope = GroupWire.buildDirectory(
                groupId = g.id,
                title = g.title,
                about = g.about,
                ownerId = me,
                slug = g.inviteSlug,
                isChannel = g.isChannel,
                needsApproval = approval,
                hops = 0,
            )
            runCatching { delivery.deliver(g.id, envelope, ids) }
                .onFailure { Log.w(TAG, "dir publish failed: ${it.message}") }
        }
        lastDirectoryPublishMs = clock()
    }

    /**
     * Роевая публикация моего @имени: контакты сохраняют в реестр и передают
     * дальше, поэтому сеть знает, кто и когда зарегистрировал имя.
     */
    suspend fun publishMyNickname(force: Boolean = false) {
        if (!force && !dueNow(lastNicknamePublishMs, GOSSIP_MIN_INTERVAL_MS)) return
        val me = myId() ?: return
        val name = myUsername() ?: return
        val ids = contactIds()
        if (ids.isEmpty()) return
        val envelope = GroupWire.buildNick(
            ownerId = me,
            name = name,
            registeredAtMs = myRegisteredAt(),
            hops = 0,
        )
        runCatching { delivery.deliver("nicknames", envelope, ids) }
            .onFailure { Log.w(TAG, "nick publish failed: ${it.message}") }
        lastNicknamePublishMs = clock()
    }

    /**
     * Роевая публикация моего аватара: контакты сохраняют и показывают вместо
     * инициалов. Маленький JPEG, отправляем нечасто.
     */
    suspend fun publishMyAvatar(force: Boolean = false) {
        if (!force && !dueNow(lastAvatarPublishMs, GOSSIP_MIN_INTERVAL_MS)) return
        val me = myId() ?: return
        val b64 = myAvatarB64() ?: return
        val ids = contactIds()
        if (ids.isEmpty()) return
        val envelope = GroupWire.buildAvatar(
            ownerId = me,
            dataB64 = b64,
            updatedAtMs = clock(),
            hops = 0,
        )
        runCatching { delivery.deliver("avatars", envelope, ids) }
            .onFailure { Log.w(TAG, "avatar publish failed: ${it.message}") }
        lastAvatarPublishMs = clock()
    }

    /**
     * Попросить узел представиться: прислать @имя и аватар.
     *
     * Зовём, когда собеседник показан техническим именем. Один запрос на узел
     * не чаще [WHOIS_MIN_INTERVAL_MS], чтобы переписка не превращалась в поток
     * служебных пакетов.
     */
    suspend fun requestIdentity(peerId: String) {
        if (peerId.isBlank()) return
        val me = myId() ?: return
        if (peerId == me) return
        val last = lastWhoIsAskedMs[peerId] ?: 0L
        if (last != 0L && clock() - last < WHOIS_MIN_INTERVAL_MS) return
        lastWhoIsAskedMs[peerId] = clock()
        val envelope = GroupWire.buildWhoIs(me)
        runCatching { delivery.deliver("whois", envelope, listOf(peerId)) }
            .onFailure { Log.w(TAG, "whois send failed: ${it.message}") }
        Log.i(TAG, "whois asked peer=$peerId")
    }

    /** Адресный ответ на «представься»: моё @имя и мой аватар. */
    private suspend fun sendMyIdentityTo(peerId: String) {
        if (peerId.isBlank()) return
        val me = myId() ?: return
        myUsername()?.let { name ->
            val envelope = GroupWire.buildNick(
                ownerId = me,
                name = name,
                registeredAtMs = myRegisteredAt(),
                hops = 0,
            )
            runCatching { delivery.deliver("nicknames", envelope, listOf(peerId)) }
                .onFailure { Log.w(TAG, "whois nick reply failed: ${it.message}") }
        }
        myAvatarB64()?.let { b64 ->
            val envelope = GroupWire.buildAvatar(
                ownerId = me,
                dataB64 = b64,
                updatedAtMs = clock(),
                hops = 0,
            )
            runCatching { delivery.deliver("avatars", envelope, listOf(peerId)) }
                .onFailure { Log.w(TAG, "whois avatar reply failed: ${it.message}") }
        }
    }

    /** Пора ли повторять фоновую рассылку. */
    private fun dueNow(lastMs: Long, minIntervalMs: Long): Boolean =
        lastMs == 0L || clock() - lastMs >= minIntervalMs

    /** Аватары из базы - в витрину для экранов. */
    suspend fun loadAvatars() {
        val rows = runCatching { avatarDao.all() }.getOrDefault(emptyList())
        AvatarStore.putAll(rows.associate { it.ownerId to it.dataB64 })
    }

    private suspend fun handleAvatar(packet: GroupWire.Packet.Avatar, senderId: String) {
        if (packet.ownerId == myId()) return // свой из сети не храним
        if (packet.dataB64.length > 40_000) return // защита от мусора
        val older = avatarDao.byOwner(packet.ownerId)
        if (older == null || packet.updatedAtMs >= older.updatedAtMs) {
            avatarDao.upsert(
                AvatarEntity(
                    ownerId = packet.ownerId,
                    dataB64 = packet.dataB64,
                    updatedAtMs = packet.updatedAtMs,
                )
            )
            AvatarStore.put(packet.ownerId, packet.dataB64)
        }
        // Эпидемия дальше, кроме отправителя.
        if (packet.hops < MAX_DIR_HOPS) {
            val next = contactIds().filter { it != senderId }
            if (next.isNotEmpty()) {
                val envelope = GroupWire.buildAvatar(
                    ownerId = packet.ownerId,
                    dataB64 = packet.dataB64,
                    updatedAtMs = packet.updatedAtMs,
                    hops = packet.hops + 1,
                )
                runCatching { delivery.deliver("avatars", envelope, next) }
            }
        }
    }

    /**
     * Аватар группы/канала: хранится в том же роевом реестре avatars, ключ -
     * «g:» + id группы. Админ сохранил - участники получают пакетом avat и
     * показывают картинку в списке и в шапке группы (раунд 42).
     */
    suspend fun saveGroupAvatar(groupId: String, dataB64: String) {
        val key = GROUP_AVATAR_PREFIX + groupId
        avatarDao.upsert(AvatarEntity(ownerId = key, dataB64 = dataB64, updatedAtMs = clock()))
        AvatarStore.put(key, dataB64)
    }

    suspend fun publishGroupAvatar(groupId: String, dataB64: String) {
        val ids = contactIds()
        if (ids.isEmpty()) return
        val envelope = GroupWire.buildAvatar(
            ownerId = GROUP_AVATAR_PREFIX + groupId,
            dataB64 = dataB64,
            updatedAtMs = clock(),
            hops = 0,
        )
        runCatching { delivery.deliver("avatars", envelope, ids) }
            .onFailure { Log.w(TAG, "group avatar publish failed: ${it.message}") }
    }

    private suspend fun handleNick(packet: GroupWire.Packet.Nick, senderId: String) {
        // @имя человека - самое надёжное, что о нём известно: оно приходит
        // роевой рассылкой и переживает переустановку. Записываем его в контакт
        // и подменяем им заглушку «Contact a1b2c3d4», из-за которой собеседник
        // так и висел набором букв и цифр, даже когда имя уже было известно.
        runCatching { onNicknameLearned(packet.ownerId, packet.name) }
            .onFailure { Log.w(TAG, "nick apply failed: ${it.message}") }
        nicknameDao.upsert(
            NicknameEntity(
                ownerId = packet.ownerId,
                name = packet.name,
                registeredAtMs = packet.registeredAtMs,
            )
        )
        // Спор об имени: такое же имя у чужого владельца. Прав тот, у кого
        // регистрация раньше (при равенстве времени - тот, у кого id меньше).
        val me = myId() ?: return
        val mine = myUsername() ?: return
        if (packet.ownerId != me && packet.name.equals(mine, ignoreCase = true)) {
            val myAt = myRegisteredAt()
            val iLost = packet.registeredAtMs < myAt ||
                (packet.registeredAtMs == myAt && packet.ownerId < me)
            if (iLost) {
                Log.w(TAG, "username @$mine lost to ${packet.ownerId}, clearing")
                onUsernameConflict()
            }
        }
        // Эпидемия дальше, кроме отправителя.
        if (packet.hops < MAX_DIR_HOPS) {
            val next = contactIds().filter { it != senderId }
            if (next.isNotEmpty()) {
                val envelope = GroupWire.buildNick(
                    ownerId = packet.ownerId,
                    name = packet.name,
                    registeredAtMs = packet.registeredAtMs,
                    hops = packet.hops + 1,
                )
                runCatching { delivery.deliver("nicknames", envelope, next) }
            }
        }
    }

    private suspend fun handleDirectory(packet: GroupWire.Packet.Directory, senderId: String) {
        if (packet.groupId.isBlank() || packet.title.isBlank()) return
        if (packet.ownerId == myId()) return // своё не храним
        directoryDao.upsert(
            DirectoryEntity(
                groupId = packet.groupId,
                title = packet.title,
                about = packet.about,
                ownerId = packet.ownerId,
                slug = packet.slug,
                isChannel = packet.isChannel,
                needsApproval = packet.needsApproval,
                hops = packet.hops,
                updatedAtMs = clock(),
            )
        )
        // Эпидемия: передаём дальше, пока дальность позволяет, кроме отправителя.
        if (packet.hops < MAX_DIR_HOPS) {
            val next = contactIds().filter { it != senderId }
            if (next.isNotEmpty()) {
                val envelope = GroupWire.buildDirectory(
                    groupId = packet.groupId,
                    title = packet.title,
                    about = packet.about,
                    ownerId = packet.ownerId,
                    slug = packet.slug,
                    isChannel = packet.isChannel,
                    needsApproval = packet.needsApproval,
                    hops = packet.hops + 1,
                )
                runCatching { delivery.deliver(packet.groupId, envelope, next) }
            }
        }
    }

    private suspend fun toSummary(g: GroupEntity): GroupSummary {
        val me = myId().orEmpty()
        val mine = groupDao.getMember(g.id, me)
        return GroupSummary(
            id = g.id,
            title = g.title,
            about = g.about,
            ownerId = g.ownerId,
            isPublic = g.isPublic,
            topicsEnabled = g.topicsEnabled,
            isChannel = g.isChannel,
            memberPermissions = g.memberPermissions,
            memberCount = g.memberCount,
            unreadCount = g.unreadCount,
            lastMessagePreview = g.lastMessagePreview,
            lastMessageAtMs = g.lastMessageAtMs,
            myRole = mine?.role ?: GroupRole.MEMBER,
            pendingRequests = if (GroupRole.isAdminOrOwner(mine?.role ?: "")) {
                groupDao.countPendingRequests(g.id)
            } else {
                0
            },
        )
    }

    // ── Темы ──────────────────────────────────────────────────────────────────

    fun observeTopics(groupId: String): Flow<List<TopicSummary>> =
        groupDao.observeTopics(groupId)
            .map { list -> list.map { toTopicSummary(it) } }
            .flowOn(Dispatchers.IO)

    private fun toTopicSummary(t: GroupTopicEntity) = TopicSummary(
        id = t.id,
        groupId = t.groupId,
        name = t.name,
        iconEmoji = t.iconEmoji,
        messageCount = t.messageCount,
        unreadCount = t.unreadCount,
        lastMessagePreview = t.lastMessagePreview,
        lastMessageAtMs = t.lastMessageAtMs,
        isClosed = t.isClosed,
        isGeneral = t.isGeneral,
    )

    /**
     * Новая тема. Создавать могут владелец и администраторы с правом
     * MANAGE_TOPICS. Счётчик сообщений темы начинается с нуля.
     */
    suspend fun createTopic(
        groupId: String,
        name: String,
        iconEmoji: String = "",
    ): Result<TopicSummary> {
        val clean = name.trim()
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("Название темы не может быть пустым"))
        }
        if (clean.length > MAX_TOPIC_CHARS) {
            return Result.failure(IllegalArgumentException("Название темы длиннее $MAX_TOPIC_CHARS символов"))
        }
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canManageTopics(member.role, member.permissions)) {
            return Result.failure(SecurityException("Нет права управлять темами"))
        }

        val now = clock()
        val topicId = idFactory()
        groupDao.insertTopic(
            GroupTopicEntity(
                id = topicId,
                groupId = groupId,
                name = clean,
                ownerId = me,
                ownerName = member.displayName,
                iconEmoji = iconEmoji,
                createdAtMs = now,
            )
        )
        broadcast(
            groupId,
            GroupWire.buildTopicCreated(groupId, topicId, clean, iconEmoji),
            excludeSelf = true,
        )
        return runCatching { toTopicSummary(groupDao.getTopicById(topicId) ?: error("topic vanished")) }
    }

    suspend fun renameTopic(topicId: String, name: String): Result<Unit> = withTopicAdminRight(topicId) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            return@withTopicAdminRight Result.failure(IllegalArgumentException("Пустое название"))
        }
        groupDao.renameTopic(topicId, clean.take(MAX_TOPIC_CHARS))
        Result.success(Unit)
    }

    suspend fun setTopicClosed(topicId: String, closed: Boolean): Result<Unit> =
        withTopicAdminRight(topicId) {
            groupDao.updateTopicClosed(topicId, closed)
            Result.success(Unit)
        }

    /** Общая обёртка: действие над темой требует права MANAGE_TOPICS. */
    private suspend fun withTopicAdminRight(
        topicId: String,
        block: suspend () -> Result<Unit>,
    ): Result<Unit> {
        val topic = groupDao.getTopicById(topicId)
            ?: return Result.failure(IllegalStateException("Тема не найдена"))
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(topic.groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canManageTopics(member.role, member.permissions)) {
            return Result.failure(SecurityException("Нет права управлять темами"))
        }
        return block()
    }

    // ── Отправка сообщения ────────────────────────────────────────────────────

    /**
     * Сообщение в тему. [photos] - фотографии поста (jpeg base64), каждая
     * уходит отдельными пакетами-кусками ВСЛЕД за текстом: один пакет с
     * целой фотографией не проходит через публичный брокер (потолок ~10 КБ),
     * а куски по [InlineImage.MAX_PART_B64_CHARS] проходят. В тексте
     * остаётся пометка «фотографий: n», по ней лента ждёт куски.
     *
     * Длинный текст (рой, этап 3) режется так же: в самом сообщении остаётся
     * первый кусок и пометка «продолжение в n кусках», остальные куски идут
     * отдельными пакетами той же темы ПЕРЕД кусками фото - слова важнее.
     * Раньше пост длиннее ~3 500 знаков через брокер не проходил вовсе.
     */
    suspend fun sendMessage(
        groupId: String,
        topicId: String,
        text: String,
        photos: List<String> = emptyList(),
    ): Result<String> {
        val attached = photos.filter { it.isNotBlank() }.take(InlineImage.MAX_PHOTOS)
        val words = InlineImage.stripImage(text)
        if (words.isEmpty() && attached.isEmpty()) return Result.failure(IllegalArgumentException("Пустое сообщение"))
        if (words.length > MAX_MESSAGE_CHARS) {
            return Result.failure(IllegalArgumentException("Сообщение длиннее $MAX_MESSAGE_CHARS символов"))
        }
        val chunks = InlineImage.splitText(words)
        val textTail = chunks.drop(1)
        if (textTail.size > InlineImage.MAX_TEXT_PARTS) {
            return Result.failure(IllegalArgumentException("Сообщение слишком длинное: сократите текст"))
        }
        val body = InlineImage.headContent(chunks.first(), 0, textTail.size, attached.size)
        if (body.isEmpty()) return Result.failure(IllegalArgumentException("Пустое сообщение"))
        // Куски фото - по InlineImage.MAX_PART_B64_CHARS на каждый, до трёх на
        // фото. Манифест поста вмещает не больше PostManifest.MAX_PARTS кусков
        // (столько понимают и прошлые версии): очень длинный текст вместе с
        // шестью фото туда не помещается - честно отказываем сразу, а не
        // теряем куски молча.
        val photoParts = attached.sumOf { (it.length + InlineImage.MAX_PART_B64_CHARS - 1) / InlineImage.MAX_PART_B64_CHARS }
        if (textTail.size + photoParts > PostManifest.MAX_PARTS) {
            return Result.failure(
                IllegalArgumentException("Слишком длинный текст для поста с таким числом фото: сократите текст или уберите фото"),
            )
        }
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (member.isBanned) return Result.failure(SecurityException("Вы ограничены в этой группе"))

        val isAdmin = GroupRole.isAdminOrOwner(member.role)
        if (!isAdmin && !GroupPermissions.has(effectiveMemberMask(group), GroupPermissions.Member.SEND_MESSAGES)) {
            return Result.failure(SecurityException("Отправка сообщений в этой группе запрещена"))
        }

        val topic = resolveTopic(group, topicId)
            ?: return Result.failure(IllegalStateException("Тема не найдена"))
        if (topic.isClosed && !isAdmin) {
            return Result.failure(SecurityException("Тема закрыта для новых сообщений"))
        }

        val now = clock()
        val messageId = idFactory()
        // Пост канала - первое сообщение своей темы; всё остальное в теме -
        // комментарии, они роем не ходят и манифеста не получают.
        val isChannelPost = group.isChannel && isAdmin &&
            messageDao.countTopicMessages(groupId, topic.id) == 0
        messageDao.insertMessage(
            MessageEntity(
                id = messageId,
                chatId = groupId,
                senderId = me,
                content = body,
                timestamp = now,
                status = "SENT",
                isFromMe = true,
                channel = "GROUP",
                recipientId = "",
                topicId = topic.id,
            )
        )
        registerOutgoing(groupId, topic, body, me, now)
        // Куски текста и фотографий ложатся в базу СРАЗУ: лента автора
        // показывает пост целиком, не дожидаясь, пока веер уйдёт по сети.
        // Сначала текст, потом фото - в таком же порядке они и уходят.
        val partTexts = ArrayList<String>()
        textTail.forEachIndexed { i, piece ->
            partTexts.add(InlineImage.buildTextPart(messageId, 0, i + 1, textTail.size, piece))
        }
        attached.forEachIndexed { photoIndex, b64 ->
            partTexts.addAll(InlineImage.splitPhoto(photoIndex + 1, b64))
        }
        val partRows = ArrayList<MessageEntity>()
        for (partText in partTexts) {
            val row = MessageEntity(
                id = idFactory(),
                chatId = groupId,
                senderId = me,
                content = partText,
                // Время чуть позже текста, чтобы порядок в теме сохранился.
                timestamp = now + 1 + partRows.size,
                status = "SENT",
                isFromMe = true,
                channel = "GROUP",
                recipientId = "",
                topicId = topic.id,
            )
            messageDao.insertMessage(row)
            partRows.add(row)
        }

        // Рой: пост канала от владельца или администратора получает подписанный
        // манифест. По нему подписчики принимают текст и куски от кого угодно и
        // сами досылают пост опоздавшим. Обычные группы и личные сообщения роем
        // не ходят.
        val manifest = if (isChannelPost) {
            signManifest(
                PostManifest.build(
                    groupId = groupId,
                    topicId = topic.id,
                    messageId = messageId,
                    authorId = me,
                    sentAtMs = now,
                    text = body,
                    parts = partRows.map { it.id to it.content },
                ),
            )?.also { storeManifest(it) }
        } else {
            null
        }
        // Кому пост уходит целиком (текст и куски). Этап 2 роя: когда умеющих
        // рой (предъявивших ключ подписи) больше K, автор шлёт полный пост
        // только первой волне - K лучших по ярусу - и всем старым телефонам
        // (они манифеста не понимают и иначе остались бы без поста). Манифест
        // уходит волне; дальше его разносят сами собравшие пост (по R соседей
        // каждый), а куски тянут полосами друг у друга. Пока умеющих рой не
        // больше K, всё как в этапе 1: полный пост и манифест - всем.
        val wave = swarmWave(groupId, me, manifest)
        val fullRecipients = wave?.full ?: allRecipients(groupId, me)
        val report = delivery.deliver(
            groupId,
            // Id сообщения уходит в конверт: у получателей строка ляжет под тем
            // же id, и закреп (Pin) найдёт её на всех телефонах. Имя - чтобы
            // получатель сразу знал, кто написал, даже без списка участников.
            GroupWire.buildMessage(
                groupId = groupId,
                topicId = topic.id,
                text = body,
                messageId = messageId,
                senderName = member.displayName,
            ),
            fullRecipients,
        )
        // Куски текста и фотографий разлетаются в фоне: до двух десятков
        // вееров подряд, и экран не должен ждать их (а viewModelScope не
        // должен их обрывать, когда человек уйдёт с экрана). Голова текста
        // уже ушла первой.
        if (partRows.isNotEmpty()) {
            val senderName = member.displayName
            backgroundScope.launch {
                for (row in partRows) {
                    runCatching {
                        delivery.deliver(
                            groupId,
                            GroupWire.buildMessage(
                                groupId = groupId,
                                topicId = topic.id,
                                text = row.content,
                                messageId = row.id,
                                senderName = senderName,
                            ),
                            fullRecipients,
                        )
                    }.onFailure { Log.w(TAG, "photo part fanout failed: ${it.message}") }
                }
                Log.i(TAG, "parts sent group=$groupId topic=${topic.id} text=${textTail.size} parts=${partRows.size}")
            }
        }
        if (manifest != null) {
            backgroundScope.launch {
                runCatching {
                    // Манифест - первой волне (на маленьком канале - всем):
                    // получившие его становятся сидами и разносят дальше.
                    delivery.deliver(groupId, GroupWire.buildPostManifest(manifest), wave?.wave ?: fullRecipients)
                }.onFailure { Log.w(TAG, "manifest fanout failed: ${it.message}") }
            }
        }
        Log.i(
            TAG,
            "group message id=$messageId group=$groupId topic=${topic.id} parts=${partRows.size} " +
                "fanout=${report.delivered}/${report.attempted} swarmRest=${wave?.rest ?: 0} " +
                "via=${delivery.name}",
        )
        return Result.success(messageId)
    }

    /** Кому шлём пост целиком ([full] = старые телефоны + первая волна [wave]); [rest] - сколько ждут роя. */
    private class SwarmWave(val full: List<String>, val wave: List<String>, val rest: Int)

    /** Все участники канала, кроме меня и забаненных. */
    private suspend fun allRecipients(groupId: String, me: String): List<String> =
        groupDao.getMembers(groupId).asSequence().filter { !it.isBanned }.map { it.nodeId }
            .filter { it != me }.toList()

    /**
     * Разбить подписчиков на первую волну и «только манифест».
     *
     * Умеющие рой - те, кто предъявил ключ подписи (`post_signers`, сам за
     * себя): старые телефоны в этот список не попадают и получают полный пост
     * по-старому. Первая волна - K лучших по ярусу из умеющих рой. Пока
     * умеющих рой не больше K, деление ничего не меняет (все в волне), и
     * канал ведёт себя как раньше; отличие появляется только на больших
     * каналах. Без манифеста (подпись недоступна) деления нет.
     */
    private suspend fun swarmWave(groupId: String, me: String, manifest: PostManifest?): SwarmWave? {
        if (manifest == null) return null
        val dao = signerDao ?: return null
        val all = allRecipients(groupId, me)
        val capable = dao.allSelfAttestedIds().toHashSet()
        val swarmers = all.filter { it in capable }
        if (swarmers.size <= FIRST_WAVE) return null
        val legacy = all.filter { it !in capable }
        val wave = orderPeers(swarmers).take(FIRST_WAVE)
        return SwarmWave(full = legacy + wave, wave = wave, rest = swarmers.size - wave.size)
    }

    // ── Правка сообщения ──────────────────────────────────────────────────────

    /**
     * Изменить текст своего сообщения (поста). Владелец группы может править
     * любое сообщение. Фотографии поста остаются прежними: меняются слова.
     * У поста канала вместе с текстом обновляется и заголовок темы.
     *
     * Длинный новый текст режется на куски, как при отправке: в конверте
     * правки едут первый кусок и пометка «продолжение в n кусках правки r»,
     * остальные куски - отдельными пакетами той же темы. Куски прежней
     * редакции у автора удаляются, у получателей - по номеру правки
     * перестают подклеиваться. Чужой пост владелец может укоротить, но не
     * удлинить сверх одного куска: его куски получатели не признали бы
     * (они принимают куски только от автора поста).
     */
    suspend fun editMessage(groupId: String, messageId: String, newText: String): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (member.isBanned) return Result.failure(SecurityException("Вы ограничены в этой группе"))
        val message = messageDao.getMessageById(messageId)
            ?: return Result.failure(IllegalStateException("Сообщение не найдено"))
        if (message.chatId != groupId) {
            return Result.failure(IllegalArgumentException("Сообщение из другой группы"))
        }
        if (message.senderId != me && group.ownerId != me) {
            return Result.failure(SecurityException("Править может только автор или владелец"))
        }
        val words = InlineImage.stripImage(newText)
        if (words.length > MAX_MESSAGE_CHARS) {
            return Result.failure(IllegalArgumentException("Сообщение длиннее $MAX_MESSAGE_CHARS символов"))
        }
        val chunks = InlineImage.splitText(words)
        val textTail = chunks.drop(1)
        if (textTail.size > InlineImage.MAX_TEXT_PARTS) {
            return Result.failure(IllegalArgumentException("Сообщение слишком длинное: сократите текст"))
        }
        if (textTail.isNotEmpty() && message.senderId != me) {
            return Result.failure(IllegalArgumentException("Такой длинный текст чужого поста может задать только его автор"))
        }
        val now = clock()
        val rev = if (textTail.isEmpty()) 0 else InlineImage.revisionAt(now)
        // В конверте едут только слова (и пометка о продолжении): фотографии у
        // получателей уже есть, а пакет с ними не прошёл бы через брокер.
        val wireText = InlineImage.headContent(chunks.first(), rev, textTail.size, 0)
        val content = InlineImage.replaceText(message.content, wireText)
        if (content.isBlank()) return Result.failure(IllegalArgumentException("Пустое сообщение"))
        val topicIdValue = message.topicId.orEmpty()
        // Куски прежней редакции больше не нужны - ни в ленте, ни в манифесте.
        val stale = messageDao.getByContentPattern(groupId, InlineImage.textPartPattern(messageId))
            .filter { InlineImage.parseTextPart(it.content)?.headId == messageId }
        val partRows = ArrayList<MessageEntity>()
        textTail.forEachIndexed { i, piece ->
            val row = MessageEntity(
                id = idFactory(),
                chatId = groupId,
                senderId = me,
                content = InlineImage.buildTextPart(messageId, rev, i + 1, textTail.size, piece),
                timestamp = now + 1 + i,
                status = "SENT",
                isFromMe = true,
                channel = "GROUP",
                recipientId = "",
                topicId = topicIdValue,
            )
            messageDao.insertMessage(row)
            partRows.add(row)
        }
        for (row in stale) messageDao.deleteById(row.id)
        applyEdit(message, content)
        broadcast(
            groupId,
            GroupWire.buildEdit(groupId, topicIdValue, messageId, wireText),
            excludeSelf = true,
        )
        if (partRows.isNotEmpty()) {
            val senderName = member.displayName
            backgroundScope.launch {
                for (row in partRows) {
                    runCatching {
                        broadcast(
                            groupId,
                            GroupWire.buildMessage(
                                groupId = groupId,
                                topicId = topicIdValue,
                                text = row.content,
                                messageId = row.id,
                                senderName = senderName,
                            ),
                            excludeSelf = true,
                        )
                    }.onFailure { Log.w(TAG, "edit text part fanout failed: ${it.message}") }
                }
                Log.i(TAG, "edit text parts sent id=$messageId parts=${partRows.size}")
            }
        }
        // Рой: у поста с манифестом после правки другой хэш текста. Автор
        // подписывает новый манифест (правка +1), иначе сиды перестали бы
        // принимать исправленный текст. Владелец, правящий чужой пост, свой
        // манифест выпустить не может (ключ автора не его) - такой пост
        // дальше раздаёт только он сам, как до роя.
        val known = manifestDao?.get(messageId)
        if (known != null && message.senderId == me) {
            // Части манифеста: новые куски текста, затем прежние части без
            // кусков старого текста (куски фото остаются).
            val staleIds = stale.map { it.id }.toHashSet()
            val kept = PostManifest.parseParts(known.parts).orEmpty().filter { it.messageId !in staleIds }
            val fresh = partRows.map { ManifestPart(it.id, PostManifest.sha256(it.content).copyOf(PostManifest.SHA16_BYTES)) }
            val manifest = signManifest(
                PostManifest(
                    groupId = groupId,
                    topicId = known.topicId,
                    messageId = messageId,
                    authorId = me,
                    sentAtMs = known.sentAtMs,
                    textSha = PostManifest.sha256(content),
                    parts = (fresh + kept).take(PostManifest.MAX_PARTS),
                    revision = known.revision + 1,
                ),
            )
            if (manifest != null) {
                storeManifest(manifest)
                backgroundScope.launch {
                    runCatching {
                        broadcast(groupId, GroupWire.buildPostManifest(manifest), excludeSelf = true)
                    }.onFailure { Log.w(TAG, "manifest re-sign fanout failed: ${it.message}") }
                }
            }
        }
        Log.i(TAG, "message edited id=$messageId group=$groupId")
        return Result.success(Unit)
    }

    /** Записать новый текст и, если это пост канала, переименовать его тему. */
    private suspend fun applyEdit(message: MessageEntity, content: String) {
        messageDao.updateContent(message.id, content)
        val topicId = message.topicId ?: return
        val topic = groupDao.getTopicById(topicId) ?: return
        val group = groupDao.getGroupById(message.chatId) ?: return
        if (!group.isChannel) return
        // Заголовок поста - первая строка текста: правится вместе с ним.
        val first = messageDao.getTopicMessages(message.chatId, topicId)
            .firstOrNull { !InlineImage.isPart(it.content) } ?: return
        if (first.id != message.id) return
        val title = postTitle(content)
        if (title != topic.name) groupDao.renameTopic(topicId, title)
    }

    // ── Досылка старых постов ─────────────────────────────────────────────────

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
        val me = myId() ?: return
        val group = groupDao.getGroupById(groupId) ?: return
        if (!group.isChannel || group.ownerId == me) return
        if (groupDao.getMember(groupId, me) == null) return
        val now = clock()
        synchronized(postsRequestedAt) {
            val last = postsRequestedAt[groupId] ?: 0L
            if (now - last < POSTS_REQUEST_GAP_MS) return
            postsRequestedAt[groupId] = now
        }
        // «Есть» - значит есть и текст поста, и все обещанные в нём фото:
        // пост с недоехавшими кусками просим целиком, лишние пакеты получатель
        // отбросит по id.
        val recent = groupDao.getTopics(groupId)
            .sortedByDescending { it.createdAtMs }
            .take(BACKFILL_POSTS)
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
        ).take(SWARM_REQUEST_POOL).shuffled()
            .take(if (missing.isEmpty()) 1 else SWARM_REQUEST_PEERS)
        // Владельцу - просьба как раньше: у него есть всё. Но на большом канале
        // (этап 2) владельца бережём: тысячи подписчиков не должны стучаться к
        // нему каждые полминуты - его спрашивают, когда соседей нет или лишь
        // изредка, когда чего-то не хватает.
        val big = group.memberCount > ROSTER_LIMIT && neighbours.isNotEmpty()
        val askOwner = !big || (missing.isNotEmpty() && (0 until OWNER_ASK_ONE_IN).random() == 0)
        if (askOwner) {
            delivery.deliver(
                groupId,
                GroupWire.buildPostsRequest(groupId, BACKFILL_POSTS, have),
                listOf(group.ownerId),
            )
        }
        neighbours.forEachIndexed { k, peer ->
            val stripeHave = have + missing.filterIndexed { i, _ -> i % neighbours.size != k }
            delivery.deliver(
                groupId,
                GroupWire.buildPostsRequest(groupId, BACKFILL_POSTS, stripeHave),
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
    private suspend fun backfillPosts(group: GroupEntity, nodeId: String, limit: Int, have: Set<String>) {
        val me = myId() ?: return
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
            if (last != null && now - last.first < BACKFILL_REPEAT_MS) last.second else emptySet()
        }
        // Последние по времени СОЗДАНИЯ поста, а не по свежести комментариев.
        // Сначала берём последние N, и только потом вычитаем уже имеющиеся:
        // иначе подписчик с полной лентой получал бы следующую порцию более
        // старых постов при каждом открытии канала.
        val topics = groupDao.getTopics(group.id)
            .sortedByDescending { it.createdAtMs }
            .take(limit.coerceIn(1, BACKFILL_POSTS))
            .filter { it.id !in have }
        val sentIds = HashSet<String>(recent)
        var sent = 0
        // От новых к старым: если связь оборвётся на середине, свежие посты
        // уже дошли. Порядок в ленте задаёт исходное время в конверте.
        for (topic in topics) {
            if (sent >= MAX_BACKFILL_PACKETS) break
            val thread = messageDao.getTopicMessages(group.id, topic.id)
            val post = thread.firstOrNull { !InlineImage.isPart(it.content) } ?: continue
            val parts = thread.filter { InlineImage.isPart(it.content) && it.senderId == post.senderId }
            val authorName = groupDao.getMember(group.id, post.senderId)?.displayName.orEmpty()
            // Манифест - первым: по нему получатель примет текст и куски от
            // меня, даже если я не владелец. Не владелец без манифеста поста
            // этот пост пропускает; пост с недоехавшими кусками тоже (сид
            // раздаёт только то, что у него целиком).
            val manifest = manifestDao?.get(post.id)?.let(::toManifest)
            if (!isOwner) {
                if (manifest == null) continue
                val partTexts = parts.map { it.content }
                if (!InlineImage.textComplete(post.id, post.content, partTexts)) continue
                if (InlineImage.assemble(partTexts).size < InlineImage.photoCount(post.content)) continue
            }
            if (manifest != null && sentIds.add(MANIFEST_SENT_PREFIX + post.id)) {
                if (sent > 0) kotlinx.coroutines.delay(BACKFILL_PACKET_GAP_MS)
                delivery.deliver(group.id, GroupWire.buildPostManifest(manifest), listOf(nodeId))
                sent++
            }
            for (row in listOf(post) + parts) {
                if (sent >= MAX_BACKFILL_PACKETS) break
                if (!sentIds.add(row.id)) continue
                // Пауза между пакетами: сотня публикаций залпом забивает
                // очередь ядра и раздражает публичный брокер.
                if (sent > 0) kotlinx.coroutines.delay(BACKFILL_PACKET_GAP_MS)
                delivery.deliver(
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

    // ── Рой постов (этап 1) ───────────────────────────────────────────────────

    /** Куски и тексты, пришедшие раньше своего манифеста: ключ - id сообщения. */
    private class PendingPiece(
        val senderId: String,
        val packet: GroupWire.Packet.Message,
        val transportId: String,
    )

    private val pendingPieces = SwarmBuffer<PendingPiece>(PENDING_PIECES, PENDING_TTL_MS, clock)

    /** Манифесты, чей ключ подписанта ещё не закреплён: ключ - id поста. */
    private val pendingManifests = SwarmBuffer<Pair<String, PostManifest>>(PENDING_MANIFESTS, PENDING_TTL_MS, clock)

    /** Сколько чужих просьб о досылке я обслуживаю как сид (не владелец). */
    private val seedRate = RateWindow(SEED_REPLIES_PER_MINUTE, 60_000L)

    private sealed class SwarmVerdict {
        class Verified(val manifest: PostManifest) : SwarmVerdict()
        object Wait : SwarmVerdict()
        object Reject : SwarmVerdict()
    }

    /**
     * Судьба чужой пересылки поста (не от владельца): есть проверенный
     * манифест и хэш сходится - принимаем; манифеста ещё нет - ждём; манифест
     * есть, но хэш не сходится - отбрасываем.
     */
    private suspend fun swarmVerdict(packet: GroupWire.Packet.Message, localId: String): SwarmVerdict {
        val dao = manifestDao ?: return SwarmVerdict.Reject
        val isPart = InlineImage.isPart(packet.text)
        // Кусок фото ищет манифест своей темы (у поста канала тема одна),
        // текст поста - манифест по своему id.
        val row = if (isPart) dao.getByTopic(packet.topicId) else dao.get(localId)
        val manifest = row?.let(::toManifest) ?: return SwarmVerdict.Wait
        if (manifest.groupId != packet.groupId || manifest.topicId != packet.topicId) return SwarmVerdict.Reject
        if (manifest.authorId != packet.authorId) return SwarmVerdict.Reject
        val ok = if (isPart) manifest.matchesPart(localId, packet.text) else manifest.matchesText(packet.text)
        if (!ok) return SwarmVerdict.Reject
        return SwarmVerdict.Verified(manifest)
    }

    /**
     * Пришёл манифест поста. Подпись сверяется с ключом, который закреплён за
     * автором (сам автор предъявил его или владелец канала заверил).
     * Неизвестный ключ - манифест ждёт `pkeys`; битая подпись - тишина.
     */
    private suspend fun handleManifest(senderId: String, manifest: PostManifest) {
        val dao = manifestDao ?: return
        val me = myId().orEmpty()
        val group = groupDao.getGroupById(manifest.groupId) ?: return
        if (!group.isChannel) return
        if (groupDao.getMember(manifest.groupId, me) == null) return
        // Кто прислал манифест - у того пост есть (сид раздаёт манифест, только
        // собрав пост): запоминаем, у кого просить полосы. Повторный манифест
        // от другого сида - ещё один адрес, где пост точно есть.
        noteSeed(manifest.topicId, senderId)
        // Уже есть такой же или более новый манифест этого поста - ничего не делаем.
        val known = dao.get(manifest.messageId)
        if (known != null && known.revision >= manifest.revision) return
        if (!manifest.verifySignature()) {
            Log.w(TAG, "manifest with bad signature dropped post=${manifest.messageId} from=$senderId")
            return
        }
        // Подписывать вправе владелец и администраторы канала. Автора, которого
        // ещё нет в моём списке участников (список в пути), не отвергаем, а
        // ждём вместе с ключом.
        val author = groupDao.getMember(manifest.groupId, manifest.authorId)
        if (manifest.authorId != group.ownerId) {
            if (author == null) {
                pendingManifests.put(manifest.messageId, senderId to manifest)
                Log.i(TAG, "manifest buffered until roster post=${manifest.messageId}")
                return
            }
            if (!GroupRole.isAdminOrOwner(author.role)) {
                Log.w(TAG, "manifest from non-admin author dropped post=${manifest.messageId}")
                return
            }
        }
        // Автор прислал манифест сам: его ключ закрепляем за ним (подлинность
        // отправителя гарантирует ядро). Иначе ключ должен быть уже закреплён.
        if (senderId == manifest.authorId) {
            pinSigner(manifest.authorId, manifest.authorId, manifest.signerPublicKey)
        } else if (!signerKnown(group, manifest.authorId, manifest.signerPublicKey)) {
            pendingManifests.put(manifest.messageId, senderId to manifest)
            // Ключи знают владелец и сам автор: спрашиваем обоих, но не чаще
            // раза за запуск на пару «канал|автор».
            if (pkeysAsked.add(manifest.groupId + '|' + manifest.authorId)) {
                delivery.deliver(
                    manifest.groupId,
                    GroupWire.buildPostKeysRequest(manifest.groupId),
                    listOf(group.ownerId, manifest.authorId).distinct(),
                )
            }
            Log.i(TAG, "manifest buffered until signer key post=${manifest.messageId}")
            return
        }
        storeManifest(manifest)
        Log.i(TAG, "manifest accepted post=${manifest.messageId} rev=${manifest.revision} group=${group.id} from=$senderId")
        // Более новая правка текста, чем у нас: сам текст придёт (или уже
        // пришёл) конвертом edit; манифест лишь перестаёт отвергать его хэш.
        releasePendingPieces(manifest)
        // Владелец должен знать, что этот телефон умеет рой (иначе он шлёт мне
        // пост целиком, как старому): предъявляем ему свой ключ, раз за запуск.
        announceKey(group)
        // Этап 2: манифест есть, а поста (или его кусков) нет - тянем куски
        // полосами у сидов. Всё собрали - сами раздаём манифест дальше.
        afterManifestAccepted(group, manifest, senderId)
    }

    // ── Рой, этап 2: полосы кусков и раздача манифеста ───────────────────────

    /** Посты (topicId), которые сейчас тянем полосами. */
    private val fetching = HashSet<String>()

    /** Кто прислал манифест или куски поста (по темам): у них пост есть - у них и просим полосы. */
    private val topicSeeds = HashMap<String, LinkedHashSet<String>>()

    private fun noteSeed(topicId: String, nodeId: String) {
        if (nodeId.isBlank() || nodeId == cachedNodeId) return
        synchronized(topicSeeds) {
            if (topicSeeds.size >= MAX_TRACKED_TOPICS && topicId !in topicSeeds) topicSeeds.clear()
            topicSeeds.getOrPut(topicId) { LinkedHashSet<String>() }.add(nodeId)
        }
    }

    private fun seedsOf(topicId: String): List<String> =
        synchronized(topicSeeds) { topicSeeds[topicId]?.toList().orEmpty() }

    /** Одновременно тянем не больше стольких постов: лента наполняется, телефон не захлёбывается. */
    private val fetchSlots = Semaphore(FETCH_PARALLEL)

    /** Посты, чей манифест мы уже раздавали дальше (по разу на запуск). */
    private val manifestRelayed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Каналы, владельцу которых в этом запуске уже предъявлен мой ключ. */
    private val keyAnnounced = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Сколько кусков в минуту я отдаю по `pwant` (защита телефона, §4). */
    private val pieceRate = RateWindow(PIECES_PER_MINUTE, 60_000L)

    /** Сколько полос отдаю одновременно. */
    private val serveSlots = Semaphore(SERVE_PARALLEL)

    /**
     * Предъявить владельцу канала мой ключ подписи: по нему владелец узнаёт,
     * что телефон умеет рой, и при публикации шлёт ему манифест, а не весь
     * пост. Раз за запуск на канал; владелец и старые телефоны (без ключа)
     * ничего не шлют.
     */
    private suspend fun announceKey(group: GroupEntity) {
        val me = myId() ?: return
        if (!group.isChannel || group.ownerId == me) return
        if (!keyAnnounced.add(group.id)) return
        val mine = myPostKey() ?: return
        // Владельцу и администраторам: посты пишут они, и делить подписчиков
        // на первую волну и остальных - тоже им.
        val targets = (listOf(group.ownerId) + groupDao.getAdmins(group.id).map { it.nodeId })
            .filter { it != me }.distinct()
        delivery.deliver(group.id, GroupWire.buildPostKeys(group.id, listOf(me to mine)), targets)
    }

    /** Все ли части поста (текст и куски) уже в базе. */
    private suspend fun isManifestComplete(group: GroupEntity, manifest: PostManifest): Boolean =
        pieceIdsHave(group.id, manifest).size >= 1 + manifest.parts.size

    /** Манифест принят: пост либо уже есть (тогда, может быть, раздаём дальше), либо тянем куски. */
    private suspend fun afterManifestAccepted(group: GroupEntity, manifest: PostManifest, from: String) {
        if (isManifestComplete(group, manifest)) {
            maybeRelay(group, manifest, from)
            return
        }
        scheduleFetch(group, manifest, from)
    }

    /**
     * Раздавать ли манифест дальше. От автора манифест приходит либо всем
     * (маленький канал - повтор никому не нужен), либо первой волне большого
     * канала - тогда волна и есть начало эстафеты. От сида - всегда дальше:
     * так эстафета и идёт.
     */
    private suspend fun maybeRelay(group: GroupEntity, manifest: PostManifest, from: String) {
        val members = maxOf(group.memberCount, groupDao.countMembers(group.id))
        if (from != manifest.authorId || members > FIRST_WAVE) onPostComplete(group, manifest)
    }

    /**
     * Запустить сбор поста по манифесту: несколько раундов `pwant` к разным
     * сидам, пока пост не соберётся или раунды не кончатся. Между раундами
     * пауза - куски идут через брокер и приходят не мгновенно. Один сбор на
     * пост за раз; параллельно - не больше [FETCH_PARALLEL] постов.
     */
    private fun scheduleFetch(group: GroupEntity, manifest: PostManifest, from: String) {
        synchronized(fetching) {
            if (!fetching.add(manifest.topicId)) return
        }
        backgroundScope.launch {
            try {
                fetchSlots.withPermit { fetchPieces(group, manifest, from) }
            } catch (e: Exception) {
                Log.w(TAG, "piece fetch failed: ${e.message}")
            } finally {
                synchronized(fetching) { fetching.remove(manifest.topicId) }
            }
        }
    }

    private suspend fun fetchPieces(group: GroupEntity, manifest: PostManifest, from: String) {
        val me = myId() ?: return
        // Свежий пост, а манифест прислал сам автор: значит, автор шлёт мне и
        // сам пост (я в первой волне или канал маленький), куски уже в пути -
        // терпеливо ждём их, а не дёргаем соседей, у которых их тоже ещё нет.
        val patient = from == manifest.authorId && clock() - manifest.sentAtMs < FRESH_POST_MS
        if (patient) {
            repeat(AUTHOR_WAIT_POLLS) {
                kotlinx.coroutines.delay(AUTHOR_WAIT_POLL_MS)
                if (isManifestComplete(group, manifest)) {
                    maybeRelay(group, manifest, from)
                    return
                }
            }
        }
        repeat(FETCH_ROUNDS) { round ->
            // Небольшая случайная пауза перед первым раундом: пост от сида
            // мог идти следом за манифестом и просто ещё не доехал.
            kotlinx.coroutines.delay(
                when {
                    round > 0 -> FETCH_ROUND_MS
                    patient -> 0L
                    else -> FETCH_FIRST_DELAY_MS + (0L..1_500L).random()
                },
            )
            val have = pieceIdsHave(group.id, manifest)
            if (have.size >= 1 + manifest.parts.size) {
                maybeRelay(group, manifest, from)
                return
            }
            // Сиды: сначала те, у кого пост точно есть (прислали манифест или
            // куски), в случайном порядке; полос - по числу таких сидов, но не
            // меньше двух: недостающие места занимают лучшие соседи, у которых
            // пост, возможно, уже есть. Состав каждый раунд другой, чтобы
            // мёртвый сид не стопорил. Автора и владельца бережём: к ним идём
            // только в последнем раунде или когда больше спросить некого.
            val last = round == FETCH_ROUNDS - 1
            val known = seedsOf(manifest.topicId)
                .filter { it != me && it != manifest.authorId && it != group.ownerId }
                .shuffled()
            val stripes = known.size.coerceIn(MIN_STRIPES, GroupWire.MAX_STRIPES)
            val pool = LinkedHashSet<String>()
            // В последнем раунде автор - первая полоса: у него пост есть наверняка.
            if (last && manifest.authorId != me) pool.add(manifest.authorId)
            pool.addAll(known)
            if (pool.size < stripes) {
                pool.addAll(
                    orderPeers(
                        allRecipients(group.id, me).filter {
                            it !in pool && it != manifest.authorId && it != group.ownerId
                        },
                    ).take(SWARM_REQUEST_POOL).shuffled(),
                )
            }
            if (pool.isEmpty()) {
                pool.add(manifest.authorId)
                pool.add(group.ownerId)
            }
            pool.remove(me)
            val seeds = pool.toList().take(stripes)
            if (seeds.isEmpty()) return
            seeds.forEachIndexed { k, seed ->
                delivery.deliver(
                    group.id,
                    GroupWire.buildPieceWant(group.id, manifest.topicId, k, seeds.size, have),
                    listOf(seed),
                )
            }
            Log.i(TAG, "pwant sent topic=${manifest.topicId} round=$round seeds=${seeds.size} have=${have.size}")
        }
        // Раунды кончились: если пост всё же собрался - раздаём; если нет,
        // остаток доберёт обычный preq при открытии канала.
        if (isManifestComplete(group, manifest)) maybeRelay(group, manifest, from)
    }

    /** Id сообщений поста (текст и куски), которые уже лежат в базе. */
    private suspend fun pieceIdsHave(groupId: String, manifest: PostManifest): List<String> {
        val ids = ArrayList<String>()
        if (messageDao.messageExists(manifest.messageId)) ids.add(manifest.messageId)
        for (part in manifest.parts) if (messageDao.messageExists(part.messageId)) ids.add(part.messageId)
        return ids
    }

    /**
     * Пост собран целиком: раздать его манифест R соседям (не автору, не
     * владельцу). Один раз на пост за запуск и только для свежих постов:
     * старый пост, дособранный через `preq`, все давно получили. Получатель
     * без поста попросит куски полосами - в том числе у меня.
     */
    private suspend fun onPostComplete(group: GroupEntity, manifest: PostManifest) {
        if (clock() - manifest.sentAtMs > FRESH_POST_MS) return
        if (!manifestRelayed.add(manifest.messageId)) return
        val me = myId() ?: return
        val candidates = allRecipients(group.id, me).filter { it != manifest.authorId && it != group.ownerId }
        if (candidates.isEmpty()) return
        // Этап 3: сначала те, кто точно умеет рой - соседи, предъявившие свой
        // ключ подписи (прислали `pkeys` или манифест). Старому телефону
        // манифест бесполезен (он его молча отбросит), и место в эстафете
        // лучше отдать тому, кто понесёт её дальше. Среди умеющих - лучшим по
        // ярусу чуть больше шансов: они надёжнее раздают. Остаток добираем
        // случайными участниками, о которых ничего не известно.
        val capable = signerDao?.allSelfAttestedIds()?.toHashSet().orEmpty()
        val known = candidates.filter { it in capable }
        val unknown = candidates.filter { it !in capable }
        val top = orderPeers(known).take(RELAY_FANOUT * 2).shuffled().take(RELAY_FANOUT / 2)
        val moreKnown = known.filter { it !in top }.shuffled().take(RELAY_FANOUT - top.size)
        val rest = unknown.shuffled().take(RELAY_FANOUT - top.size - moreKnown.size)
        val targets = top + moreKnown + rest
        kotlinx.coroutines.delay((1_000L..5_000L).random())
        delivery.deliver(group.id, GroupWire.buildPostManifest(manifest), targets)
        Log.i(TAG, "manifest relayed post=${manifest.messageId} to=${targets.size} known=${top.size + moreKnown.size}")
    }

    /**
     * Просьба прислать полосу кусков: отвечаю, если пост (хотя бы текст) у
     * меня есть и просящий - участник. Полоса k из m: номер 0 - текст, дальше
     * куски в порядке манифеста; шлю те, чей номер ≡ k (mod m), которых у
     * просящего нет и которые есть у меня (как в торренте, раздаю и неполное).
     */
    private suspend fun handlePieceWant(senderId: String, packet: GroupWire.Packet.PieceWant) {
        val dao = manifestDao ?: return
        val me = myId() ?: return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (!group.isChannel) return
        if (groupDao.getMember(packet.groupId, me) == null) return
        // Закрытый канал - только известным участникам; открытый - любому
        // (на большом канале список участников до всех не доходит, а вступить
        // в открытый канал и так может каждый). Забаненным - никогда.
        val requester = groupDao.getMember(packet.groupId, senderId)
        if (requester?.isBanned == true) return
        if (requester == null && !group.isPublic) return
        val manifest = dao.getByTopic(packet.topicId)?.let(::toManifest) ?: return
        if (!messageDao.messageExists(manifest.messageId)) return
        // Предел отдачи (§4): не больше нескольких полос разом и не больше
        // стольких-то кусков в минуту. Лишние просьбы молча отбрасываем -
        // просящий через раунд спросит другого сида.
        if (!serveSlots.tryAcquire()) {
            Log.i(TAG, "pwant throttled topic=${packet.topicId} from=$senderId")
            return
        }
        backgroundScope.launch {
            try {
                val thread = messageDao.getTopicMessages(group.id, packet.topicId)
                val byId = thread.associateBy { it.id }
                val post = byId[manifest.messageId] ?: return@launch
                val ordered = listOf(manifest.messageId) + manifest.parts.map { it.messageId }
                val wanted = GroupWire.stripe(ordered, packet.stripe, packet.stripes, packet.have.toHashSet())
                val authorName = groupDao.getMember(group.id, manifest.authorId)?.displayName.orEmpty()
                var sent = 0
                // Манифест не шлём: просящий просит по нему - он у него есть.
                for (id in wanted) {
                    val row = byId[id] ?: continue
                    if (!pieceRate.allow(clock())) break
                    if (sent > 0) kotlinx.coroutines.delay(BACKFILL_PACKET_GAP_MS)
                    delivery.deliver(
                        group.id,
                        GroupWire.buildMessage(
                            groupId = group.id,
                            topicId = packet.topicId,
                            text = row.content,
                            messageId = row.id,
                            senderName = authorName,
                            authorId = manifest.authorId,
                            sentAtMs = if (row.id == post.id) manifest.sentAtMs else row.timestamp,
                        ),
                        listOf(senderId),
                    )
                    sent++
                }
                Log.i(TAG, "pwant served topic=${packet.topicId} stripe=${packet.stripe}/${packet.stripes} to=$senderId pieces=$sent")
            } catch (e: Exception) {
                Log.w(TAG, "pwant serve failed: ${e.message}")
            } finally {
                serveSlots.release()
            }
        }
    }

    /** Заверенные ключи: свой - от самого узла, чужие - только от владельца канала. */
    private suspend fun handlePostKeys(senderId: String, packet: GroupWire.Packet.PostKeys) {
        signerDao ?: return
        val me = myId().orEmpty()
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (groupDao.getMember(packet.groupId, me) == null) return
        val fromOwner = senderId == group.ownerId
        // Чужим ключи не закрепляем: таблица не должна расти от случайных
        // пакетов. Исключение - открытый канал: там подписчик, которого нет
        // в моём (неполном на большом канале) списке, предъявляет свой ключ,
        // чтобы автор считал его умеющим рой.
        if (!fromOwner && groupDao.getMember(packet.groupId, senderId) == null && !group.isPublic) return
        for ((nodeId, key) in packet.keys) {
            if (nodeId == senderId) {
                pinSigner(nodeId, nodeId, key)
            } else if (fromOwner) {
                pinSigner(nodeId, group.ownerId, key)
            }
        }
        // Владелец прислал ключи (обычно при приёме) - отвечаем своим: так он
        // узнаёт, что этот телефон умеет рой.
        if (fromOwner) announceKey(group)
        // Ключ мог быть последним, чего ждали отложенные манифесты.
        val ready = pendingManifests.take { (_, m) -> m.groupId == packet.groupId }
        for ((from, manifest) in ready) handleManifest(from, manifest)
    }

    /** Разослать мой ключ подписи и ключи администраторов: владелец - новичку и всем по запросу. */
    private suspend fun sendPostKeys(group: GroupEntity, nodeId: String) {
        val dao = signerDao ?: return
        val me = myId() ?: return
        val mine = myPostKey() ?: return
        val keys = ArrayList<Pair<String, ByteArray>>()
        keys.add(me to mine)
        if (group.ownerId == me) {
            val adminIds = groupDao.getMembers(group.id)
                .filter { it.nodeId != me && GroupRole.isAdminOrOwner(it.role) }
                .map { it.nodeId }
            if (adminIds.isNotEmpty()) {
                dao.selfAttested(adminIds).forEach { row ->
                    PostManifest.unb64(row.publicKey)?.let { keys.add(row.nodeId to it) }
                }
            }
        }
        delivery.deliver(group.id, GroupWire.buildPostKeys(group.id, keys), listOf(nodeId))
    }

    /** Группы|узлы, чей ключ мы уже спрашивали у владельца в этом запуске. */
    private val pkeysAsked = java.util.Collections.synchronizedSet(HashSet<String>())

    private suspend fun pinSigner(nodeId: String, attestedBy: String, key: ByteArray) {
        val dao = signerDao ?: return
        if (key.size != Ed25519.PUBLIC_KEY_BYTES) return
        val encoded = PostManifest.b64(key)
        val current = dao.get(nodeId, attestedBy)
        if (current?.publicKey == encoded) return
        dao.put(PostSignerEntity(nodeId, attestedBy, encoded, clock()))
    }

    /** Закреплён ли за [nodeId] именно этот ключ - им самим или владельцем канала. */
    private suspend fun signerKnown(group: GroupEntity, nodeId: String, key: ByteArray): Boolean {
        val dao = signerDao ?: return false
        val encoded = PostManifest.b64(key)
        if (dao.get(nodeId, nodeId)?.publicKey == encoded) return true
        return dao.get(nodeId, group.ownerId)?.publicKey == encoded
    }

    private suspend fun storeManifest(manifest: PostManifest) {
        val dao = manifestDao ?: return
        dao.put(
            PostManifestEntity(
                messageId = manifest.messageId,
                groupId = manifest.groupId,
                topicId = manifest.topicId,
                authorId = manifest.authorId,
                sentAtMs = manifest.sentAtMs,
                textSha = PostManifest.hex(manifest.textSha),
                parts = manifest.partsWire(),
                revision = manifest.revision,
                signerKey = PostManifest.b64(manifest.signerPublicKey),
                signature = PostManifest.b64(manifest.signature),
                receivedAtMs = clock(),
            ),
        )
    }

    private fun toManifest(row: PostManifestEntity): PostManifest? {
        val textSha = PostManifest.unhex(row.textSha) ?: return null
        val parts = PostManifest.parseParts(row.parts) ?: return null
        val key = PostManifest.unb64(row.signerKey) ?: return null
        val sig = PostManifest.unb64(row.signature) ?: return null
        return PostManifest(
            groupId = row.groupId,
            topicId = row.topicId,
            messageId = row.messageId,
            authorId = row.authorId,
            sentAtMs = row.sentAtMs,
            textSha = textSha,
            parts = parts,
            revision = row.revision,
            signerPublicKey = key,
            signature = sig,
        )
    }

    /** Манифест доехал: прогоняем куски, что ждали его, обычным путём. */
    private suspend fun releasePendingPieces(manifest: PostManifest) {
        val waiting = pendingPieces.take { it.packet.topicId == manifest.topicId }
        for (piece in waiting) {
            runCatching { handleIncoming(piece.senderId, piece.packet, piece.transportId) }
                .onFailure { Log.w(TAG, "buffered piece failed: ${it.message}") }
        }
        if (waiting.isNotEmpty()) Log.i(TAG, "released ${waiting.size} buffered pieces topic=${manifest.topicId}")
    }

    /** Скользящее окно: не больше [limit] событий за [windowMs]. */
    private class RateWindow(private val limit: Int, private val windowMs: Long) {
        private val stamps = ArrayDeque<Long>()

        @Synchronized
        fun allow(now: Long): Boolean {
            while (stamps.isNotEmpty() && now - stamps.first() > windowMs) stamps.removeFirst()
            if (stamps.size >= limit) return false
            stamps.addLast(now)
            return true
        }
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

    /** Заголовок поста канала - первая строка текста без служебных строк. */
    private fun postTitle(content: String): String =
        InlineImage.stripImage(content).lineSequence().firstOrNull().orEmpty().trim()
            .take(POST_TITLE_CHARS).ifBlank { "Пост" }

    /** Тема по умолчанию: если тем нет или id пустой — пишем в General. */
    private suspend fun resolveTopic(group: GroupEntity, topicId: String): GroupTopicEntity? {
        if (!group.topicsEnabled) {
            return groupDao.getGeneralTopic(group.id) ?: groupDao.getTopics(group.id).firstOrNull()
        }
        if (topicId.isNotBlank()) return groupDao.getTopicById(topicId)
        return groupDao.getGeneralTopic(group.id)
    }

    private suspend fun registerOutgoing(
        groupId: String,
        topic: GroupTopicEntity,
        body: String,
        senderId: String,
        now: Long,
    ) {
        groupDao.registerTopicMessage(topic.id, preview(body), now)
        groupDao.updateGroupLastMessage(groupId, preview(body), now)
        registerStats(groupId, topic.id, senderId, now)
    }

    // ── Приём групповых событий ───────────────────────────────────────────────

    suspend fun handleIncoming(senderId: String, packet: GroupWire.Packet, messageId: String) {
        val me = myId().orEmpty()
        when (packet) {
            is GroupWire.Packet.Message -> {
                val group = groupDao.getGroupById(packet.groupId) ?: return
                val member = groupDao.getMember(packet.groupId, me) ?: return
                if (member.isBanned) return
                // Сообщение хранится под id отправителя, если он пришёл в
                // конверте: иначе закреп и ответы не найдут его на этом
                // телефоне. Для старых конвертов остаётся транспортный id.
                val localId = packet.messageId.ifBlank { messageId }
                if (messageDao.messageExists(localId)) return
                val now = clock()
                // Досылка старого поста: отправитель пересылает чужой пост, автор
                // и время указаны в конверте. Верим владельцу канала - и любому
                // участнику, если у нас есть проверенный манифест этого поста и
                // текст сходится с хэшем (рой). Кусок или пост, чей манифест ещё
                // не доехал, ждёт в буфере, а не отбрасывается.
                var relayed = packet.authorId.isNotBlank() && senderId == group.ownerId
                var verifiedBy: PostManifest? = null
                if (packet.authorId.isNotBlank() && !relayed && group.isChannel && manifestDao != null) {
                    when (val verdict = swarmVerdict(packet, localId)) {
                        is SwarmVerdict.Verified -> {
                            relayed = true
                            verifiedBy = verdict.manifest
                            noteSeed(packet.topicId, senderId)
                        }
                        SwarmVerdict.Wait -> {
                            pendingPieces.put(localId, PendingPiece(senderId, packet, messageId))
                            Log.i(TAG, "piece buffered until manifest id=$localId group=${group.id}")
                            return
                        }
                        SwarmVerdict.Reject -> {
                            Log.w(TAG, "relayed piece rejected id=$localId group=${group.id} from=$senderId")
                            return
                        }
                    }
                }
                val authorId = if (relayed) packet.authorId else senderId
                val isMine = authorId == me
                val isPart = InlineImage.isPart(packet.text)
                // Время поста: из подписанного манифеста, если он есть, - сид мог
                // получить пост «вживую» и хранить его под своим временем приёма.
                val sentAt = when {
                    verifiedBy != null && !isPart && verifiedBy.sentAtMs in 1L..now -> verifiedBy.sentAtMs
                    relayed && packet.sentAtMs in 1L..now -> packet.sentAtMs
                    else -> now
                }
                messageDao.insertMessage(
                    MessageEntity(
                        id = localId,
                        chatId = packet.groupId,
                        senderId = authorId,
                        content = packet.text,
                        timestamp = sentAt,
                        status = if (isMine) "SENT" else "RECEIVED",
                        isFromMe = isMine,
                        channel = "GROUP",
                        recipientId = if (isMine) "" else me,
                        topicId = packet.topicId,
                    )
                )
                rememberSender(packet.groupId, authorId, packet.senderName, now)
                // Кусок фотографии - не сообщение: он не двигает счётчики
                // непрочитанного, превью и статистику. Досланные старые посты
                // тоже не считаются новыми: человек их не пропускал.
                if (isPart || relayed) {
                    // Досланный пост темы, которой у нас ещё нет (список тем
                    // разминулся в пути или пришёл раньше вступления): заводим
                    // тему сами, иначе пост не попадёт в ленту. Только от
                    // владельца или по проверенному манифесту - relayed это
                    // уже гарантирует.
                    if (relayed && !isPart && groupDao.getTopicById(packet.topicId) == null) {
                        groupDao.insertTopic(
                            GroupTopicEntity(
                                id = packet.topicId,
                                groupId = packet.groupId,
                                name = postTitle(packet.text),
                                ownerId = authorId,
                                ownerName = packet.senderName,
                                createdAtMs = sentAt,
                            )
                        )
                    }
                    // Свежий пост, пришедший через рой (не от автора, а от
                    // сида), для читателя новый: считаем его непрочитанным, как
                    // обычное сообщение. Старая досылка (пост давний) - нет:
                    // человек её не пропускал.
                    val fresh = relayed && !isPart && now - sentAt < FRESH_POST_MS
                    if (!fresh) {
                        Log.i(TAG, "group ${if (isPart) "photo part" else "backfilled post"} in group=${group.id} topic=${packet.topicId}")
                        return
                    }
                    // Свежий пост целиком собран: я - сид, эстафету манифеста
                    // веду дальше (см. maybeRelay); пост без фото собран сразу.
                    val complete = verifiedBy
                    if (complete != null && !InlineImage.expectsParts(packet.text)) {
                        maybeRelay(group, complete, senderId)
                    }
                }
                val topic = groupDao.getTopicById(packet.topicId)
                if (topic != null) {
                    groupDao.registerTopicMessage(topic.id, preview(packet.text), now)
                    groupDao.incrementTopicUnread(topic.id)
                }
                groupDao.updateGroupLastMessage(packet.groupId, preview(packet.text), now)
                groupDao.incrementGroupUnread(packet.groupId)
                registerStats(packet.groupId, packet.topicId, authorId, now)
                Log.i(TAG, "group message in group=${group.id} topic=${packet.topicId} from=$senderId")
            }

            is GroupWire.Packet.Edit -> {
                val group = groupDao.getGroupById(packet.groupId) ?: return
                if (groupDao.getMember(packet.groupId, me) == null) return
                val message = messageDao.getMessageById(packet.messageId) ?: return
                if (message.chatId != packet.groupId) return
                // Править вправе автор и владелец группы - остальное отбрасываем.
                if (senderId != message.senderId && senderId != group.ownerId) return
                // Приходят только слова (и пометка о продолжении длинного
                // текста); свои служебные строки (фото) оставляем.
                val content = InlineImage.replaceText(message.content, packet.text)
                if (content.isBlank() || content.length > MAX_MESSAGE_CHARS) return
                applyEdit(message, content)
                // Куски текста прежних редакций больше не подклеиваются - убираем.
                val keepRev = InlineImage.textTail(content)?.rev
                for (row in messageDao.getByContentPattern(group.id, InlineImage.textPartPattern(packet.messageId))) {
                    val part = InlineImage.parseTextPart(row.content) ?: continue
                    if (part.headId == packet.messageId && part.rev != keepRev) messageDao.deleteById(row.id)
                }
                Log.i(TAG, "message edit applied id=${packet.messageId} group=${group.id} from=$senderId")
            }

            is GroupWire.Packet.PostsRequest -> {
                val group = groupDao.getGroupById(packet.groupId) ?: return
                if (!group.isChannel) return
                if (groupDao.getMember(packet.groupId, me) == null) return
                // Отвечает владелец - и любой участник с манифестами (рой).
                // Закрытый канал - только известным участникам; открытый -
                // любому (на большом канале список участников до всех не
                // доходит). Забаненным - никогда. Сид (не владелец) отвечает
                // не чаще нескольких раз в минуту.
                val requester = groupDao.getMember(packet.groupId, senderId)
                if (requester?.isBanned == true) return
                if (requester == null && (!group.isPublic || group.ownerId == me)) return
                if (group.ownerId != me && !seedRate.allow(clock())) {
                    Log.i(TAG, "posts request throttled group=${group.id} from=$senderId")
                    return
                }
                // Веер по сети - в фоне: приём пакетов не должен ждать.
                backgroundScope.launch {
                    runCatching { backfillPosts(group, senderId, packet.limit, packet.have.toSet()) }
                        .onFailure { Log.w(TAG, "posts backfill failed: ${it.message}") }
                }
            }

            is GroupWire.Packet.PostManifest -> handleManifest(senderId, packet.manifest)

            is GroupWire.Packet.PostKeys -> handlePostKeys(senderId, packet)

            is GroupWire.Packet.PieceWant -> handlePieceWant(senderId, packet)

            // Счётчики через владельца (этап 3): сводку считает и применяет
            // PostCounterRepository; сеть - в фоне, приём пакетов не ждёт.
            is GroupWire.Packet.CountersRequest -> backgroundScope.launch {
                runCatching { onCountersRequest(senderId, packet) }
                    .onFailure { Log.w(TAG, "counters serve failed: ${it.message}") }
            }

            is GroupWire.Packet.Counters -> backgroundScope.launch {
                runCatching { onCounters(senderId, packet) }
                    .onFailure { Log.w(TAG, "counters apply failed: ${it.message}") }
            }

            is GroupWire.Packet.Peers -> {
                // Выборку соседей и число подписчиков принимаем только от
                // владельца: она заменяет полный список на большом канале.
                val group = groupDao.getGroupById(packet.groupId) ?: return
                if (group.ownerId != senderId) return
                if (groupDao.getMember(packet.groupId, me) == null) return
                val now = clock()
                for (nodeId in packet.nodeIds) {
                    if (nodeId == me || groupDao.getMember(packet.groupId, nodeId) != null) continue
                    groupDao.insertMember(
                        GroupMemberEntity(
                            groupId = packet.groupId,
                            nodeId = nodeId,
                            displayName = "",
                            role = GroupRole.MEMBER,
                            joinedAtMs = now,
                        )
                    )
                }
                // Число подписчиков - от владельца: локальная таблица на большом
                // канале заведомо неполная.
                if (packet.memberCount > 0) groupDao.updateMemberCount(packet.groupId, packet.memberCount)
                Log.i(TAG, "peers applied group=${group.id} count=${packet.memberCount} sample=${packet.nodeIds.size}")
            }

            is GroupWire.Packet.PostKeysRequest -> {
                // Отвечают владелец и администраторы канала: у них есть что
                // предъявить. Обычный участник постов не подписывает.
                val group = groupDao.getGroupById(packet.groupId) ?: return
                if (!group.isChannel) return
                val member = groupDao.getMember(packet.groupId, me) ?: return
                if (!GroupRole.isAdminOrOwner(member.role) && group.ownerId != me) return
                val requester = groupDao.getMember(packet.groupId, senderId) ?: return
                if (requester.isBanned) return
                sendPostKeys(group, senderId)
            }

            is GroupWire.Packet.TopicCreated -> {
                if (groupDao.getMember(packet.groupId, me) == null) return
                if (groupDao.getTopicById(packet.topicId) != null) return
                groupDao.insertTopic(
                    GroupTopicEntity(
                        id = packet.topicId,
                        groupId = packet.groupId,
                        name = packet.name,
                        ownerId = senderId,
                        ownerName = groupDao.getMember(packet.groupId, senderId)?.displayName.orEmpty(),
                        iconEmoji = packet.iconEmoji,
                        createdAtMs = clock(),
                    )
                )
            }

            is GroupWire.Packet.JoinRequest -> {
                val mine = groupDao.getMember(packet.groupId, me) ?: return
                if (!GroupPermissions.canInvite(mine.role, mine.permissions, 0L)) return
                val existing = groupDao.getJoinRequest(packet.groupId, senderId)
                if (existing != null && existing.status == "APPROVED") return
                val group = groupDao.getGroupById(packet.groupId) ?: return
                val banned = groupDao.getMember(packet.groupId, senderId)?.isBanned == true
                if (banned) {
                    Log.i(TAG, "join request dropped: node is banned, node=$senderId")
                    return
                }

                // Ссылку проверяем по СВОЕЙ базе: только у владельца есть её запись.
                val invite = if (packet.slug.isNotBlank()) {
                    groupDao.getInviteBySlug(packet.slug)
                } else {
                    null
                }
                val admitAtOnce = invite != null &&
                    invite.groupId == packet.groupId &&
                    !invite.revoked &&
                    !invite.requestApproval &&
                    (invite.expiresAtMs == null || invite.expiresAtMs > clock()) &&
                    (invite.maxUses <= 0 || invite.useCount < invite.maxUses)

                if (admitAtOnce) {
                    admitMember(group, senderId, packet.displayName, packet.slug)
                } else {
                    groupDao.insertJoinRequest(
                        GroupJoinRequestEntity(
                            groupId = packet.groupId,
                            nodeId = senderId,
                            displayName = packet.displayName,
                            note = packet.note,
                            requestedAtMs = clock(),
                        )
                    )
                    Log.i(TAG, "join request queued group=${packet.groupId} node=$senderId")
                }
            }

            is GroupWire.Packet.JoinDecision -> {
                if (packet.nodeId != me) return
                if (packet.approved) {
                    val group = groupDao.getGroupById(packet.groupId) ?: return
                    if (groupDao.getMember(packet.groupId, me) == null) {
                        groupDao.insertMember(
                            GroupMemberEntity(
                                groupId = packet.groupId,
                                nodeId = me,
                                displayName = myDisplayName(),
                                role = GroupRole.MEMBER,
                                joinedAtMs = clock(),
                            )
                        )
                        groupDao.clearLeft(packet.groupId)
                        groupDao.bumpMemberCount(packet.groupId)
                        Log.i(TAG, "join approved into group=${group.id}")
                    }
                } else {
                    groupDao.updateJoinRequestStatus(
                        packet.groupId, me, "REJECTED", clock(), senderId,
                    )
                }
            }

            is GroupWire.Packet.Pin -> {
                if (groupDao.getMember(packet.groupId, me) == null) return
                messageDao.updatePinned(
                    packet.messageId,
                    packet.pinned,
                    if (packet.pinned) clock() else null,
                    if (packet.pinned) senderId else null,
                )
            }

            is GroupWire.Packet.RosterRequest -> {
                // Отвечают владелец и администраторы: у них состав полный.
                // Обычный участник не отвечает, чтобы на один запрос не
                // приходило десять одинаковых списков.
                val member = groupDao.getMember(packet.groupId, me) ?: return
                if (!GroupRole.isAdminOrOwner(member.role)) return
                publishRoster(packet.groupId, newcomer = senderId)
                Log.i(TAG, "roster resent group=${packet.groupId} to=$senderId")
            }

            is GroupWire.Packet.GroupDeleted -> {
                // Стираем копию, только если пакет прислал владелец группы:
                // иначе любой участник мог бы удалять чужие переписки.
                val group = groupDao.getGroupById(packet.groupId) ?: return
                if (group.ownerId != senderId) return
                deleteGroupLocally(packet.groupId)
            }

            is GroupWire.Packet.GroupInfo -> {
                // Карточку принимаем только от владельца группы.
                if (packet.ownerId != senderId) return
                val current = groupDao.getGroupById(packet.groupId)
                if (current == null) {
                    // Группы ещё нет - вставляем. Конфликта нет, каскада нет.
                    groupDao.insertGroup(
                        GroupEntity(
                            id = packet.groupId,
                            title = packet.title.ifBlank { "Группа" },
                            about = packet.about,
                            ownerId = packet.ownerId,
                            isPublic = packet.isPublic,
                            topicsEnabled = packet.topicsEnabled,
                            isChannel = packet.isChannel,
                            createdAtMs = clock(),
                            memberCount = 1,
                            inviteSlug = packet.inviteSlug,
                        )
                    )
                } else {
                    // Группа уже есть - ТОЛЬКО UPDATE: перезапись строки стёрла бы
                    // участников и темы каскадом по внешнему ключу.
                    groupDao.updateGroupFromOwner(
                        groupId = packet.groupId,
                        title = packet.title.ifBlank { current.title },
                        about = packet.about,
                        inviteSlug = packet.inviteSlug,
                        isPublic = packet.isPublic,
                        topicsEnabled = packet.topicsEnabled,
                    )
                }
                if (current == null && groupDao.getMember(packet.groupId, me) == null) {
                    groupDao.insertMember(
                        GroupMemberEntity(
                            groupId = packet.groupId,
                            nodeId = me,
                            displayName = myDisplayName(),
                            role = GroupRole.MEMBER,
                            joinedAtMs = clock(),
                        )
                    )
                    groupDao.bumpMemberCount(packet.groupId)
                }
                Log.i(TAG, "group info applied group=${packet.groupId} title=${packet.title}")
            }

            is GroupWire.Packet.Kick -> {
                if (packet.nodeId != me) return
                // Исключить мог только владелец или администратор с правом бана.
                val sender = groupDao.getMember(packet.groupId, senderId)
                if (sender == null || !GroupPermissions.canBan(sender.role, sender.permissions)) return
                groupDao.deleteMember(packet.groupId, me)
                groupDao.markLeft(packet.groupId)
                Log.i(TAG, "kicked from group=${packet.groupId} by=$senderId")
            }

            is GroupWire.Packet.Directory -> handleDirectory(packet, senderId)

            is GroupWire.Packet.Nick -> handleNick(packet, senderId)

            is GroupWire.Packet.WhoIs -> {
                // У собеседника вместо нашего имени набор букв и цифр.
                // Отвечаем адресно и сразу, не дожидаясь роевой рассылки.
                sendMyIdentityTo(senderId)
                Log.i(TAG, "whois answered to=$senderId")
            }

            is GroupWire.Packet.Avatar -> handleAvatar(packet, senderId)

            is GroupWire.Packet.TopicsRequest -> {
                val group = groupDao.getGroupById(packet.groupId) ?: return
                if (group.ownerId != me) return
                if (groupDao.getMember(packet.groupId, senderId) == null) return
                sendTopics(packet.groupId, senderId)
                Log.i(TAG, "topics sent on request group=${packet.groupId} to=$senderId")
            }

            is GroupWire.Packet.Topics -> {
                // Список тем приходит новому участнику от владельца группы.
                val group = groupDao.getGroupById(packet.groupId)
                if (group == null || group.ownerId != senderId) return
                if (groupDao.getMember(packet.groupId, me) == null) return
                val now = clock()
                packet.entries.forEach { entry ->
                    if (groupDao.getTopicById(entry.topicId) == null) {
                        groupDao.insertTopic(
                            GroupTopicEntity(
                                id = entry.topicId,
                                groupId = packet.groupId,
                                name = entry.name,
                                ownerId = senderId,
                                ownerName = groupDao.getMember(packet.groupId, senderId)
                                    ?.displayName.orEmpty(),
                                iconEmoji = entry.iconEmoji,
                                createdAtMs = now,
                            )
                        )
                    }
                }
                Log.i(TAG, "topics applied group=${packet.groupId} count=${packet.entries.size}")
            }

            is GroupWire.Packet.Roster -> {
                if (groupDao.getMember(packet.groupId, me) == null) return
                val now = clock()
                packet.entries.forEach { entry ->
                    val current = groupDao.getMember(packet.groupId, entry.nodeId)
                    if (current == null) {
                        groupDao.insertMember(
                            GroupMemberEntity(
                                groupId = packet.groupId,
                                nodeId = entry.nodeId,
                                displayName = entry.displayName,
                                role = entry.role,
                                joinedAtMs = now,
                            )
                        )
                    } else if (current.displayName != entry.displayName || current.role != entry.role) {
                        groupDao.insertMember(
                            current.copy(displayName = entry.displayName, role = entry.role)
                        )
                    }
                }
                // На большом канале список неполный (только администраторы), а
                // число подписчиков пришло от владельца в `peers`: ниже него
                // не опускаем.
                groupDao.bumpMemberCount(packet.groupId)
                // Состав обновился: манифесты, ждавшие автора-администратора,
                // можно проверить ещё раз.
                val ready = pendingManifests.take { (_, m) -> m.groupId == packet.groupId }
                for ((from, manifest) in ready) handleManifest(from, manifest)
            }
        }
    }

    // ── Закрепы ───────────────────────────────────────────────────────────────

    /**
     * Закрепить или открепить сообщение. Право есть только у владельца и у
     * администратора, которому явно выдали PIN_MESSAGES.
     */
    suspend fun setPinned(
        groupId: String,
        messageId: String,
        pinned: Boolean,
    ): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canPinMessages(member.role, member.permissions)) {
            return Result.failure(SecurityException("Закреплять сообщения могут только администраторы с таким правом"))
        }
        val message = messageDao.getMessageById(messageId)
            ?: return Result.failure(IllegalStateException("Сообщение не найдено"))
        if (message.chatId != groupId) {
            return Result.failure(IllegalArgumentException("Сообщение из другой группы"))
        }
        messageDao.updatePinned(messageId, pinned, if (pinned) clock() else null, if (pinned) me else null)
        broadcast(groupId, GroupWire.buildPin(groupId, message.topicId.orEmpty(), messageId, pinned), excludeSelf = true)
        return Result.success(Unit)
    }

    /**
     * Тема прочитана.
     *
     * Счётчик непрочитанных только рос: markTopicRead и markGroupRead в DAO
     * были, но их никто не вызывал, поэтому цифры на группе висели даже после
     * чтения. Теперь экран ленты вызывает этот метод при каждом обновлении -
     * и при входе в тему, и когда новое сообщение приходит на открытом экране.
     */
    suspend fun markRead(groupId: String, topicId: String?) {
        if (groupId.isBlank()) return
        // ВАЖНО: писать в базу только когда есть что менять.
        //
        // Экран темы вызывает этот метод на каждом обновлении ленты, а лента
        // темы и список тем живут в одной таблице group_topics. Без этой
        // проверки получался замкнутый круг: сброс непрочитанных писал в
        // таблицу, поток тем перезапускал ленту, лента снова звала сброс - и
        // так без конца, пока приложение не зависало.
        if (!topicId.isNullOrBlank()) {
            val topic = groupDao.getTopicById(topicId)
            if (topic != null && topic.unreadCount > 0) {
                groupDao.markTopicRead(topicId)
            }
        }
        val group = groupDao.getGroupById(groupId) ?: return
        val unread = groupDao.sumTopicUnread(groupId)
        if (group.unreadCount != unread) {
            groupDao.setGroupUnread(groupId, unread)
        }
    }

    /** Закреплённые сообщения конкретной темы (а не всей группы). */
    fun observePinned(groupId: String, topicId: String): Flow<List<MessageEntity>> =
        messageDao.observePinnedMessages(groupId, topicId)

    /** Лента сообщений конкретной темы. */
    fun observeTopicMessages(groupId: String, topicId: String): Flow<List<MessageEntity>> =
        messageDao.observeTopicMessages(groupId, topicId)

    // ── Заявки на вступление ──────────────────────────────────────────────────

    fun observeJoinRequests(groupId: String): Flow<List<JoinRequestSummary>> =
        groupDao.observePendingRequests(groupId).map { list ->
            list.map { JoinRequestSummary(it.groupId, it.nodeId, it.displayName, it.note, it.requestedAtMs) }
        }.flowOn(Dispatchers.IO)

    /** Шаг 1 для частного вступления: заявка уходит администраторам группы. */
    suspend fun sendJoinRequest(groupId: String, note: String): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val admins = groupDao.getAdmins(groupId)
        if (admins.isEmpty()) return Result.failure(IllegalStateException("В группе нет администраторов"))
        val envelope = GroupWire.buildJoinRequest(
            groupId = groupId,
            displayName = myDisplayName(),
            note = note.trim().take(MAX_NOTE_CHARS),
            slug = groupDao.getGroupById(groupId)?.inviteSlug.orEmpty(),
        )
        delivery.deliver(groupId, envelope, admins.map { it.nodeId }.filter { it != me })
        return Result.success(Unit)
    }

    suspend fun decideJoinRequest(
        groupId: String,
        nodeId: String,
        approve: Boolean,
    ): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
            admitMember(group, nodeId, request.displayName, slug = "")
        } else {
            delivery.deliver(
                groupId,
                GroupWire.buildJoinDecision(groupId, nodeId, false),
                listOf(nodeId),
            )
        }
        return Result.success(Unit)
    }

    // ── Ссылки-приглашения и QR ───────────────────────────────────────────────

    fun observeInvites(groupId: String): Flow<List<InviteSummary>> =
        groupDao.observeInvites(groupId).map { list ->
            val group = groupDao.getGroupById(groupId)
            list.map { toInviteSummary(it, group?.ownerId, group?.isChannel == true) }
        }

    /**
     * Ссылка-приглашение. Кроме slug несёт id группы и адрес владельца — иначе
     * вступающий телефон не знает, у кого её запрашивать.
     */
    private fun toInviteSummary(
        i: GroupInviteEntity,
        ownerId: String? = null,
        isChannel: Boolean = false,
    ) = InviteSummary(
        slug = i.slug,
        groupId = i.groupId,
        link = GroupInviteLinks.build(
            slug = i.slug,
            groupId = i.groupId,
            ownerId = ownerId,
            isChannel = isChannel,
            requestApproval = i.requestApproval,
        ),
        createdAtMs = i.createdAtMs,
        expiresAtMs = i.expiresAtMs,
        maxUses = i.maxUses,
        useCount = i.useCount,
        revoked = i.revoked,
        requestApproval = i.requestApproval,
    )

    suspend fun createInvite(
        groupId: String,
        expiresAtMs: Long? = null,
        maxUses: Int = 0,
        requestApproval: Boolean? = null,
    ): Result<InviteSummary> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val mask = effectiveMemberMask(group)
        if (!GroupPermissions.canInvite(member.role, member.permissions, mask)) {
            return Result.failure(SecurityException("Нет права приглашать участников"))
        }
        val slug = GroupInviteLinks.newSlug()
        val entity = GroupInviteEntity(
            slug = slug,
            groupId = groupId,
            createdBy = me,
            createdAtMs = clock(),
            expiresAtMs = expiresAtMs,
            maxUses = maxUses.coerceAtLeast(0),
            // По умолчанию частная группа требует одобрения даже по ссылке.
            requestApproval = requestApproval ?: !group.isPublic,
        )
        groupDao.insertInvite(entity)
        return Result.success(toInviteSummary(entity, group.ownerId, group.isChannel))
    }

    suspend fun revokeInvite(groupId: String, slug: String): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        // Управление ссылками - только владелец или администратор с правом
        // приглашать. Право «добавлять участников» у обычных участников такого
        // разрешения не даёт: иначе любой мог отозвать чужую ссылку.
        if (!GroupPermissions.canManageInvites(member.role, member.permissions)) {
            return Result.failure(SecurityException("Ссылками управляют только администраторы"))
        }
        groupDao.revokeInvite(slug)
        return Result.success(Unit)
    }

    /**
     * Убрать ссылку из списка совсем. Отзыв только помечает её недействующей,
     * а владельцу нужно ещё и очистить список от мертвых строк.
     */
    suspend fun deleteInvite(groupId: String, slug: String): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        if (!GroupPermissions.canManageInvites(member.role, member.permissions)) {
            return Result.failure(SecurityException("Ссылками управляют только администраторы"))
        }
        groupDao.deleteInvite(slug)
        return Result.success(Unit)
    }

    /**
     * Вступление по ссылке или QR — с ЛЮБОГО телефона.
     *
     * Пригласительная запись есть только в базе создателя группы, поэтому искать
     * её по slug на чужом телефоне бессмысленно: ссылка сама несёт id группы и
     * адрес владельца. Мы шлём владельцу заявку прямо по этим данным, а он уже
     * проверяет ссылку у себя и либо пускает сразу, либо показывает заявку в
     * админ-кабинете.
     *
     * Если приглашение всё-таки нашлось локально (владелец открыл собственную
     * ссылку на своём телефоне) — работаем по старому локальному пути.
     */
    suspend fun joinByLink(raw: String, note: String = ""): JoinOutcome {
        val target = GroupInviteLinks.parseTarget(raw)
            ?: return JoinOutcome.Failed("Это не ссылка-приглашение в группу")
        val me = myId() ?: return JoinOutcome.Failed("Идентичность узла ещё не готова")

        // Уже в группе — не шлём повторную заявку.
        val known = target.groupId?.let { groupDao.getGroupById(it) }
        if (known != null && !known.isLeft && groupDao.getMember(known.id, me) != null) {
            return JoinOutcome.Joined(known.id, known.title, known.isChannel)
        }

        // Своя ссылка на своём телефоне: приглашение лежит в локальной базе.
        if (groupDao.getInviteBySlug(target.slug) != null) return joinBySlug(target.slug, note)

        if (!target.isRoutable) {
            return JoinOutcome.Failed(
                "Ссылка старого образца: попросите владельца прислать её заново из раздела «Сообщества»"
            )
        }
        val groupId = target.groupId.orEmpty()
        val ownerId = target.ownerId.orEmpty()
        if (ownerId == me) return JoinOutcome.Failed("Это ваша группа")

        val envelope = GroupWire.buildJoinRequest(
            groupId = groupId,
            displayName = myDisplayName(),
            note = note.trim().take(MAX_NOTE_CHARS),
            slug = target.slug,
        )
        delivery.deliver(groupId, envelope, listOf(ownerId))
        Log.i(TAG, "join request sent to owner group=$groupId")
        // Признаки берём из ссылки: своей базы с приглашением здесь нет, а
        // писать «заявка отправлена», когда владелец принимает сразу, - врать.
        return JoinOutcome.RequestSent(
            groupId = groupId,
            title = "",
            isChannel = target.isChannel,
            needsApproval = target.needsApproval,
        )
    }

    /**
     * Вступление по slug, когда приглашение лежит в ЛОКАЛЬНОЙ базе — то есть на
     * телефоне владельца группы. С чужого телефона сюда попадать не должно:
     * там работает [joinByLink].
     */
    suspend fun joinBySlug(slug: String, note: String = ""): JoinOutcome {
        val me = myId() ?: return JoinOutcome.Failed("Идентичность узла ещё не готова")
        val invite = groupDao.getInviteBySlug(slug)
            ?: return JoinOutcome.Failed("Приглашение не найдено на этом телефоне")
        if (invite.revoked) return JoinOutcome.Failed("Приглашение отозвано")
        val expired = invite.expiresAtMs != null && invite.expiresAtMs <= clock()
        if (expired) return JoinOutcome.Failed("Приглашение истекло")
        if (invite.maxUses > 0 && invite.useCount >= invite.maxUses) {
            return JoinOutcome.Failed("Лимит вступлений по этой ссылке исчерпан")
        }
        val group = groupDao.getGroupById(invite.groupId)
            ?: return JoinOutcome.Failed("Группа не найдена")
        if (groupDao.getMember(group.id, me) != null) {
            return JoinOutcome.Joined(group.id, group.title, group.isChannel)
        }

        return if (invite.requestApproval) {
            groupDao.insertJoinRequest(
                GroupJoinRequestEntity(
                    groupId = group.id,
                    nodeId = me,
                    displayName = myDisplayName(),
                    note = note.trim().take(MAX_NOTE_CHARS),
                    requestedAtMs = clock(),
                )
            )
            sendJoinRequest(group.id, note)
            JoinOutcome.RequestSent(
                groupId = group.id,
                title = group.title,
                isChannel = group.isChannel,
                needsApproval = true,
            )
        } else {
            groupDao.insertMember(
                GroupMemberEntity(
                    groupId = group.id,
                    nodeId = me,
                    displayName = myDisplayName(),
                    role = GroupRole.MEMBER,
                    joinedAtMs = clock(),
                )
            )
            groupDao.registerInviteUse(slug)
            groupDao.refreshMemberCount(group.id)
            JoinOutcome.Joined(group.id, group.title, group.isChannel)
        }
    }

    // ── Участники: поиск, роли, права, баны ───────────────────────────────────

    fun observeMembers(groupId: String): Flow<List<MemberSummary>> =
        groupDao.observeMembers(groupId)
            .map { list -> list.map { toMemberSummary(it) } }
            .flowOn(Dispatchers.IO)

    private suspend fun toMemberSummary(m: GroupMemberEntity) = MemberSummary(
        nodeId = m.nodeId,
        displayName = m.displayName,
        role = m.role,
        joinedAtMs = m.joinedAtMs,
        permissions = m.permissions,
        customTitle = m.customTitle,
        isBanned = m.isBanned,
        isMe = m.nodeId == myId(),
    )

    /** Поиск участников по имени или идентификатору узла. */
    suspend fun searchMembers(groupId: String, query: String): List<MemberSummary> {
        val q = query.trim()
        val rows = if (q.isEmpty()) groupDao.getMembers(groupId) else groupDao.searchMembers(groupId, q)
        return rows.map { toMemberSummary(it) }
    }

    suspend fun setAdminRole(groupId: String, nodeId: String, admin: Boolean): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val actor = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canAddAdmins(actor.role, actor.permissions)) {
            return Result.failure(SecurityException("Нет права назначать администраторов"))
        }
        val target = groupDao.getMember(groupId, nodeId)
            ?: return Result.failure(IllegalStateException("Участник не найден"))
        if (target.role == GroupRole.OWNER) {
            return Result.failure(IllegalArgumentException("Владельца нельзя разжаловать"))
        }
        groupDao.updateMemberRole(
            groupId,
            nodeId,
            if (admin) GroupRole.ADMIN else GroupRole.MEMBER,
            if (admin) GroupPermissions.Admin.DEFAULT else 0L,
        )
        publishRoster(groupId, adminsChanged = true)
        return Result.success(Unit)
    }

    suspend fun setAdminPermissions(groupId: String, nodeId: String, mask: Long): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val actor = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canAddAdmins(actor.role, actor.permissions)) {
            return Result.failure(SecurityException("Нет права менять права администраторов"))
        }
        val target = groupDao.getMember(groupId, nodeId)
            ?: return Result.failure(IllegalStateException("Участник не найден"))
        if (target.role == GroupRole.OWNER) {
            return Result.failure(IllegalArgumentException("Права владельца не ограничиваются"))
        }
        groupDao.updateMemberPermissions(groupId, nodeId, mask and GroupPermissions.Admin.ALL)
        publishRoster(groupId, adminsChanged = true)
        return Result.success(Unit)
    }

    /** Разрешения для участников — общая политика группы. */
    suspend fun setMemberPermissions(groupId: String, mask: Long): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val actor = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        // Смена разрешений участников — это изменение информации о группе,
        // поэтому требуется право CHANGE_INFO (владелец имеет его безусловно).
        if (!GroupPermissions.canChangeInfo(actor.role, actor.permissions, 0L)) {
            return Result.failure(SecurityException("Нет права менять разрешения группы"))
        }
        // Только точечный UPDATE. Перезапись строки группы (INSERT OR REPLACE)
        // удаляет её, а внешний ключ с ON DELETE CASCADE стирает участников,
        // темы, ссылки и заявки - именно так группа «теряла» темы и владельца.
        groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        groupDao.updateGroupMemberPermissions(groupId, mask and GroupPermissions.Member.ALL)
        return Result.success(Unit)
    }

    suspend fun setMemberBlocked(groupId: String, nodeId: String, banned: Boolean): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val actor = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canBan(actor.role, actor.permissions)) {
            return Result.failure(SecurityException("Нет права ограничивать участников"))
        }
        val target = groupDao.getMember(groupId, nodeId)
            ?: return Result.failure(IllegalStateException("Участник не найден"))
        if (target.role == GroupRole.OWNER) {
            return Result.failure(IllegalArgumentException("Владельца нельзя ограничить"))
        }
        if (banned) {
            groupDao.updateMemberBanned(groupId, nodeId, true)
        } else {
            groupDao.deleteMember(groupId, nodeId)
        }
        groupDao.refreshMemberCount(groupId)
        publishRoster(groupId)
        // Самому исключённому говорим отдельно: рассылка состава до него уже
        // не доходит как «вас убрали», и группа остаётся у него в списке.
        // Сеть могла отказать - в базе он уже исключён, поэтому падать нельзя.
        runCatching {
            delivery.deliver(groupId, GroupWire.buildKick(groupId, nodeId), listOf(nodeId))
        }.onFailure { e ->
            Log.w(TAG, "kick notice not delivered to $nodeId: ${e.message}")
        }
        return Result.success(Unit)
    }

    // ── Публичная / частная группа ────────────────────────────────────────────

    suspend fun setPublic(groupId: String, isPublic: Boolean): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        if (!GroupPermissions.canChangeInfo(member.role, member.permissions, effectiveMemberMask(group))) {
            return Result.failure(SecurityException("Нет права менять тип группы"))
        }
        groupDao.updateGroupVisibility(groupId, isPublic)
        return Result.success(Unit)
    }

    suspend fun updateProfile(groupId: String, title: String, about: String): Result<Unit> {
        val clean = title.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Пустое название"))
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        if (!GroupPermissions.canChangeInfo(member.role, member.permissions, effectiveMemberMask(group))) {
            return Result.failure(SecurityException("Нет права менять информацию о группе"))
        }
        groupDao.updateGroupProfile(groupId, clean.take(MAX_TITLE_CHARS), about.trim().take(MAX_ABOUT_CHARS))
        return Result.success(Unit)
    }

    /** Снять счётчик непрочитанного - пункт меню в пузыре группы. */
    suspend fun markGroupRead(groupId: String) {
        groupDao.markGroupRead(groupId)
    }

    /**
     * Ссылка-приглашение в группу и её название: то же, что готовит главный
     * экран, но доступное и из раздела «Группы».
     */
    suspend fun inviteLinkFor(groupId: String): Pair<String, String>? {
        val group = groupDao.getGroupById(groupId) ?: return null
        val slug = group.inviteSlug
        if (slug.isBlank()) return null
        return group.title to GroupInviteLinks.build(
            slug = slug,
            groupId = group.id,
            ownerId = group.ownerId,
            isChannel = group.isChannel,
            // Частная группа принимает по заявке: вступающий телефон должен
            // честно написать «заявка отправлена».
            requestApproval = !group.isPublic,
        )
    }

    /**
     * Ссылка для QR при личной встрече: вход БЕЗ одобрения.
     *
     * Когда люди стоят рядом и показывают друг другу телефоны, подтверждать
     * нечего - они уже видят, кого пускают. Поэтому создаётся отдельное
     * приглашение с выключенным одобрением, а обычная ссылка «поделиться»
     * остаётся строгой: её могут переслать кому угодно.
     */
    suspend fun qrInviteLinkFor(groupId: String): Pair<String, String>? {
        val group = groupDao.getGroupById(groupId) ?: return null
        val invite = createInvite(groupId, requestApproval = false).getOrNull() ?: return null
        return group.title to invite.link
    }

    /**
     * Ссылка на конкретный пост канала.
     *
     * Это обычное приглашение в канал плюс тема поста: получатель подпишется
     * и попадёт сразу на нужную запись, а не в начало ленты. Ссылку понимает
     * и сам APU, и любой чужой мессенджер - переход вернёт человека в APU.
     */
    suspend fun postLinkFor(channelId: String, topicId: String): String? {
        val channel = groupDao.getGroupById(channelId) ?: return null
        val invite = createInvite(channelId, requestApproval = false).getOrNull() ?: return null
        // Веб-адрес, а не p2pmessenger://: чужие мессенджеры подсвечивают
        // только http(s), поэтому пересланная ссылка была мёртвым текстом.
        return GroupInviteLinks.buildWebLink(
            slug = invite.slug,
            groupId = channelId,
            ownerId = channel.ownerId,
            isChannel = channel.isChannel,
            postTopicId = topicId,
        )
    }

    suspend fun leaveGroup(groupId: String): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (member.role == GroupRole.OWNER) {
            return Result.failure(IllegalArgumentException("Владелец не может выйти из группы без передачи прав"))
        }
        groupDao.deleteMember(groupId, me)
        groupDao.markLeft(groupId)
        return Result.success(Unit)
    }

    // ── Статистика для администраторов ────────────────────────────────────────

    /**
     * Удалить группу безвозвратно. Право только у владельца: это не выход из
     * группы, а уничтожение переписки у всех участников. Остальным уходит
     * пакет GroupDeleted, и они стирают свою копию.
     */
    suspend fun deleteGroup(groupId: String): Result<Unit> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        if (group.ownerId != me) {
            return Result.failure(SecurityException("Удалить группу может только её владелец"))
        }
        // Рассылаем ДО зачистки: список получателей берётся из участников группы.
        broadcast(groupId, GroupWire.buildGroupDeleted(groupId), excludeSelf = true)
        deleteGroupLocally(groupId)
        return Result.success(Unit)
    }

    /** Локальная зачистка: сообщения группы, затем сама группа (дочерние строки — каскадом). */
    private suspend fun deleteGroupLocally(groupId: String) {
        messageDao.deleteGroupMessages(groupId)
        manifestDao?.deleteForGroup(groupId)
        groupDao.deleteGroup(groupId)
    }

    suspend fun stats(groupId: String, days: Int = 7): Result<GroupStats> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupRole.isAdminOrOwner(member.role)) {
            return Result.failure(SecurityException("Статистика доступна только администраторам"))
        }
        val fromKey = dayKey(clock() - (days - 1).toLong() * DAY_MS)
        val members = groupDao.getMembers(groupId)
        val topics = groupDao.getTopics(groupId)
        return Result.success(
            GroupStats(
                groupId = groupId,
                memberCount = members.count { !it.isBanned },
                adminCount = members.count { GroupRole.isAdminOrOwner(it.role) },
                topicCount = topics.size,
                pendingRequests = groupDao.countPendingRequests(groupId),
                totalMessages = groupDao.totalTopicMessages(groupId),
                last7Days = groupDao.getGroupStats(groupId, days)
                    .filter { it.dayKey >= fromKey }
                    .sortedBy { it.dayKey }
                    .map { GroupStatDay(it.dayKey, it.messageCount, it.senderCount) },
                perTopic = topics.associate { it.id to it.messageCount },
            )
        )
    }

    /** Суточный срез: сообщения и число разных отправителей. */
    private suspend fun registerStats(groupId: String, topicId: String, senderId: String, now: Long) {
        val key = dayKey(now)
        bumpStat(groupId, topicId, key, senderId)
        bumpStat(groupId, "", key, senderId)
    }

    private suspend fun bumpStat(groupId: String, topicId: String, key: String, senderId: String) {
        val existing = groupDao.getStat(groupId, topicId, key)
        val senders = LinkedHashSet<String>()
        if (existing != null) {
            existing.sendersCsv.split(',').filter { it.isNotBlank() }.forEach { senders.add(it) }
        }
        senders.add(senderId)
        groupDao.insertStat(
            GroupMessageStatEntity(
                groupId = groupId,
                topicId = topicId,
                dayKey = key,
                messageCount = (existing?.messageCount ?: 0) + 1,
                senderCount = senders.size,
                sendersCsv = senders.joinToString(","),
            )
        )
    }

    // ── Служебное ─────────────────────────────────────────────────────────────

    /**
     * Маска прав участников. Ноль означает «не задано» (например, группа
     * создана до появления поля) — тогда применяется набор по умолчанию,
     * иначе участники потеряли бы возможность писать.
     */
    private fun effectiveMemberMask(group: GroupEntity): Long =
        if (group.memberPermissions == 0L) GroupPermissions.Member.DEFAULT else group.memberPermissions

    /**
     * Принять человека в группу и прислать ему всё, без чего группа у него не
     * появится: карточку группы, состав и решение. Вызывается и при вступлении
     * по ссылке без одобрения, и при ручном одобрении заявки.
     */
    private suspend fun admitMember(
        group: GroupEntity,
        nodeId: String,
        displayName: String,
        slug: String,
    ) {
        groupDao.insertMember(
            GroupMemberEntity(
                groupId = group.id,
                nodeId = nodeId,
                displayName = displayName,
                role = GroupRole.MEMBER,
                joinedAtMs = clock(),
            )
        )
        if (slug.isNotBlank()) groupDao.registerInviteUse(slug)
        groupDao.refreshMemberCount(group.id)
        sendGroupInfo(group, nodeId)
        sendTopics(group.id, nodeId)
        publishRoster(group.id, newcomer = nodeId)
        if (group.isChannel) sendPostKeys(group, nodeId)
        delivery.deliver(
            group.id,
            GroupWire.buildJoinDecision(group.id, nodeId, true),
            listOf(nodeId),
        )
        Log.i(TAG, "member admitted group=${group.id} node=$nodeId")
        // Новому подписчику канала - последние посты целиком, а не только
        // названия: иначе лента у него пуста, пока кто-нибудь не напишет.
        // На большом канале (есть кому раздавать) владелец шлёт только
        // манифесты: куски новичок тянет полосами у соседей из `peers`, а
        // владелец не тратит по 600 КБ на каждого вступившего. Старый телефон
        // манифестов не поймёт - но и вступить в большой канал он может, так
        // что ему по-прежнему шлём посты целиком.
        if (group.isChannel) {
            backgroundScope.launch {
                // Небольшая пауза: карточка канала, темы и решение о приёме
                // должны улечься у получателя раньше постов - пост для ещё
                // не известного канала он отбрасывает.
                kotlinx.coroutines.delay(1_500L)
                val big = groupDao.countMembers(group.id) > FIRST_WAVE
                // Свой ключ новичок присылает в ответ на мой `pkeys`; на большом
                // канале ждём его ещё несколько секунд - иначе шлём куски зря.
                var swarmCapable = signerDao?.get(nodeId, nodeId) != null
                var waited = 0
                while (big && !swarmCapable && waited < KEY_WAIT_POLLS) {
                    kotlinx.coroutines.delay(KEY_WAIT_POLL_MS)
                    waited++
                    swarmCapable = signerDao?.get(nodeId, nodeId) != null
                }
                runCatching {
                    if (swarmCapable && big) {
                        sendManifests(group, nodeId, BACKFILL_POSTS)
                    } else {
                        backfillPosts(group, nodeId, BACKFILL_POSTS, emptySet())
                    }
                }.onFailure { Log.w(TAG, "posts push failed: ${it.message}") }
            }
        }
    }

    /** Манифесты последних [limit] постов - новичку большого канала (куски он возьмёт у соседей). */
    private suspend fun sendManifests(group: GroupEntity, nodeId: String, limit: Int) {
        val dao = manifestDao ?: return
        val topics = groupDao.getTopics(group.id).sortedByDescending { it.createdAtMs }.take(limit)
        var sent = 0
        for (topic in topics) {
            val manifest = dao.getByTopic(topic.id)?.let(::toManifest) ?: continue
            if (sent > 0) kotlinx.coroutines.delay(BACKFILL_PACKET_GAP_MS)
            delivery.deliver(group.id, GroupWire.buildPostManifest(manifest), listOf(nodeId))
            sent++
        }
        Log.i(TAG, "manifests sent group=${group.id} to=$nodeId count=$sent")
    }

    /** Группы, у которых мы уже просили темы в этом запуске приложения. */
    private val topicsRequested = HashSet<String>()

    /**
     * Попросить у владельца список тем.
     *
     * Вызывается при открытии чата группы: вступивший позже не застал пакеты
     * TopicCreated и видит пустой список тем. Просим не чаще раза запуск на
     * группу - пакет маленький, а приёмник добавляет только отсутствующие темы.
     */
    suspend fun requestTopics(groupId: String) {
        val me = myId() ?: return
        val group = groupDao.getGroupById(groupId) ?: return
        if (group.ownerId == me) return
        if (groupDao.getMember(groupId, me) == null) return
        if (!topicsRequested.add(groupId)) return
        delivery.deliver(groupId, GroupWire.buildTopicsRequest(groupId), listOf(group.ownerId))
        Log.i(TAG, "topics requested group=$groupId")
    }

    /**
     * Самолечение после аварии с перезаписью группы.
     *
     * Если у владельца нет своей строки участника (её стёр каскад по внешнему
     * ключу, когда строка группы перезаписывалась через INSERT OR REPLACE),
     * возвращаем её: без этой строки владелец не может ни писать, ни управлять
     * группой - везде «вы не участник группы». Сами темы такая авария не
     * возвращает, но группа снова становится управляемой.
     */
    suspend fun repairOwnerMemberships(): Int {
        val me = myId() ?: return 0
        var repaired = 0
        groupDao.getGroups().forEach { group ->
            if (group.ownerId != me) return@forEach
            if (groupDao.getMember(group.id, me) != null) return@forEach
            groupDao.insertMember(
                GroupMemberEntity(
                    groupId = group.id,
                    nodeId = me,
                    displayName = myDisplayName(),
                    role = GroupRole.OWNER,
                    joinedAtMs = clock(),
                )
            )
            groupDao.clearLeft(group.id)
            groupDao.refreshMemberCount(group.id)
            repaired++
            Log.i(TAG, "owner membership repaired group=${group.id}")
        }
        return repaired
    }

    /**
     * Разослать участникам карточку группы, темы и состав заново.
     *
     * Нужно для тех, кто вступил до появления этих пакетов: темы и состав они
     * не получили и видят пустую группу. Повторная отправка безопасна - приёмник
     * добавляет только отсутствующие строки.
     */
    suspend fun resyncMembers(groupId: String): Result<Int> {
        val me = myId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val actor = groupDao.getMember(groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupRole.isAdminOrOwner(actor.role)) {
            return Result.failure(SecurityException("Рассылка доступна только администраторам"))
        }
        val group = groupDao.getGroupById(groupId)
            ?: return Result.failure(IllegalStateException("Группа не найдена"))
        val others = groupDao.getMembers(groupId).map { it.nodeId }.filter { it != me }
        others.forEach { id ->
            sendGroupInfo(group, id)
            sendTopics(group.id, id)
        }
        publishRoster(groupId, adminsChanged = true)
        Log.i(TAG, "resync sent group=$groupId to=${others.size}")
        return Result.success(others.size)
    }

    /**
     * Темы группы новому участнику. Без них он попадает в пустой чат: темы
     * создаются пакетом TopicCreated в момент создания, и опоздавший их не видел.
     */
    private suspend fun sendTopics(groupId: String, nodeId: String) {
        val topics = groupDao.getTopics(groupId)
        if (topics.isEmpty()) return
        delivery.deliver(
            groupId,
            GroupWire.buildTopics(
                groupId,
                topics.map { GroupWire.TopicEntry(it.id, it.name, it.iconEmoji) },
            ),
            listOf(nodeId),
        )
    }

    /** Карточка группы новому участнику: название, описание, владелец, настройки. */
    private suspend fun sendGroupInfo(group: GroupEntity, nodeId: String) {
        delivery.deliver(
            group.id,
            GroupWire.buildGroupInfo(
                groupId = group.id,
                title = group.title,
                about = group.about,
                ownerId = group.ownerId,
                inviteSlug = group.inviteSlug,
                isPublic = group.isPublic,
                topicsEnabled = group.topicsEnabled,
                isChannel = group.isChannel,
            ),
            listOf(nodeId),
        )
    }

    private suspend fun broadcast(groupId: String, envelope: String, excludeSelf: Boolean): DeliveryReport {
        val me = myId().orEmpty()
        val recipients = groupDao.getMembers(groupId)
            .filter { !it.isBanned }
            .map { it.nodeId }
            .filter { !excludeSelf || it != me }
        return delivery.deliver(groupId, envelope, recipients)
    }

    /**
     * Запоминает автора сообщения, чтобы в ленте было имя, а не обрывок
     * идентификатора.
     *
     * Список участников приходит только при изменении состава, и пропустивший
     * его телефон имени не знает. Если имя приехало вместе с сообщением -
     * заводим или дополняем строку участника. Если имени нет - просим группу
     * прислать состав (по одному разу на незнакомый узел, чтобы не спамить).
     */
    private suspend fun rememberSender(
        groupId: String,
        senderId: String,
        senderName: String,
        now: Long,
    ) {
        if (senderId.isBlank()) return
        val known = groupDao.getMember(groupId, senderId)
        if (known == null) {
            if (senderName.isNotBlank()) {
                groupDao.insertMember(
                    GroupMemberEntity(
                        groupId = groupId,
                        nodeId = senderId,
                        displayName = senderName,
                        role = GroupRole.MEMBER,
                        joinedAtMs = now,
                    ),
                )
                groupDao.bumpMemberCount(groupId)
            } else if (rosterAsked.add(groupId + '|' + senderId)) {
                // Отвечают только владелец и администраторы - им и шлём, а не
                // всем: на большом канале «всем» - это тысячи пакетов.
                val me = myId().orEmpty()
                val admins = groupDao.getAdmins(groupId).map { it.nodeId }.filter { it != me }
                if (admins.isNotEmpty()) delivery.deliver(groupId, GroupWire.buildRosterRequest(groupId), admins)
            }
            return
        }
        if (known.displayName.isBlank() && senderName.isNotBlank()) {
            groupDao.insertMember(known.copy(displayName = senderName))
        }
    }

    /**
     * После изменения состава рассылаем актуальный список участников.
     *
     * На большом канале (больше [ROSTER_LIMIT] участников) полный список в
     * пакет не помещается и рассылать его всем при каждом вступлении -
     * O(N²) трафика. Вместо него [newcomer] получает `peers`: число
     * подписчиков и выборку соседей, у кого спрашивать посты; остальным
     * ничего не шлём (имена они узнают из конвертов сообщений и по `who`).
     * Смена ролей и исключения на большом канале идут отдельными пакетами
     * (роль в конверте `roster` ограниченного размера - только админы).
     */
    private suspend fun publishRoster(groupId: String, newcomer: String? = null, adminsChanged: Boolean = false) {
        val members = groupDao.getMembers(groupId)
        if (members.size <= ROSTER_LIMIT) {
            val entries = members.map { GroupWire.RosterEntry(it.nodeId, it.displayName, it.role) }
            broadcast(groupId, GroupWire.buildRoster(groupId, entries), excludeSelf = true)
            return
        }
        val me = myId().orEmpty()
        // Список администраторов (с именами и ролями) - короткий; по нему
        // подписчики принимают манифесты постов администраторов и знают,
        // кого спрашивать о составе.
        val adminMembers = members.filter { GroupRole.isAdminOrOwner(it.role) }
        val adminRoster = GroupWire.buildRoster(
            groupId,
            adminMembers.map { GroupWire.RosterEntry(it.nodeId, it.displayName, it.role) },
        )
        if (newcomer == null) {
            // Смена состава без новичка: рассылать нечего, кроме смены ролей -
            // и только её (исключение одного из тысяч - не повод для N пакетов).
            if (adminsChanged) broadcast(groupId, adminRoster, excludeSelf = true)
            return
        }
        // Владелец и администраторы - всегда, остальное место - случайные
        // подписчики: у них новичок будет спрашивать посты. Этап 3: сначала
        // те, кто умеет рой (владелец знает их ключи - каждый новичок
        // предъявляет свой), потом остальные: сосед со старой версией на
        // просьбу о кусках не ответит.
        val admins = adminMembers.map { it.nodeId }.toHashSet()
        val capable = signerDao?.allSelfAttestedIds()?.toHashSet().orEmpty()
        val plain = members.asSequence().map { it.nodeId }
            .filter { it != me && it !in admins && it != newcomer }.toList()
        val others = plain.filter { it in capable }.shuffled() + plain.filter { it !in capable }.shuffled()
        val sample = (admins.toList() + others).filter { it != newcomer }.take(PEERS_SAMPLE)
        delivery.deliver(groupId, adminRoster, listOf(newcomer))
        delivery.deliver(groupId, GroupWire.buildPeers(groupId, members.size, sample), listOf(newcomer))
        // Соседям из выборки - строка о новичке (обычный короткий `roster` из
        // одной записи): иначе на закрытом канале они не признают его
        // участником и не отдадут ему куски. Администраторы всегда в выборке,
        // так что их список участников остаётся полным.
        val newcomerRow = members.firstOrNull { it.nodeId == newcomer }
        if (newcomerRow != null) {
            delivery.deliver(
                groupId,
                GroupWire.buildRoster(
                    groupId,
                    listOf(GroupWire.RosterEntry(newcomerRow.nodeId, newcomerRow.displayName, newcomerRow.role)),
                ),
                sample,
            )
        }
        Log.i(TAG, "peers sent group=$groupId members=${members.size} sample=${sample.size} to=$newcomer")
    }

    /** Превью для списков: без служебных строк фотографий. */
    private fun preview(text: String): String {
        val clean = InlineImage.stripImage(text)
        val shown = if (clean.isBlank() && (InlineImage.hasImage(text) || InlineImage.photoCount(text) > 0)) {
            "Фото"
        } else {
            clean
        }
        return shown.replace('\n', ' ').take(PREVIEW_CHARS)
    }

    private fun dayKey(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    companion object {
        /** Ключ группового аватара в роевом реестре avatars. */
        const val GROUP_AVATAR_PREFIX = "g:"
        const val GENERAL_TOPIC_NAME = "General"
        const val MAX_TITLE_CHARS = 128
        const val MAX_ABOUT_CHARS = 512
        const val MAX_TOPIC_CHARS = 128
        /**
         * Потолок текста поста или сообщения в группе.
         *
         * Фото вкладывается прямо в текст (InlineImage) и занимает до 7000
         * символов, поэтому прежние 4096 отвергали ЛЮБУЮ картинку - даже
         * самую маленькую: пост с фото не отправлялся вообще.
         *
         * 11000 подобрано под конверт APUGRP1: он вмещает 16 КБ, а текст
         * внутри ещё раз кодируется base64 и вырастает на треть
         * (11000 -> ~14700 плюс служебные поля). Запас оставлен намеренно.
         */
        const val MAX_MESSAGE_CHARS = 11000
        const val MAX_NOTE_CHARS = 256
        /** Сколько последних постов канала досылается вступившему позже. */
        const val BACKFILL_POSTS = 20
        /**
         * Потолок пакетов одной досылки: 20 постов по 6 фото по 3 куска - это
         * 380 пакетов, а очередь исходящих у ядра рассчитана на 256 (с
         * запасом под остальной трафик). Свежие посты идут первыми, текст
         * поста - раньше его фото, поэтому урезаются только фото самых
         * старых постов.
         */
        const val MAX_BACKFILL_PACKETS = 150
        /** Окно, в котором повторная досылка тому же узлу не дублирует уже отправленное. */
        private const val BACKFILL_REPEAT_MS = 8_000L
        /** Подписчик просит старые посты не чаще раза в полминуты на канал. */
        private const val POSTS_REQUEST_GAP_MS = 30_000L
        /** Пауза между пакетами досылки. */
        private const val BACKFILL_PACKET_GAP_MS = 40L
        /** Рой: кроме владельца, просьбу о постах получают ещё столько соседей. */
        const val SWARM_REQUEST_PEERS = 3
        /** Рой: соседей выбирают случайно из стольких лучших по ярусу. */
        const val SWARM_REQUEST_POOL = 10
        /** Рой: сколько чужих просьб в минуту обслуживает участник-сид. */
        private const val SEED_REPLIES_PER_MINUTE = 6
        /** Рой: буфер кусков без манифеста - записей и срок жизни. */
        private const val PENDING_PIECES = 100
        private const val PENDING_MANIFESTS = 40
        private const val PENDING_TTL_MS = 60_000L
        /** Метка в списке «уже слали»: манифест поста, а не сообщение. */
        private const val MANIFEST_SENT_PREFIX = "pman:"
        /** Рой, этап 2: первая волна автора - столько лучших из умеющих рой получают пост целиком. */
        const val FIRST_WAVE = 20
        /** Рой, этап 2: собравший пост раздаёт манифест стольким соседям. */
        const val RELAY_FANOUT = 10
        /** Рой, этап 2: раунды `pwant` и пауза между ними; первая пауза короче. */
        private const val FETCH_ROUNDS = 3
        private const val FETCH_ROUND_MS = 10_000L
        private const val FETCH_FIRST_DELAY_MS = 2_000L
        /** Рой, этап 2: сколько постов тянем полосами одновременно. */
        private const val FETCH_PARALLEL = 3
        /** Рой, этап 2: меньше стольких полос не делим даже при одном известном сиде (второй - сосед наугад). */
        private const val MIN_STRIPES = 2
        /** Рой, этап 2: по скольким темам помним сидов. */
        private const val MAX_TRACKED_TOPICS = 200
        /** Рой, этап 2: свежий пост от автора ждём до 18 × 5 с (веер автора небыстрый), прежде чем просить куски у соседей. */
        private const val AUTHOR_WAIT_POLLS = 18
        private const val AUTHOR_WAIT_POLL_MS = 5_000L
        /** Рой, этап 2: предел отдачи по `pwant` - кусков в минуту и полос одновременно. */
        private const val PIECES_PER_MINUTE = 60
        private const val SERVE_PARALLEL = 3
        /** Пост моложе этого, пришедший от сида, - новый (непрочитанный), а не досылка старого. */
        private const val FRESH_POST_MS = 15L * 60 * 1000
        /** Рой, этап 2: с такого числа участников владелец шлёт новичку `peers` вместо полного списка. */
        const val ROSTER_LIMIT = 100
        /** Рой, этап 2: сколько соседей владелец даёт новичку в `peers`. */
        const val PEERS_SAMPLE = 30
        /** Рой, этап 2: на большом канале владельца спрашивают о недостающем лишь в одном случае из стольких. */
        private const val OWNER_ASK_ONE_IN = 4
        /** Рой, этап 2: сколько ждём ключ новичка (4 × 1.5 с), прежде чем слать ему куски по-старому. */
        private const val KEY_WAIT_POLLS = 4
        private const val KEY_WAIT_POLL_MS = 1_500L
        /** Заголовок поста канала - первая строка текста, не длиннее этого. */
        const val POST_TITLE_CHARS = 40
        private const val PREVIEW_CHARS = 80
        /** Дальность эпидемии каталога: владелец -> контакты -> их контакты. */
        private const val MAX_DIR_HOPS = 2
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /**
         * Не чаще раза в 6 часов повторяем фоновые рассылки каталога, @имени и
         * аватара. Это справочные данные: они не меняются по несколько раз в
         * день, а трафик на слабой сети экономят заметно.
         */
        private const val GOSSIP_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000
        /**
         * Не чаще раза в 10 минут повторяем адресный запрос «представься» к
         * одному и тому же узлу. Имя нужно один раз: как только оно пришло,
         * запрос больше не отправляется вовсе.
         */
        private const val WHOIS_MIN_INTERVAL_MS = 10L * 60 * 1000
        private const val TAG = "GroupRepository"
    }
}
