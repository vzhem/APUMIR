package com.vladimir.messenger.data.contactreset

import com.vladimir.messenger.data.group.GroupWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResetWireTest {
    private val node = "pk_${"ab".repeat(32)}"
    private val token = ByteArray(128) { (it % 251).toByte() }
    private val binding = ByteArray(96) { (255 - it % 256).toByte() }

    private fun sample(name: String = "Телефон 1") =
        ContactResetWire.build(ContactResetWire.EPOCH, node, name, token, binding)

    @Test
    fun roundTripPreservesPublicFields() {
        val wire = sample("Сервер 5")
        assertTrue(ContactResetWire.isPacket(wire))
        val hello = ContactResetWire.parse(wire)
        assertTrue(hello != null)
        assertEquals(ContactResetWire.EPOCH, hello!!.epoch)
        assertEquals(node, hello.nodeId)
        assertEquals("Сервер 5", hello.displayName)
        assertArrayEquals(token, hello.token)
        assertArrayEquals(binding, hello.binding)
        assertFalse(wire.contains('='))
        assertTrue(wire.length <= ContactResetWire.MAX_WIRE_CHARS)
    }

    @Test
    fun oldGroupNamespaceSwallowsUnknownKindInsteadOfChatText() {
        val wire = sample()
        assertTrue(GroupWire.isGroupPacket(wire))
        assertNull(GroupWire.parse(wire))
    }

    @Test
    fun malformedCardsFailClosed() {
        assertNull(ContactResetWire.parse(null))
        assertNull(ContactResetWire.parse("Привет"))
        assertNull(ContactResetWire.parse(ContactResetWire.PREFIX))
        assertNull(ContactResetWire.parse(sample() + "|extra"))
        assertNull(ContactResetWire.parse(sample().replace("|${ContactResetWire.EPOCH}|", "|2|")))
        assertNull(ContactResetWire.parse(sample().replace(node, "pk_short")))
        assertNull(ContactResetWire.parse(sample() + "!"))
        assertNull(ContactResetWire.parse("APUGRP1|dir1|" + "x".repeat(ContactResetWire.MAX_WIRE_CHARS)))
    }

    @Test
    fun oversizedFieldsAreRejectedAtBuildTime() {
        assertThrowsIllegalArgument { ContactResetWire.build(2, node, "", token, binding) }
        assertThrowsIllegalArgument { ContactResetWire.build(ContactResetWire.EPOCH, "contact", "", token, binding) }
        assertThrowsIllegalArgument { ContactResetWire.build(ContactResetWire.EPOCH, node, "x".repeat(161), token, binding) }
        assertThrowsIllegalArgument { ContactResetWire.build(ContactResetWire.EPOCH, node, "", ByteArray(0), binding) }
        assertThrowsIllegalArgument { ContactResetWire.build(ContactResetWire.EPOCH, node, "", token, ByteArray(513)) }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
