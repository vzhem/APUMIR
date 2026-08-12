package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkParserTest {
    @Test
    fun parsesAppLinkWithSnakeCaseNodeId() {
        val invite = InviteLinkParser.parse("p2pmessenger://add?node_id=pk_abc123")

        assertEquals("pk_abc123", invite?.nodeId)
        assertEquals(InviteLinkParser.Source.APP_LINK, invite?.source)
    }

    @Test
    fun parsesAppLinkWithCamelCaseNodeIdAndName() {
        val invite = InviteLinkParser.parse("p2pmessenger://add?nodeId=pk_abc123&name=Vladimir%20Test")

        assertEquals("pk_abc123", invite?.nodeId)
        assertEquals("Vladimir Test", invite?.displayName)
        assertEquals(InviteLinkParser.Source.APP_LINK, invite?.source)
    }

    @Test
    fun parsesCurrentTelegramBotLinkWithRawNodeId() {
        val invite = InviteLinkParser.parse("https://t.me/p2p_messenger_relay_bot?start=pk_abc123")

        assertEquals("pk_abc123", invite?.nodeId)
        assertEquals(InviteLinkParser.Source.TELEGRAM_LINK, invite?.source)
    }

    @Test
    fun parsesCurrentTelegramBotLinkWithAddPrefix() {
        val invite = InviteLinkParser.parse("https://t.me/p2p_messenger_relay_bot?start=add_pk_abc123")

        assertEquals("pk_abc123", invite?.nodeId)
        assertEquals(InviteLinkParser.Source.TELEGRAM_LINK, invite?.source)
    }

    @Test
    fun parsesLegacyTelegramBotLink() {
        val invite = InviteLinkParser.parse("https://t.me/P2PMessengerBot?start=add_pk_abc123")

        assertEquals("pk_abc123", invite?.nodeId)
        assertEquals(InviteLinkParser.Source.LEGACY_TELEGRAM_LINK, invite?.source)
    }

    @Test
    fun parsesRustConnectLink() {
        val invite = InviteLinkParser.parse("p2pm://connect?node=pk_abc123&addr=1.2.3.4%3A9000")

        assertEquals("pk_abc123", invite?.nodeId)
        assertEquals(InviteLinkParser.Source.RUST_CONNECT_LINK, invite?.source)
    }

    @Test
    fun ignoresUnknownTelegramBot() {
        val invite = InviteLinkParser.parse("https://t.me/other_bot?start=pk_abc123")

        assertNull(invite)
    }

    @Test
    fun ignoresInvalidLinks() {
        assertNull(InviteLinkParser.parse(""))
        assertNull(InviteLinkParser.parse("not a uri"))
        assertNull(InviteLinkParser.parse("https://example.com/?node_id=pk_abc123"))
    }
}
