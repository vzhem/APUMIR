package com.vladimir.messenger.data.group

/** Доменные модели групп. UI работает с ними, а не с Room-сущностями. */

data class GroupSummary(
    val id: String,
    val title: String,
    val about: String,
    val ownerId: String,
    val isPublic: Boolean,
    val topicsEnabled: Boolean,
    /** Канал, а не группа: посты пишут администраторы, обсуждение в комментариях. */
    val isChannel: Boolean = false,
    /** Маска разрешений обычных участников — общая политика группы. */
    val memberPermissions: Long,
    val memberCount: Int,
    val unreadCount: Int,
    val lastMessagePreview: String?,
    val lastMessageAtMs: Long?,
    val myRole: String,
    val pendingRequests: Int,
    /**
     * р250: медленный режим - сколько секунд участник ждёт между своими
     * сообщениями; 0 - режим выключен. Владельцу и администраторам режим не
     * мешает. В канале относится к комментариям под постами.
     */
    val slowModeSeconds: Int = 0,
    val quietHours: String = "",
    val memberListVisible: Boolean = true,
    /**
     * Красная кнопка SOS: показывать ли её в группе, сколько тревожных
     * сообщений уходит за одно нажатие (3..5) и кто вправе нажимать
     * (GroupSos.PRESS_*). Для каналов не используется.
     */
    val sosEnabled: Boolean = true,
    val sosMessageCount: Int = GroupSos.DEFAULT_COUNT,
    val sosPressRole: String = GroupSos.PRESS_ALL,
    /** Личная пауза уведомлений группы или канала. */
    val mutedUntilMs: Long = 0L,
    val wallpaper: String = "",
    val wallpaperRevision: String = "",
)

data class TopicSummary(
    val id: String,
    val groupId: String,
    val name: String,
    val iconEmoji: String,
    /** Сколько всего сообщений накопилось в теме. */
    val messageCount: Int,
    /** Сколько из них не прочитано. */
    val unreadCount: Int,
    val lastMessagePreview: String?,
    val lastMessageAtMs: Long?,
    val isClosed: Boolean,
    val isGeneral: Boolean,
)

data class MemberSummary(
    val nodeId: String,
    val displayName: String,
    val role: String,
    val joinedAtMs: Long,
    val permissions: Long,
    val customTitle: String,
    val isBanned: Boolean,
    val isMe: Boolean,
)

data class JoinRequestSummary(
    val groupId: String,
    val nodeId: String,
    val displayName: String,
    val note: String,
    val requestedAtMs: Long,
)

/** Строка «Моих заявок»: всё, что известно абоненту о заявке и группе. */
data class MyJoinRequestSummary(
    val groupId: String,
    val title: String,
    val isChannel: Boolean,
    val memberCount: Int,
    val ownerName: String,
    val groupCreatedAtMs: Long,
    val note: String,
    val requestedAtMs: Long,
    /** PENDING | APPROVED | REJECTED - см. MyJoinRequestStore. */
    val status: String,
    val decidedAtMs: Long,
    val ownerReceivedAtMs: Long,
    /** Заявка ещё в очереди телефона и досылается. */
    val queued: Boolean,
    val queueAttempts: Int,
    val lastTryMs: Long,
    /** Когда очередь перестанет досылать заявку (0 - не в очереди). */
    val expiresAtMs: Long,
)

data class InviteSummary(
    val slug: String,
    val groupId: String,
    /** Основная ссылка p2pmessenger://group?… - для QR-кода и разбора без сети. */
    val link: String,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val maxUses: Int,
    val useCount: Int,
    val revoked: Boolean,
    val requestApproval: Boolean,
    /**
     * Ссылка для пересылки наружу: короткая https://<хост>/s/<код> без
     * идентификаторов группы и владельца; если сервис коротких ссылок
     * недоступен - длинная веб-ссылка. По умолчанию совпадает с [link].
     */
    val shareLink: String = link,
) {
    val isActive: Boolean
        get() = !revoked &&
            (expiresAtMs == null || expiresAtMs > System.currentTimeMillis()) &&
            (maxUses == 0 || useCount < maxUses)
}

data class GroupStatDay(
    val dayKey: String,
    val messageCount: Int,
    val senderCount: Int,
)

data class GroupStats(
    val groupId: String,
    val memberCount: Int,
    val adminCount: Int,
    val topicCount: Int,
    val pendingRequests: Int,
    val totalMessages: Int,
    val last7Days: List<GroupStatDay>,
    val perTopic: Map<String, Int>,
    /** Уникальные авторы за период, а не сумма авторов отдельных дней. */
    val activeSenders7Days: Int = 0,
    /** Публикации канала отдельно от комментариев и служебных частей фото. */
    val channelPosts: List<ChannelPostStat> = emptyList(),
    val publicationsLast7Days: List<GroupStatDay> = emptyList(),
)

/** Счётчики сохранённой публикации, в тех же единицах, что и в ленте канала. */
data class ChannelPostStat(
    val topicId: String,
    val publishedAtMs: Long,
    val authorId: String,
    val commentCount: Int,
    val viewCount: Int,
)

/** Результат попытки вступить по ссылке-приглашению. */
sealed class JoinOutcome {
    /** Сразу добавлен в участники (публичная группа или ссылка без одобрения). */
    data class Joined(
        val groupId: String,
        val title: String,
        val isChannel: Boolean = false,
    ) : JoinOutcome()

    /**
     * Заявка ушла владельцу. `needsApproval` - из ссылки: по нему экран пишет
     * правду, заявка это или вход сразу.
     */
    data class RequestSent(
        val groupId: String,
        val title: String,
        val isChannel: Boolean = false,
        val needsApproval: Boolean = true,
    ) : JoinOutcome()

    data class Failed(val reason: String) : JoinOutcome()
}
