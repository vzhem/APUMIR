package com.vladimir.messenger.data.file

import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.util.NodeIds

/**
 * Selects unpinned peers that should receive a durable `File HELLO` binding
 * handshake on a periodic sweep.
 *
 * Groups and channels contain members who have no 1:1 chat or contact entry
 * with each other. Both `OutgoingPayloadPolicy` (sealed `APUGRP1` envelopes)
 * and pairwise remote push (route registration + sealed `APUPUSH1` epoch
 * exchange) require a pinned exchange binding for every peer pair. Direct
 * contacts and 1:1 chats are always checked in full; group and channel members
 * are prioritised (owners and admins first, then regular members) and capped to
 * [MAX_GROUP_HELLOS_PER_SWEEP] per sweep so joining a large channel never
 * floods the relay queue in one burst.
 */
internal object FileHandshakeCandidates {

    /** Maximum unpinned group/channel peers sent a HELLO in one 5-minute sweep. */
    const val MAX_GROUP_HELLOS_PER_SWEEP = 24

    suspend fun select(
        ownNodeId: String?,
        directPeerIds: Iterable<String>,
        privilegedGroupPeerIds: Iterable<String>,
        regularGroupPeerIds: Iterable<String>,
        maxGroupHellos: Int = MAX_GROUP_HELLOS_PER_SWEEP,
        isPinned: suspend (String) -> Boolean,
    ): List<String> {
        val own = ownNodeId?.trim().orEmpty()
        val seen = HashSet<String>()
        val selected = ArrayList<String>()

        fun acceptId(raw: String): String? {
            val id = raw.trim()
            if (!NodeIds.isNodeId(id) || id == own || !seen.add(id)) return null
            return id
        }

        for (raw in directPeerIds) {
            val id = acceptId(raw) ?: continue
            if (!isPinned(id)) selected.add(id)
        }

        val groupLimit = maxGroupHellos.coerceAtLeast(0)
        if (groupLimit == 0) return selected
        var groupSelected = 0
        for (source in listOf(privilegedGroupPeerIds, regularGroupPeerIds)) {
            for (raw in source) {
                if (groupSelected >= groupLimit) return selected
                val id = acceptId(raw) ?: continue
                if (!isPinned(id)) {
                    selected.add(id)
                    groupSelected++
                }
            }
        }
        return selected
    }

    /** Reads direct, group and channel candidates from Room without failing the sweep. */
    suspend fun collectUnpinned(
        ownNodeId: String?,
        chatRepository: ChatRepository,
        contactDao: ContactDao,
        groupDao: GroupDao,
        peerStore: FileExchangePeerStore,
    ): List<String> {
        val fromChats = runCatching { chatRepository.getAllContactIds() }.getOrDefault(emptyList())
        val fromContacts = runCatching { contactDao.observeAllContactsOnce().map { it.id } }
            .getOrDefault(emptyList())
        val privileged = LinkedHashSet<String>()
        val regular = LinkedHashSet<String>()
        val communities = runCatching { groupDao.getGroups() + groupDao.getChannels() }
            .getOrDefault(emptyList())
        for (group in communities) {
            if (group.ownerId.isNotBlank()) privileged.add(group.ownerId)
            val members = runCatching { groupDao.getMembers(group.id) }.getOrDefault(emptyList())
            for (member in members) {
                if (member.isBanned || member.nodeId.isBlank()) continue
                if (GroupRole.isAdminOrOwner(member.role)) {
                    privileged.add(member.nodeId)
                } else {
                    regular.add(member.nodeId)
                }
            }
        }
        return select(
            ownNodeId = ownNodeId,
            directPeerIds = fromChats + fromContacts,
            privilegedGroupPeerIds = privileged,
            regularGroupPeerIds = regular,
        ) { peerId ->
            runCatching { peerStore.bindingFor(peerId) != null }.getOrDefault(true)
        }
    }
}
