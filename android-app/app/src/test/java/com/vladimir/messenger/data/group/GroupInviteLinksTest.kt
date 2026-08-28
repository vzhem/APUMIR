package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteLinksTest {

    /** Признаки канала и одобрения доезжают во вступающий телефон вместе со ссылкой. */
    @Test
    fun linkCarriesChannelAndApprovalFlags() {
        val link = GroupInviteLinks.build(
            slug = "AbCdEf2345678901",
            groupId = "grp-1",
            ownerId = "pk_owner",
            isChannel = true,
            requestApproval = true,
        )
        val target = GroupInviteLinks.parseTarget(link)
        assertNotNull(target)
        target!!
        assertTrue("канал должен остаться каналом", target.isChannel)
        assertTrue("одобрение должно быть видно из ссылки", target.needsApproval)
    }

    /** Старая ссылка без признаков: не канал, тип одобрения неизвестен. */
    @Test
    fun linkWithoutFlagsIsNotChannel() {
        val target = GroupInviteLinks.parseTarget(
            "p2pmessenger://group?slug=AbCdEf2345678901&g=grp-1&o=pk_owner"
        )
        assertNotNull(target)
        target!!
        assertFalse(target.isChannel)
        assertFalse(target.needsApproval)
    }

    @Test
    fun generatedSlugsAreValidAndUnique() {
        val seen = HashSet<String>()
        repeat(200) {
            val slug = GroupInviteLinks.newSlug()
            assertTrue("slug $slug не прошёл валидацию", GroupInviteLinks.isValidSlug(slug))
            seen.add(slug)
        }
        assertEquals("совпадения slug на 200 попытках", 200, seen.size)
    }

    @Test
    fun appLinkRoundTrip() {
        val slug = GroupInviteLinks.newSlug()
        val link = GroupInviteLinks.build(slug)
        assertTrue(link.startsWith(GroupInviteLinks.APP_LINK_PREFIX))
        assertEquals(slug, GroupInviteLinks.parseSlug(link))
    }

    @Test
    fun fullLinkCarriesGroupAndOwner() {
        // Без id группы и адреса владельца вступающий телефон не знает, у кого
        // спрашивать группу, — ссылка обязана нести их в себе.
        val slug = GroupInviteLinks.newSlug()
        val link = GroupInviteLinks.build(slug, "grp-1", "pk_owner")
        val target = GroupInviteLinks.parseTarget(link)
        assertTrue(target != null)
        assertEquals(slug, target!!.slug)
        assertEquals("grp-1", target.groupId)
        assertEquals("pk_owner", target.ownerId)
        assertTrue(target.isRoutable)
        assertEquals(slug, GroupInviteLinks.parseSlug(link))
    }

    @Test
    fun linkIsFoundInsideMessengerMessage() {
        // MAX и подобные переносят длинную ссылку по словам, а скопировать
        // только её нельзя: копируется всё сообщение, с разрывами внутри ссылки.
        val slug = GroupInviteLinks.newSlug()
        val link = GroupInviteLinks.build(slug, "grp-1", "pk_owner")
        val broken = link.replace("&g=", "\n&g=").replace("&o=", "\n&o=")
        val message = "Присоединяйся к группе «Работа» в APU.\n\n" +
            "Открой ссылку или вставь её в APU:\n" + broken + "\n\n" +
            "Скачать APU:\nhttps://github.com/vzhem/APUMIR/releases/latest"
        val target = GroupInviteLinks.parseTarget(message)
        assertTrue("ссылка не найдена в тексте сообщения", target != null)
        assertEquals(slug, target!!.slug)
        assertEquals("grp-1", target.groupId)
        assertEquals("pk_owner", target.ownerId)
    }

    @Test
    fun cyrillicTailDoesNotStickToLink() {
        // Если мессенджер склеил ссылку со следующим русским словом, оно не
        // должно попасть в адрес владельца.
        val slug = GroupInviteLinks.newSlug()
        val glued = GroupInviteLinks.build(slug, "grp-1", "pk_owner") + "СкачатьAPU"
        val target = GroupInviteLinks.parseTarget(glued)
        assertTrue(target != null)
        assertEquals("pk_owner", target!!.ownerId)
    }

    @Test
    fun oldLinkWithoutOwnerIsNotRoutable() {
        val slug = GroupInviteLinks.newSlug()
        val target = GroupInviteLinks.parseTarget(GroupInviteLinks.build(slug))
        assertTrue(target != null)
        assertEquals(slug, target!!.slug)
        assertNull(target.groupId)
        assertNull(target.ownerId)
        assertFalse(target.isRoutable)
    }

    @Test
    fun bareSlugIsAcceptedButNotRoutable() {
        val slug = GroupInviteLinks.newSlug()
        val target = GroupInviteLinks.parseTarget(slug)
        assertTrue(target != null)
        assertEquals(slug, target!!.slug)
        assertFalse(target.isRoutable)
    }

    @Test
    fun shortLinkRoundTrip() {
        val slug = GroupInviteLinks.newSlug()
        assertEquals(slug, GroupInviteLinks.parseSlug(GroupInviteLinks.SHORT_LINK_PREFIX + slug))
    }

    @Test
    fun telegramBootstrapLinkRoundTrip() {
        val slug = GroupInviteLinks.newSlug()
        val link = GroupInviteLinks.buildTelegramLink(slug)
        assertTrue(link.contains(GroupInviteLinks.TELEGRAM_BOT_USERNAME))
        assertEquals(slug, GroupInviteLinks.parseSlug(link))
    }

    @Test
    fun contactInvitesAreNotGroupInvites() {
        assertNull(GroupInviteLinks.parseSlug("p2pmessenger://add?node_id=pk_40d2401a3ac3029c88cc3d1d3bc62a95"))
        assertNull(GroupInviteLinks.parseSlug("p2p://key/somepublickey"))
        assertNull(
            GroupInviteLinks.parseSlug(
                "https://t.me/p2p_messenger_relay_bot?start=add_pk_40d2401a3ac3029c88cc3d1d3bc62a95"
            )
        )
    }

    @Test
    fun unrelatedInputIsRejected() {
        assertNull(GroupInviteLinks.parseSlug(null))
        assertNull(GroupInviteLinks.parseSlug("   "))
        assertNull(GroupInviteLinks.parseSlug("просто текст"))
        assertNull(GroupInviteLinks.parseSlug("https://example.com/group"))
        assertNull(GroupInviteLinks.parseSlug("ftp://group/abcdefgh"))
    }

    @Test
    fun malformedSlugsAreRejected() {
        assertFalse(GroupInviteLinks.isValidSlug(null))
        assertFalse(GroupInviteLinks.isValidSlug(""))
        assertFalse("слишком короткий", GroupInviteLinks.isValidSlug("abc"))
        assertFalse("недопустимые символы", GroupInviteLinks.isValidSlug("slug_with_underscore1"))
        assertFalse("слишком длинный", GroupInviteLinks.isValidSlug("a".repeat(33)))
        assertNull(GroupInviteLinks.parseSlug(GroupInviteLinks.APP_LINK_PREFIX + "abc"))
    }

    @Test
    fun groupHostIsRequiredInAppLink() {
        assertNull(GroupInviteLinks.parseSlug("p2pmessenger://channel?slug=Abcdefghijkmnopq"))
        assertEquals(
            "Abcdefghijkmnopq",
            GroupInviteLinks.parseSlug("p2pmessenger://GROUP?slug=Abcdefghijkmnopq"),
        )
    }

    @Test
    fun inviteSummaryActiveRules() {
        val now = System.currentTimeMillis()
        val base = InviteSummary(
            slug = "Abcdefghijkmnopq",
            groupId = "g",
            link = "x",
            createdAtMs = now,
            expiresAtMs = null,
            maxUses = 0,
            useCount = 0,
            revoked = false,
            requestApproval = false,
        )
        assertTrue(base.isActive)
        assertFalse(base.copy(revoked = true).isActive)
        assertFalse(base.copy(expiresAtMs = now - 1).isActive)
        assertTrue(base.copy(expiresAtMs = now + 60_000).isActive)
        assertFalse(base.copy(maxUses = 5, useCount = 5).isActive)
        assertTrue(base.copy(maxUses = 5, useCount = 4).isActive)
        assertNotEquals(base.isActive, base.copy(revoked = true).isActive)
    }
}
