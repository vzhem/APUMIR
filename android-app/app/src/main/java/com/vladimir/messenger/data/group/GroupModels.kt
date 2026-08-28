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

data class InviteSummary(
    val slug: String,
    val groupId: String,
    val link: String,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val maxUses: Int,
    val useCount: Int,
    val revoked: Boolean,
    val requestApproval: Boolean,
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
)

/** Результат попытки вступить по ссылке-приглашению. */
sealed class JoinOutcome {
    /** Сразу добавлен в участники (публичная группа или ссылка без одобрения). */
    data class Joined(val groupId: String, val title: String) : JoinOutcome()

    /** Группа частная: создана заявка, ждёт решения администратора. */
    data class RequestSent(val groupId: String, val title: String) : JoinOutcome()

    data class Failed(val reason: String) : JoinOutcome()
}
