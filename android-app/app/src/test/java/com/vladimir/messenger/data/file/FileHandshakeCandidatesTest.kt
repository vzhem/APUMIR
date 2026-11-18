package com.vladimir.messenger.data.file

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileHandshakeCandidatesTest {

    private fun peer(index: Int): String = "pk_" + "%032x".format(index)
    private val me = peer(1)

    @Test fun includesAllUnpinnedDirectContactsAndPrioritizesGroupAdminsBeforeMembers() = runBlocking {
        val directPinned = peer(2)
        val directUnpinnedA = peer(3)
        val directUnpinnedB = peer(4)
        val channelOwner = peer(10)
        val groupAdmin = peer(11)
        val memberA = peer(20)
        val memberB = peer(21)
        val pinned = setOf(directPinned, groupAdmin)

        val selected = FileHandshakeCandidates.select(
            ownNodeId = me,
            directPeerIds = listOf(me, directPinned, directUnpinnedA, directUnpinnedB, directUnpinnedA),
            privilegedGroupPeerIds = listOf(me, channelOwner, groupAdmin, directUnpinnedA),
            regularGroupPeerIds = listOf(memberA, memberB, channelOwner),
            maxGroupHellos = 2,
        ) { id -> id in pinned }

        // Both direct contacts are included; remaining group budget (2) picks owner + first unpinned member.
        assertEquals(listOf(directUnpinnedA, directUnpinnedB, channelOwner, memberA), selected)
    }

    @Test fun boundsGroupAndChannelHandshakesPerSweepAndFiltersInvalidIds() = runBlocking {
        val members = (100..160).map(::peer)
        val selected = FileHandshakeCandidates.select(
            ownNodeId = me,
            directPeerIds = listOf("", "CUSTD_1", "relay|x", me),
            privilegedGroupPeerIds = listOf("bad_id"),
            regularGroupPeerIds = members,
        ) { false }

        assertEquals(FileHandshakeCandidates.MAX_GROUP_HELLOS_PER_SWEEP, selected.size)
        assertEquals(members.take(FileHandshakeCandidates.MAX_GROUP_HELLOS_PER_SWEEP), selected)
        assertTrue(me !in selected)
    }
}
