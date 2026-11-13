package com.vladimir.messenger.data.repository

import android.content.Context
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.service.IncomingNotificationGate
import com.vladimir.messenger.service.LocalMessageNotifications

/**
 * Serialize group/topic reads and local notification posts in this process.
 * The Room read and pending-flag changes are transactional inside the DAO;
 * Android cancellation follows them while the same gate is held. This is not
 * a cross-process, restore, delete or deferred-reveal lock.
 */
internal class IncomingGroupNotificationReads(
    private val groups: GroupDao,
    private val cancelGroup: (String) -> Unit,
    private val cancelTopic: (String, String) -> Unit,
) {
    companion object {
        fun forContext(groups: GroupDao, context: Context?) = IncomingGroupNotificationReads(
            groups,
            cancelGroup = { groupId -> context?.let { LocalMessageNotifications.cancelChat(it, groupId) } },
            cancelTopic = { groupId, topicId -> context?.let { LocalMessageNotifications.cancelTopic(it, groupId, topicId) } },
        )
    }

    /** null means the topic does not belong to this group; otherwise its prior unread state. */
    suspend fun topic(groupId: String, topicId: String): Boolean? = IncomingNotificationGate.run {
        topicLocked(groupId, topicId)
    }

    suspend fun group(groupId: String): Boolean = IncomingNotificationGate.run { groupLocked(groupId) }

    /** A mirror read of one topic must not clear notifications for other topics. */
    suspend fun mirror(groupId: String, topicId: String) = IncomingNotificationGate.run {
        if (topicId.isBlank()) {
            groupLocked(groupId)
        } else if (topicLocked(groupId, topicId) != null) {
            val group = groups.getGroupById(groupId)
            val unread = groups.sumTopicUnread(groupId)
            if (group != null && group.unreadCount != unread) groups.setGroupUnread(groupId, unread)
        }
        Unit
    }

    private suspend fun topicLocked(groupId: String, topicId: String): Boolean? {
        val topic = groups.getTopicById(topicId) ?: return null
        if (topic.groupId != groupId) return null
        groups.markTopicRead(groupId, topicId)
        cancelTopic(groupId, topicId)
        return topic.unreadCount > 0
    }

    private suspend fun groupLocked(groupId: String): Boolean {
        if (groups.getGroupById(groupId) == null) return false
        groups.markGroupRead(groupId)
        cancelGroup(groupId)
        return true
    }
}
