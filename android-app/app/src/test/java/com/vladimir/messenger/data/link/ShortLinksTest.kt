package com.vladimir.messenger.data.link

import com.vladimir.messenger.data.group.GroupInviteLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Короткие ссылки для пересылки: владелец (2026-09-10) просил, чтобы в
 * пересланной ссылке не было видно ни канала, ни владельца, ни записи.
 */
class ShortLinksTest {

    @Test
    fun `short link carries only the code`() {
        val link = ShortLinks.build("kixtW77kJx")
        assertEquals("https://" + GroupInviteLinks.WEB_HOST + "/s/kixtW77kJx", link)
        assertFalse(link.contains("slug="))
        assertFalse(link.contains("pk_"))
        assertEquals("kixtW77kJx", ShortLinks.codeOf(link))
        assertTrue(ShortLinks.isShortLink(link))
    }

    /** Ссылку копируют вместе с текстом сообщения - код всё равно находится. */
    @Test
    fun `code is found inside forwarded text`() {
        val text = "Открыть в APU:\nhttps://" + GroupInviteLinks.WEB_HOST + "/s/kixtW77kJx Скачать APU"
        assertEquals("kixtW77kJx", ShortLinks.codeOf(text))
        val glued = "https://" + GroupInviteLinks.WEB_HOST + "/s/kixtW77kJxСкачать"
        assertEquals("kixtW77kJx", ShortLinks.codeOf(glued))
    }

    /** Официальный домен принимается наравне с текущим хостом. */
    @Test
    fun `official host is accepted`() {
        assertEquals("PhLe3W8FBU", ShortLinks.codeOf("https://" + GroupInviteLinks.OFFICIAL_HOST + "/s/PhLe3W8FBU"))
    }

    @Test
    fun `foreign host and long link are not short links`() {
        assertNull(ShortLinks.codeOf("https://example.com/s/kixtW77kJx"))
        assertNull(ShortLinks.codeOf("https://" + GroupInviteLinks.WEB_HOST + "/i?slug=Abcdefghijkmnopq&g=grp-1"))
        assertNull(ShortLinks.codeOf("https://" + GroupInviteLinks.WEB_HOST + "/s/"))
        assertNull(ShortLinks.codeOf("https://" + GroupInviteLinks.WEB_HOST + "/s/abc"))
        assertNull(ShortLinks.codeOf(""))
        assertNull(ShortLinks.codeOf(null))
        assertFalse(ShortLinks.isShortLink("p2pmessenger://group?slug=Abcdefghijkmnopq"))
    }

    @Test
    fun `code format`() {
        assertTrue(ShortLinks.isValidCode("kixtW77kJx"))
        assertTrue(ShortLinks.isValidCode("AZXkjwjK9WAZXkjwjK9W"))
        assertFalse(ShortLinks.isValidCode("abc"))
        assertFalse(ShortLinks.isValidCode("kixtW77kJx/../x"))
        assertFalse(ShortLinks.isValidCode(null))
        assertFalse(ShortLinks.isValidCode(""))
    }

    /** Сервис вернул цель: открываем только то, что строит само приложение. */
    @Test
    fun `only own link forms are allowed as targets`() {
        assertTrue(ShortLinks.isAllowedTarget("p2pmessenger://group?slug=Abcdefghijkmnopq&g=grp-1&o=pk_owner&p=t&c=1"))
        assertTrue(ShortLinks.isAllowedTarget("apu://a/PTGP7-vX5eOuHhYoWYK2iA/vzhem"))
        assertTrue(ShortLinks.isAllowedTarget("p2pmessenger://add?node_id=pk_0123&name=Me"))
        assertFalse(ShortLinks.isAllowedTarget("https://evil.example/"))
        assertFalse(ShortLinks.isAllowedTarget("intent://x#Intent;end"))
        assertFalse(ShortLinks.isAllowedTarget("p2pmessenger://group?slug=A bcdefghijkmnopq"))
        assertFalse(ShortLinks.isAllowedTarget(""))
        assertFalse(ShortLinks.isAllowedTarget(null))
    }

    /** Полная ссылка, которую прячем за кодом, разбирается обратно как приглашение. */
    @Test
    fun `deep link round-trips through GroupInviteLinks`() {
        val web = GroupInviteLinks.buildWebLink(
            slug = "Abcdefghijkmnopq",
            groupId = "grp-1",
            ownerId = "pk_owner",
            isChannel = true,
            postTopicId = "topic-77",
        )
        val deep = GroupInviteLinks.toDeepLink(web)
        assertEquals(
            "p2pmessenger://group?slug=Abcdefghijkmnopq&g=grp-1&o=pk_owner&p=topic-77&c=1",
            deep,
        )
        assertTrue(ShortLinks.isAllowedTarget(deep))
        val target = GroupInviteLinks.parseTarget(deep)!!
        assertEquals("topic-77", target.postTopicId)
        assertTrue(target.isChannel)
        assertEquals(web, GroupInviteLinks.toWebLink(deep))
        assertNull(GroupInviteLinks.toDeepLink("https://example.com/"))
    }
}
