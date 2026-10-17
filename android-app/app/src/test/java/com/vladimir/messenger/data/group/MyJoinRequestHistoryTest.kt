package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MyJoinRequestHistoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun cardFromOwnerRoundTripsThroughWire() {
        val parsed = GroupWire.parse(
            GroupWire.buildJoinInfo("g1", "me", "Клуб «Ёж»", 42, true, "Анна", 1_690_000_000_000L)
        ) as GroupWire.Packet.JoinInfo
        assertEquals("g1", parsed.groupId)
        assertEquals("me", parsed.requesterId)
        assertEquals("Клуб «Ёж»", parsed.title)
        assertEquals(42, parsed.memberCount)
        assertTrue(parsed.isChannel)
        assertEquals("Анна", parsed.ownerName)
        assertEquals(1_690_000_000_000L, parsed.createdAtMs)
    }

    @Test
    fun entryKeepsEveryFieldAcrossReload() {
        val store = MyJoinRequestStore(folder.root)
        val entry = MyJoinRequestStore.Entry(
            groupId = "g1",
            title = "Тест | с разделителем",
            isChannel = false,
            memberCount = 7,
            ownerName = "Владелец",
            groupCreatedAtMs = 10L,
            note = "пустите, пожалуйста",
            requestedAtMs = 20L,
            status = MyJoinRequestStore.PENDING,
            decidedAtMs = 0L,
            ownerReceivedAtMs = 30L,
            recipients = listOf("a", "b"),
        )
        store.upsert(entry)
        assertEquals(entry, MyJoinRequestStore(folder.root).get("g1"))
    }

    @Test
    fun updateChangesOnlyTheRequestedGroup() {
        val store = MyJoinRequestStore(folder.root)
        store.upsert(MyJoinRequestStore.Entry("g1", requestedAtMs = 1L, recipients = listOf("o")))
        store.upsert(MyJoinRequestStore.Entry("g2", requestedAtMs = 2L, recipients = listOf("o")))
        store.update("g1") { it.copy(status = MyJoinRequestStore.APPROVED, decidedAtMs = 5L) }
        assertEquals(MyJoinRequestStore.APPROVED, store.get("g1")?.status)
        assertEquals(MyJoinRequestStore.PENDING, store.get("g2")?.status)
    }

    @Test
    fun removedRequestIsGone() {
        val store = MyJoinRequestStore(folder.root)
        store.upsert(MyJoinRequestStore.Entry("g1", requestedAtMs = 1L))
        store.remove("g1")
        assertNull(store.get("g1"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun brokenFileIsIgnoredNotCrashing() {
        folder.newFile("my-requests.txt").writeText("не та шапка\nxxx|yyy")
        assertTrue(MyJoinRequestStore(folder.root).all().isEmpty())
    }
}
