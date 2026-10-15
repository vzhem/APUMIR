package com.vladimir.messenger.util

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
}
