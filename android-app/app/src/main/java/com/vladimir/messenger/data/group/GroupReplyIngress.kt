package com.vladimir.messenger.data.group

import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao

/**
 * Keep early group replies separate by local identity, group and claimed sender.
 * The database update must compare that sender with the stored parent author and
 * reject conflicting quotes. This is only a scope check: it does not prove the
 * transport's sender identity, or make the in-memory buffer durable.
 */
internal class GroupReplyIngress(
    private val localId: () -> String?,
    private val isMember: suspend (String, String) -> Boolean,
    private val parentGroup: suspend (String) -> String?,
    private val apply: suspend (String, GroupWire.Packet.Reply) -> Unit,
    capacity: Int,
    ttlMs: Long,
    clock: () -> Long,
) {
    private data class Pending(val sender: String, val packet: GroupWire.Packet.Reply)
    private val pending = GroupReplyBuffer<Pending>(capacity, ttlMs, clock)

    suspend fun receive(sender: String, packet: GroupWire.Packet.Reply) {
        val owner = localId()?.takeIf { it.isNotBlank() } ?: return
        if (sender.isBlank() || !isMember(packet.groupId, owner)) return
        if (parentGroup(packet.messageId) != packet.groupId) {
            pending.put(owner, packet.groupId, packet.messageId, sender, Pending(sender, packet))
            // A parent can be inserted and drain the buffer between our first
            // lookup and put. Recheck so that ordering does not strand a reply.
            if (localId() == owner && parentGroup(packet.messageId) == packet.groupId) {
                drain(owner, packet.groupId, packet.messageId)
            }
        } else if (localId() == owner) {
            apply(sender, packet)
        }
    }

    suspend fun parentInserted(groupId: String, messageId: String) {
        val owner = localId()?.takeIf { it.isNotBlank() } ?: return
        drain(owner, groupId, messageId)
    }

    private suspend fun drain(owner: String, groupId: String, messageId: String) {
        if (localId() != owner || !isMember(groupId, owner) || localId() != owner) return
        for (entry in pending.take(owner, groupId, messageId)) {
            if (localId() != owner) return
            apply(entry.sender, entry.packet)
        }
    }
}

internal fun roomGroupReplyIngress(
    groupDao: GroupDao,
    messageDao: MessageDao,
    myId: () -> String?,
    capacity: Int,
    ttlMs: Long,
    clock: () -> Long,
): GroupReplyIngress = GroupReplyIngress(
    localId = myId,
    isMember = { groupId, owner -> groupDao.getMember(groupId, owner) != null },
    parentGroup = { id -> messageDao.getMessageById(id)?.chatId },
    apply = { sender, packet ->
        messageDao.applyGroupReply(
            groupId = packet.groupId, messageId = packet.messageId, senderId = sender,
            replyToId = packet.replyToId, replyAuthor = packet.replyAuthor,
            replyText = GroupWire.replyPreview(packet.replyText),
        )
    },
    capacity = capacity,
    ttlMs = ttlMs,
    clock = clock,
)
