package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.local.dao.DirectoryDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupInviteEntity
import com.vladimir.messenger.data.local.entity.GroupJoinRequestEntity
import com.vladimir.messenger.data.local.entity.GroupMemberEntity
import com.vladimir.messenger.data.local.entity.GroupMessageStatEntity
import com.vladimir.messenger.data.local.entity.GroupTopicEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
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
) {

    /**
     * Узлы, для которых состав уже запрашивали: повторно не спрашиваем,
     * чтобы одно неизвестное имя не породило поток запросов.
     */
    private val rosterAsked: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

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
        val me = myNodeId()
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
        runCatching { publishMyDirectory() }
            .onFailure { Log.w(TAG, "directory publish failed: ${it.message}") }
        return runCatching { requireSummary(groupId) }
    }

    // ── Список групп ──────────────────────────────────────────────────────────

    fun observeGroups(): Flow<List<GroupSummary>> =
        groupDao.observeGroups().map { list -> list.map { toSummary(it) } }

    fun observeGroup(groupId: String): Flow<GroupSummary?> =
        groupDao.observeGroup(groupId).map { it?.let { g -> toSummary(g) } }

    suspend fun summary(groupId: String): GroupSummary? =
        groupDao.getGroupById(groupId)?.let { toSummary(it) }

    private suspend fun requireSummary(groupId: String): GroupSummary =
        toSummary(groupDao.getGroupById(groupId) ?: error("group $groupId vanished"))

    /** Каналы этого телефона - для раздела «Каналы» на главном экране. */
    fun observeChannels(): Flow<List<GroupSummary>> =
        groupDao.observeChannels().map { list -> list.map { toSummary(it) } }

    /** Сетевой каталог: чужие публичные группы и каналы для поиска. */
    fun observeDirectory(): Flow<List<DirectoryEntity>> = directoryDao.observeAll()

    /**
     * Роевая публикация каталога: владелец рассказывает контактам о своих
     * публичных группах и каналах, контакты передают запись дальше (hops не
     * больше [MAX_DIR_HOPS) - так поиск находит созданное другими людьми без
     * центрального сервера.
     */
    suspend fun publishMyDirectory() {
        val me = myNodeId() ?: return
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
    }

    private suspend fun handleDirectory(packet: GroupWire.Packet.Directory, senderId: String) {
        if (packet.groupId.isBlank() || packet.title.isBlank()) return
        if (packet.ownerId == myNodeId()) return // своё не храним
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
        val me = myNodeId().orEmpty()
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
        groupDao.observeTopics(groupId).map { list -> list.map { toTopicSummary(it) } }

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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        broadcast(groupId, GroupWire.buildTopicCreated(groupId, topicId, clean), excludeSelf = true)
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
        val member = groupDao.getMember(topic.groupId, me)
            ?: return Result.failure(IllegalStateException("Вы не участник этой группы"))
        if (!GroupPermissions.canManageTopics(member.role, member.permissions)) {
            return Result.failure(SecurityException("Нет права управлять темами"))
        }
        return block()
    }

    // ── Отправка сообщения ────────────────────────────────────────────────────

    suspend fun sendMessage(groupId: String, topicId: String, text: String): Result<String> {
        val body = text.trim()
        if (body.isEmpty()) return Result.failure(IllegalArgumentException("Пустое сообщение"))
        if (body.length > MAX_MESSAGE_CHARS) {
            return Result.failure(IllegalArgumentException("Сообщение длиннее $MAX_MESSAGE_CHARS символов"))
        }
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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

        val report = broadcast(
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
            excludeSelf = true,
        )
        Log.i(
            TAG,
            "group message id=$messageId group=$groupId topic=${topic.id} " +
                "fanout=${report.delivered}/${report.attempted} via=${delivery.name}",
        )
        return Result.success(messageId)
    }

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
        val me = myNodeId().orEmpty()
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
                messageDao.insertMessage(
                    MessageEntity(
                        id = localId,
                        chatId = packet.groupId,
                        senderId = senderId,
                        content = packet.text,
                        timestamp = now,
                        status = "RECEIVED",
                        isFromMe = false,
                        channel = "GROUP",
                        recipientId = me,
                        topicId = packet.topicId,
                    )
                )
                rememberSender(packet.groupId, senderId, packet.senderName, now)
                val topic = groupDao.getTopicById(packet.topicId)
                if (topic != null) {
                    groupDao.registerTopicMessage(topic.id, preview(packet.text), now)
                    groupDao.incrementTopicUnread(topic.id)
                }
                groupDao.updateGroupLastMessage(packet.groupId, preview(packet.text), now)
                groupDao.incrementGroupUnread(packet.groupId)
                registerStats(packet.groupId, packet.topicId, senderId, now)
                Log.i(TAG, "group message in group=${group.id} topic=${packet.topicId} from=$senderId")
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
                        groupDao.refreshMemberCount(packet.groupId)
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
                publishRoster(packet.groupId)
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
                    groupDao.refreshMemberCount(packet.groupId)
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
                groupDao.refreshMemberCount(packet.groupId)
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        }

    /** Шаг 1 для частного вступления: заявка уходит администраторам группы. */
    suspend fun sendJoinRequest(groupId: String, note: String): Result<Unit> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return JoinOutcome.Failed("Идентичность узла ещё не готова")

        // Уже в группе — не шлём повторную заявку.
        val known = target.groupId?.let { groupDao.getGroupById(it) }
        if (known != null && !known.isLeft && groupDao.getMember(known.id, me) != null) {
            return JoinOutcome.Joined(known.id, known.title, known.isChannel)
        }

        // Своя ссылка на своём телефоне: приглашение лежит в локальной базе.
        if (groupDao.getInviteBySlug(target.slug) != null) return joinBySlug(target.slug, note)

        if (!target.isRoutable) {
            return JoinOutcome.Failed(
                "Ссылка старого образца: попросите владельца прислать её заново из раздела «Группы»"
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
        val me = myNodeId() ?: return JoinOutcome.Failed("Идентичность узла ещё не готова")
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
        groupDao.observeMembers(groupId).map { list -> list.map { toMemberSummary(it) } }

    private suspend fun toMemberSummary(m: GroupMemberEntity) = MemberSummary(
        nodeId = m.nodeId,
        displayName = m.displayName,
        role = m.role,
        joinedAtMs = m.joinedAtMs,
        permissions = m.permissions,
        customTitle = m.customTitle,
        isBanned = m.isBanned,
        isMe = m.nodeId == myNodeId(),
    )

    /** Поиск участников по имени или идентификатору узла. */
    suspend fun searchMembers(groupId: String, query: String): List<MemberSummary> {
        val q = query.trim()
        val rows = if (q.isEmpty()) groupDao.getMembers(groupId) else groupDao.searchMembers(groupId, q)
        return rows.map { toMemberSummary(it) }
    }

    suspend fun setAdminRole(groupId: String, nodeId: String, admin: Boolean): Result<Unit> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        publishRoster(groupId)
        return Result.success(Unit)
    }

    suspend fun setAdminPermissions(groupId: String, nodeId: String, mask: Long): Result<Unit> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        publishRoster(groupId)
        return Result.success(Unit)
    }

    /** Разрешения для участников — общая политика группы. */
    suspend fun setMemberPermissions(groupId: String, mask: Long): Result<Unit> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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

    suspend fun leaveGroup(groupId: String): Result<Unit> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        groupDao.deleteGroup(groupId)
    }

    suspend fun stats(groupId: String, days: Int = 7): Result<GroupStats> {
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        publishRoster(group.id)
        delivery.deliver(
            group.id,
            GroupWire.buildJoinDecision(group.id, nodeId, true),
            listOf(nodeId),
        )
        Log.i(TAG, "member admitted group=${group.id} node=$nodeId")
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
        val me = myNodeId() ?: return
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
        val me = myNodeId() ?: return 0
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
        val me = myNodeId() ?: return Result.failure(IllegalStateException("Идентичность узла ещё не готова"))
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
        publishRoster(groupId)
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
                topics.map { GroupWire.TopicEntry(it.id, it.name) },
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
        val me = myNodeId().orEmpty()
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
                groupDao.refreshMemberCount(groupId)
            } else if (rosterAsked.add(groupId + '|' + senderId)) {
                broadcast(groupId, GroupWire.buildRosterRequest(groupId), excludeSelf = true)
            }
            return
        }
        if (known.displayName.isBlank() && senderName.isNotBlank()) {
            groupDao.insertMember(known.copy(displayName = senderName))
        }
    }

    /** После изменения состава рассылаем актуальный список участников. */
    private suspend fun publishRoster(groupId: String) {
        val entries = groupDao.getMembers(groupId).map {
            GroupWire.RosterEntry(it.nodeId, it.displayName, it.role)
        }
        broadcast(groupId, GroupWire.buildRoster(groupId, entries), excludeSelf = true)
    }

    private fun preview(text: String): String =
        text.replace('\n', ' ').take(PREVIEW_CHARS)

    private fun dayKey(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    companion object {
        const val GENERAL_TOPIC_NAME = "General"
        const val MAX_TITLE_CHARS = 128
        const val MAX_ABOUT_CHARS = 512
        const val MAX_TOPIC_CHARS = 128
        const val MAX_MESSAGE_CHARS = 4096
        const val MAX_NOTE_CHARS = 256
        private const val PREVIEW_CHARS = 80
        /** Дальность эпидемии каталога: владелец -> контакты -> их контакты. */
        private const val MAX_DIR_HOPS = 2
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val TAG = "GroupRepository"
    }
}
