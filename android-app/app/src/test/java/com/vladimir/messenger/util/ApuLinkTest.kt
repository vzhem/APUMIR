package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Короткая ссылка-приглашение.
 *
 * Смысл всей затеи - размер QR-кода. Прежняя ссылка занимала 589 символов и
 * требовала 85 модулей: густая сетка, которую камера ловит подолгу. Короткая
 * укладывается в 60 символов и 33 модуля, поэтому модуль становится втрое
 * крупнее и код читается с расстояния и под углом.
 *
 * Здесь проверяется само свойство: что построено, то и разбирается обратно, а
 * длина остаётся в пределах, ради которых всё делалось.
 */
class ApuLinkTest {

    private val shortNode = "pk_" + "0123456789abcdef".repeat(2)
    private val longNode = "pk_" + "0123456789abcdef".repeat(4)

    @Test
    fun linkRoundTripsThroughItsOwnParser() {
        val link = ApuLink.build(shortNode, "vladimir")
        assertNotNull(link)

        val parsed = ApuLink.parse(link)
        assertEquals(shortNode, parsed?.nodeId)
        assertEquals("vladimir", parsed?.nickname)
    }

    @Test
    fun longNodeRoundTripsToo() {
        val parsed = ApuLink.parse(ApuLink.build(longNode, "stas77"))

        assertEquals(longNode, parsed?.nodeId)
        assertEquals("stas77", parsed?.nickname)
    }

    /** Общий разборщик обязан понимать короткую ссылку: её отдают все экраны. */
    @Test
    fun theCommonParserUnderstandsTheShortLink() {
        val invite = InviteLinkParser.parse(ApuLink.build(shortNode, "vladimir"))

        assertEquals(shortNode, invite?.nodeId)
        assertEquals("vladimir", invite?.username)
        assertEquals(InviteLinkParser.Source.APP_LINK, invite?.source)
    }

    /**
     * Ради этого всё и затевалось. 589 символов прежней ссылки давали QR из 85
     * модулей; здесь строка обязана остаться заметно короче сотни символов.
     */
    @Test
    fun theLinkStaysShortEnoughToKeepTheQrCoarse() {
        val link = ApuLink.build(shortNode, "vladimir")!!

        assertTrue("ссылка выросла до ${link.length} символов", link.length <= 70)
    }

    @Test
    fun nodeSurvivesPackingUnchanged() {
        assertEquals(shortNode, ApuLink.unpackNode(ApuLink.packNode(shortNode)))
        assertEquals(longNode, ApuLink.unpackNode(ApuLink.packNode(longNode)))
    }

    /** Плотная запись обязана быть заметно короче шестнадцатеричной. */
    @Test
    fun packingActuallySavesSpace() {
        val packed = ApuLink.packNode(longNode)!!

        assertTrue("упаковано в $packed", packed.length < longNode.length - 20)
    }

    @Test
    fun linkWithoutNicknameIsStillValid() {
        val parsed = ApuLink.parse(ApuLink.build(shortNode, null))

        assertEquals(shortNode, parsed?.nodeId)
        assertNull(parsed?.nickname)
    }

    /** Собака - неснимаемый префикс при показе, в ссылке её быть не должно. */
    @Test
    fun leadingAtIsStrippedFromTheNickname() {
        val parsed = ApuLink.parse(ApuLink.build(shortNode, "  @vladimir "))

        assertEquals("vladimir", parsed?.nickname)
    }

    @Test
    fun nicknameOnlyLinkCarriesNoNode() {
        val parsed = ApuLink.parse(ApuLink.buildByNickname("@stas77"))

        assertNull(parsed?.nodeId)
        assertEquals("stas77", parsed?.nickname)
    }

    /** Сканеры иногда отдают текст заглавными: схема не должна от этого падать. */
    @Test
    fun theSchemeIsCaseInsensitive() {
        val link = ApuLink.build(shortNode, "vladimir")!!
        val shouted = link.replaceFirst("apu://a/", "APU://A/")

        assertEquals(shortNode, ApuLink.parse(shouted)?.nodeId)
    }

    @Test
    fun garbageGivesNothing() {
        assertNull(ApuLink.parse(null))
        assertNull(ApuLink.parse(""))
        assertNull(ApuLink.parse("apu://a/"))
        assertNull(ApuLink.parse("apu://a/не-base64!!"))
        assertNull(ApuLink.parse("https://example.com/apu://a/xxx"))
        assertNull(ApuLink.build("не узел", "vladimir"))
        assertNull(ApuLink.build("", "vladimir"))
    }

    /** Узел неверной длины - не узел, даже если это правильный base64. */
    @Test
    fun wrongNodeLengthIsRejected() {
        assertNull(ApuLink.unpackNode("YWJj"))
    }

    @Test
    fun badNicknamesAreRejected() {
        assertNull(ApuLink.normalizeNick("две слова"))
        assertNull(ApuLink.normalizeNick("@"))
        assertNull(ApuLink.normalizeNick(""))
        assertNull(ApuLink.normalizeNick(null))
        assertEquals("vlad_77", ApuLink.normalizeNick("@vlad_77"))
    }

    /** Длинные ссылки прежнего образца обязаны продолжать работать. */
    @Test
    fun theOldLongLinkStillParses() {
        val old = "p2pmessenger://add?node_id=$shortNode&name=%D0%A1%D1%82%D0%B0%D1%81&u=stas77"

        val invite = InviteLinkParser.parse(old)
        assertEquals(shortNode, invite?.nodeId)
        assertEquals("stas77", invite?.username)
    }
}
