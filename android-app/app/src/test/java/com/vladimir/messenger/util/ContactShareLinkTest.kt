package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContactShareLinkTest {
    private val nodeId = "pk_" + "ab".repeat(16)

    @Test
    fun buildsTheAppLinkTheParserUnderstands() {
        val link = ContactShareLink.build(nodeId, "Женя")
        assertTrue(link.startsWith("p2pmessenger://add?node_id=$nodeId&name="))
        // InviteLinkParser должен разобрать нашу ссылку
        val invite = InviteLinkParser.parse(link)
        assertTrue(invite != null)
        assertEquals(nodeId, invite!!.nodeId)
    }

    @Test
    fun nameIsTrimmedAndBounded() {
        val long = "A".repeat(300)
        val link = ContactShareLink.build(nodeId, "  $long  ")
        assertTrue(link.length < "p2pmessenger://add?node_id=$nodeId&name=".length + 300)
    }

    @Test
    fun invalidNodeIdRejected() {
        try {
            ContactShareLink.build("not-a-node", "X")
            fail("accepted invalid node id")
        } catch (_: IllegalArgumentException) {
        }
    }
}
