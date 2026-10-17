package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteCardSenderTest {
    @Test
    fun groupAndChannelInvitationsKeepTheirJoinActionType() {
        val groupCard = GroupInviteCardSender.build(
            isChannel = false,
            title = "Соседи",
            about = "Общий чат дома",
            link = "https://example.org/group-invite",
        )
        val channelCard = GroupInviteCardSender.build(
            isChannel = true,
            title = "Новости дома",
            about = "Только объявления",
            link = "https://example.org/channel-invite",
        )

        assertFalse(requireNotNull(GroupInviteCardSender.parseCard(groupCard)).isChannel)
        assertTrue(requireNotNull(GroupInviteCardSender.parseCard(channelCard)).isChannel)
    }

    @Test
    fun closedGroupWithShortLinkCarriesDetailsWithoutNetwork() {
        val about = "Описание группы: " + "подробности ".repeat(40) + "\nПравила участия"
        val text = GroupInviteCardSender.build(
            isChannel = false,
            title = "БВ",
            about = about,
            link = "https://apumir.app/s/Abcdefgh",
            isPublic = false,
            needsApproval = true,
        )
        val card = requireNotNull(GroupInviteCardSender.parseCard(text))
        assertEquals("БВ", card.title)
        assertEquals(about, card.about)
        assertEquals(false, card.isPublic)
        assertEquals(true, card.needsApproval)
        assertFalse(card.isChannel)
        assertNull(card.apkLink)
    }

    @Test
    fun openGroupCanBeJoinedDirectlyAndEmptyDescriptionIsPreserved() {
        val card = requireNotNull(GroupInviteCardSender.parseCard(GroupInviteCardSender.build(
            false, "Двор", "", "https://apumir.app/s/Abcdefgh",
            isPublic = true, needsApproval = false,
        )))
        assertEquals("", card.about)
        assertEquals(true, card.isPublic)
        assertEquals(false, card.needsApproval)
    }

    @Test
    fun privateChannelCanHaveAnInvitationWithoutApproval() {
        val text = GroupInviteCardSender.build(
            true, "Новости", "Объявления", "https://apumir.app/s/Abcdefgh",
            isPublic = false, needsApproval = false,
        ) + "\nСкачать APU:\nhttps://example.org/app.apk"
        val card = requireNotNull(GroupInviteCardSender.parseCard(text))
        assertTrue(card.isChannel)
        assertEquals(false, card.isPublic)
        assertEquals(false, card.needsApproval)
        assertEquals("Объявления", card.about)
        assertEquals("https://example.org/app.apk", card.apkLink)
    }

    @Test
    fun oldShortInvitationDoesNotInventAccessSettings() {
        val card = requireNotNull(GroupInviteCardSender.parseCard(
            "Группа «БВ» в APU.\nhttps://apumir.app/s/Abcdefgh"
        ))
        assertNull(card.isPublic)
        assertNull(card.needsApproval)
    }

    @Test
    fun oldDeepLinkStillProvidesApprovalFlag() {
        val card = requireNotNull(GroupInviteCardSender.parseCard(
            "Приглашение в группу «БВ»:\np2pmessenger://group?slug=Abcdefghijkmnopq&a=1"
        ))
        assertEquals(true, card.needsApproval)
        assertNull(card.isPublic)
    }
}
