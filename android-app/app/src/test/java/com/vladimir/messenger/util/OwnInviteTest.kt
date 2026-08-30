package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат ссылки-приглашения на свой профиль.
 *
 * Ссылку показывают шесть мест: раздел рангов, «Поделиться приглашением» в
 * списке чатов, два места в контактах, «Мой QR-код» в настройках, экран
 * профиля и шаг «Покажите другу» в регистрации. Все они обязаны брать строку у
 * [OwnInvite], иначе где-то появится QR без подписанного токена и приглашение
 * перестанет поднимать ранг. Здесь проверяется само свойство: что бы
 * [OwnInvite.buildLink] ни собрал, [InviteLinkParser] разберёт это обратно с
 * тем же токеном.
 *
 * [OwnInvite.buildLink] намеренно не трогает Android, поэтому тест выполняется
 * в гейте на шаге unit-тестов.
 */
class OwnInviteTest {

    private val nodeId = "pk_" + "0123456789abcdef".repeat(2)
    private val token = "AQCOAQAj" + "aB3_-".repeat(40)

    @Test
    fun linkWithTokenRoundTripsThroughTheParser() {
        val link = OwnInvite.buildLink(nodeId, "Стас", "stas77", token)
        assertNotNull(link)

        val invite = InviteLinkParser.parse(link)
        assertEquals(nodeId, invite?.nodeId)
        assertEquals("Стас", invite?.displayName)
        assertEquals("stas77", invite?.username)
        assertEquals(token, invite?.referralToken)
        assertEquals(InviteLinkParser.Source.APP_LINK, invite?.source)
    }

    /** Без токена ссылка остаётся рабочей, но ранг по ней не начисляется. */
    @Test
    fun linkWithoutTokenCarriesNoReferral() {
        val invite = InviteLinkParser.parse(OwnInvite.buildLink(nodeId, "Стас", "", null))

        assertEquals(nodeId, invite?.nodeId)
        assertNull(invite?.referralToken)
    }

    @Test
    fun cyrillicNameAndUsernameSurviveTheUrlEncoding() {
        val link = OwnInvite.buildLink(nodeId, "Владимир Тест", "владимир_77", token)

        val invite = InviteLinkParser.parse(link)
        assertEquals("Владимир Тест", invite?.displayName)
        assertEquals("владимир_77", invite?.username)
        assertEquals(token, invite?.referralToken)
    }

    /** Собака — неснимаемый префикс при показе, в ссылке её быть не должно. */
    @Test
    fun leadingAtIsStrippedFromTheUsername() {
        val invite = InviteLinkParser.parse(OwnInvite.buildLink(nodeId, "Me", "  @vladimir  ", null))

        assertEquals("vladimir", invite?.username)
    }

    @Test
    fun blankNodeIdGivesNoLink() {
        assertNull(OwnInvite.buildLink("", "Me", "me", token))
        assertNull(OwnInvite.buildLink("   ", "Me", "me", token))
    }

    /**
     * Имя параметра обязано совпадать с тем, что читает парсер: расхождение
     * означало бы ссылку, которая выглядит подписанной, но токена не несёт.
     */
    @Test
    fun tokenParameterNameMatchesTheParser() {
        assertEquals("r", ReferralInviteLink.TOKEN_PARAMETER)
        val link = OwnInvite.buildLink(nodeId, "Me", "", token)!!
        assertTrue(link.contains("&${ReferralInviteLink.TOKEN_PARAMETER}=$token"))
    }

    /**
     * Худший случай: токен из 512 байт даёт 683 символа в base64url. Он обязан
     * проходить и через предел ссылки, и через предел парсера (700 символов),
     * иначе реальный токен молча терялся бы на длинных узлах.
     */
    @Test
    fun worstCaseTokenStillReachesTheParser() {
        val longInvitee = "pk_" + "ab".repeat(32)
        val bigToken = "A".repeat(683)
        val link = OwnInvite.buildLink(longInvitee, "Очень длинное имя владельца", "очень_длинное_имя", bigToken)!!

        val invite = InviteLinkParser.parse(link)
        assertEquals(longInvitee, invite?.nodeId)
        assertEquals(bigToken, invite?.referralToken)
    }

    /**
     * Обычная ссылка владельца — короткий узел (35 символов), короткое имя и
     * реальный токен ~330 символов. Запас нужен для QR-кода: чем короче строка,
     * тем крупнее модули и тем легче код читается камерой.
     */
    @Test
    fun realisticLinkStaysShortEnoughForAQrCode() {
        val link = OwnInvite.buildLink("pk_" + "0".repeat(32), "Стас", "stas77", "A".repeat(330))!!

        assertTrue("ссылка выросла до ${link.length} символов", link.length < 500)
        assertEquals("A".repeat(330), InviteLinkParser.parse(link)?.referralToken)
    }

    @Test
    fun emptyNameAndUsernameProduceAParsableLink() {
        val invite = InviteLinkParser.parse(OwnInvite.buildLink(nodeId, "", "", token))

        assertEquals(nodeId, invite?.nodeId)
        assertNull(invite?.username)
        assertEquals(token, invite?.referralToken)
    }
}
