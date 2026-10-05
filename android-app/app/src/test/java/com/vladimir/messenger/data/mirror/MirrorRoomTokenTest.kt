package com.vladimir.messenger.data.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка договора о комнате зеркала (р249).
 *
 * Главное здесь - не формулы, а СХОДИМОСТЬ: два устройства обязаны выбрать
 * один и тот же маркер, кем бы они ни были и в каком порядке ни узнали бы
 * о маркере партнёра. Разошлись - оказались в разных комнатах и оглохли друг
 * к другу, а найтись смогли бы только случайной разведкой.
 */
class MirrorRoomTokenTest {

    private val older = MirrorRoomToken.RoomToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1_000L)
    private val newer = MirrorRoomToken.RoomToken("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2_000L)

    /** Маркера нет вовсе - берём любой предложенный. */
    @Test
    fun firstTokenWins() {
        assertTrue(MirrorRoomToken.wins(older, null))
    }

    /** Более старый маркер главнее: он уже разделил устройства однажды. */
    @Test
    fun olderTokenWins() {
        assertTrue(MirrorRoomToken.wins(older, newer))
        assertTrue(!MirrorRoomToken.wins(newer, older))
    }

    /** Свой маркер не выигрывает сам у себя - иначе устройства перезаписывали бы его бесконечно. */
    @Test
    fun sameTokenDoesNotWin() {
        assertTrue(!MirrorRoomToken.wins(older, older))
    }

    /** Одинаковое время - меньший строкой. Правило обязано быть симметричным. */
    @Test
    fun equalTimeIsBrokenByValue() {
        val left = MirrorRoomToken.RoomToken("11111111111111111111111111111111", 5_000L)
        val right = MirrorRoomToken.RoomToken("22222222222222222222222222222222", 5_000L)
        assertTrue(MirrorRoomToken.wins(left, right))
        assertTrue(!MirrorRoomToken.wins(right, left))
    }

    /** Оба устройства сходятся на одном маркере, кто бы о ком ни узнал первым. */
    @Test
    fun bothSidesConverge() {
        val mine = newer
        val theirs = older
        val myChoice = if (MirrorRoomToken.wins(theirs, mine)) theirs else mine
        val theirChoice = if (MirrorRoomToken.wins(mine, theirs)) mine else theirs
        assertEquals(myChoice, theirChoice)
        assertEquals(older, myChoice)
    }

    /** Печать полки: пустая без маркера, устойчивая с ним. */
    @Test
    fun stampIsStableAndBlankWithoutToken() {
        assertEquals("", MirrorRoomToken.stamp(null))
        assertEquals("", MirrorRoomToken.stamp(""))
        assertEquals(MirrorRoomToken.stamp(older.value), MirrorRoomToken.stamp(older.value))
        assertNotEquals(MirrorRoomToken.stamp(older.value), MirrorRoomToken.stamp(newer.value))
    }

    /** Полка: 32 hex, своя у каждого маркера и одна и та же для одного маркера. */
    @Test
    fun shelfIsDerivedFromToken() {
        val shelf = MirrorRoomToken.shelf(older.value)
        assertEquals(32, shelf.length)
        assertTrue(shelf.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(shelf, MirrorRoomToken.shelf(older.value))
        assertNotEquals(shelf, MirrorRoomToken.shelf(newer.value))
    }

    /** Метка кадра есть только у защищённой комнаты. */
    @Test
    fun frameMarkFollowsToken() {
        assertEquals("", MirrorRoomToken.frameMark(null))
        assertNotEquals("", MirrorRoomToken.frameMark(older.value))
        assertEquals(
            MirrorRoomToken.frameMark(older.value),
            MirrorRoomToken.frameMark(older.value),
        )
    }
}
