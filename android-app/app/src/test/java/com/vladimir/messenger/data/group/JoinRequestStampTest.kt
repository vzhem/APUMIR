package com.vladimir.messenger.data.group

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JoinRequestStampTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun requestTimeSurvivesWireRoundTrip() {
        val parsed = GroupWire.parse(
            GroupWire.buildJoinRequest("g", "Аня", "пустите", "AbCdEf2345678901", requestedAtMs = 1_700_000_000_000L)
        ) as GroupWire.Packet.JoinRequest
        assertEquals(1_700_000_000_000L, parsed.requestedAtMs)
        assertEquals("AbCdEf2345678901", parsed.slug)
    }

    @Test
    fun requestWithoutTimeStillParses() {
        val parsed = GroupWire.parse(GroupWire.buildJoinRequest("g", "Аня", "пустите", "slug")) as GroupWire.Packet.JoinRequest
        assertEquals(0L, parsed.requestedAtMs)
    }

    @Test
    fun outboxKeepsOneEntryPerGroupAndSurvivesReopen() {
        val outbox = GroupJoinOutbox(File(folder.root, "outbox"))
        outbox.put(GroupJoinOutbox.Entry("g1", "Аня", "", "s", listOf("owner"), 1_000L, 0L, 0))
        outbox.put(GroupJoinOutbox.Entry("g1", "Аня", "новая", "s", listOf("owner"), 2_000L, 0L, 0))
        outbox.put(GroupJoinOutbox.Entry("g2", "Аня", "", "", listOf("admin1", "admin2"), 1_000L, 0L, 0))

        val reopened = GroupJoinOutbox(File(folder.root, "outbox"))
        val all = reopened.all().associateBy { it.groupId }
        assertEquals(2, all.size)
        assertEquals("новая", all.getValue("g1").note)
        assertEquals(listOf("admin1", "admin2"), all.getValue("g2").recipients)
    }

    @Test
    fun removingOneGroupLeavesOthers() {
        val outbox = GroupJoinOutbox(File(folder.root, "outbox"))
        outbox.put(GroupJoinOutbox.Entry("g1", "Аня", "", "", listOf("owner"), 1_000L, 0L, 0))
        outbox.put(GroupJoinOutbox.Entry("g2", "Аня", "", "", listOf("owner"), 1_000L, 0L, 0))
        outbox.remove("g1")
        assertNull(outbox.all().firstOrNull { it.groupId == "g1" })
        assertTrue(outbox.all().any { it.groupId == "g2" })
    }

    @Test
    fun dueSkipsRecentTriesAndDropsExpired() {
        val outbox = GroupJoinOutbox(File(folder.root, "outbox"))
        outbox.put(GroupJoinOutbox.Entry("fresh", "Аня", "", "", listOf("owner"), 1_000L, 0L, 0))
        outbox.put(GroupJoinOutbox.Entry("old", "Аня", "", "", listOf("owner"), 0L, 0L, 0))
        outbox.markTried("fresh", 5_000L)
        val due = outbox.due(now = 5_500L, minIntervalMs = 1_000L, ttlMs = 10_000L)
        // «fresh» пробовали 500 мс назад - ждёт; «old» не пробовали никогда - пора.
        assertEquals(listOf("old"), due.map { it.groupId })
        assertEquals(setOf("fresh", "old"), outbox.all().map { it.groupId }.toSet())
    }
}
