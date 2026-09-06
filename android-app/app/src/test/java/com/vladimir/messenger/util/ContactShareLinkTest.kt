package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ссылка «поделиться контактом».
 *
 * Главное требование: она должна быть КОРОТКОЙ и без видимого pk_. Прежний
 * вид `p2pmessenger://add?node_id=pk_<40 знаков>&name=…` занимал в переписке
 * три строки и выглядел как техническая ошибка.
 */
class ContactShareLinkTest {
    private val nodeId = "pk_" + "ab".repeat(16)

    @Test
    fun `link is short and hides the raw node id`() {
        val link = ContactShareLink.build(nodeId, "Женя")
        assertTrue(link.startsWith("apu://a/"))
        assertFalse("сырой pk_ не должен попадать в ссылку", link.contains("pk_"))
        // Втрое короче прежнего вида - ради этого всё и делалось.
        assertTrue(link.length < 40)
    }

    @Test
    fun `parser still recovers the same node`() {
        val invite = InviteLinkParser.parse(ContactShareLink.build(nodeId, "Женя"))
        assertTrue(invite != null)
        assertEquals(nodeId, invite!!.nodeId)
    }

    @Test
    fun `nickname travels with the link when known`() {
        val link = ContactShareLink.build(nodeId, "Женя", "zhenya")
        assertTrue(link.endsWith("/zhenya"))
        assertEquals("zhenya", InviteLinkParser.parse(link)?.username)
    }

    @Test
    fun `works without a nickname`() {
        val link = ContactShareLink.build(nodeId, "Женя")
        assertEquals(nodeId, InviteLinkParser.parse(link)?.nodeId)
    }

    @Test
    fun `long display name cannot bloat the link`() {
        // Имя в короткий вид не входит вовсе, поэтому длина от него не зависит.
        val long = "A".repeat(300)
        assertTrue(ContactShareLink.build(nodeId, long).length < 40)
    }

    @Test
    fun `invalid node id rejected`() {
        try {
            ContactShareLink.build("not-a-node", "X")
            fail("accepted invalid node id")
        } catch (_: IllegalArgumentException) {
        }
    }
}
