package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeIdsTest {

    private val realId = "pk_3d94b6417734f69cde63c37cf2d5d2dd"
    private val longId = "pk_" + "ab".repeat(32)

    @Test
    fun realNodeIdsAccepted() {
        assertTrue(NodeIds.isNodeId(realId))
        assertTrue(NodeIds.isNodeId(longId))
        // Тестовые/короткие ключи: 7 знаков после pk_ - минимум (правило LAN-канала).
        assertTrue(NodeIds.isNodeId("pk_deadbee"))
        assertTrue(NodeIds.isNodeId("pk_deadbeef"))
    }

    @Test
    fun strayCallPayloadSenderRejected() {
        // Так выглядело первое поле, когда голый пакет звонка разобрали как sender|msgId|chatId|text.
        assertFalse(NodeIds.isNodeId("APUCALL1"))
        assertFalse(NodeIds.isNodeId("APUCALLHS1"))
        assertFalse(NodeIds.isNodeId("apu-file1"))
        assertFalse(NodeIds.isNodeId(""))
        assertFalse(NodeIds.isNodeId(null))
        assertFalse(NodeIds.isNodeId("unknown"))
    }

    @Test
    fun malformedPkRejected() {
        assertFalse(NodeIds.isNodeId("pk_"))
        assertFalse(NodeIds.isNodeId("pk_short"))
        assertFalse(NodeIds.isNodeId("pk_3d94b641|ab|715"))
        assertFalse(NodeIds.isNodeId("pk_3d94 b641 7734"))
        assertFalse(NodeIds.isNodeId("pk_" + "a".repeat(129)))
        assertFalse(NodeIds.isNodeId("p2pmessenger://add?node=pk_3d94b6417734f69cde63c37cf2d5d2dd"))
    }

    @Test
    fun autoNameMatchesLegacyFormat() {
        assertEquals("Contact f2d5d2dd", NodeIds.autoName(realId))
        assertEquals("Contact APUCALL1", NodeIds.autoName("APUCALL1"))
    }

    @Test
    fun strayAutoContactDetected() {
        // Призрак со скриншота владельца: id «APUCALL1», имя-заглушка от него же.
        assertTrue(NodeIds.isStrayAutoContact("APUCALL1", "Contact APUCALL1"))
        assertTrue(NodeIds.isStrayAutoContact("APUCALL1", "  Contact APUCALL1 "))
    }

    @Test
    fun realAndRenamedContactsKept() {
        // Настоящий контакт с именем-заглушкой - не призрак.
        assertFalse(NodeIds.isStrayAutoContact(realId, NodeIds.autoName(realId)))
        // Переименованный владельцем - тоже не трогаем, даже если id мусорный.
        assertFalse(NodeIds.isStrayAutoContact("APUCALL1", "Вася"))
        // Старые контакты, где вместо id хранится ссылка с pk_: за ними человек.
        val link = "p2pmessenger://add?node=pk_3d94b6417734f69cde63c37cf2d5d2dd"
        assertFalse(NodeIds.isStrayAutoContact(link, NodeIds.autoName(link)))
        assertFalse(NodeIds.isStrayAutoContact("", "Contact "))
        assertFalse(NodeIds.isStrayAutoContact(null, "Contact APUCALL1"))
    }
}
