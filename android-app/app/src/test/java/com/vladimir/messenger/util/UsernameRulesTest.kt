package com.vladimir.messenger.util

import com.vladimir.messenger.ui.theme.UsernameHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило @никнейма: латинские буквы, цифры и подчёркивание.
 *
 * Ограничение не косметическое. @никнейм едет в короткой ссылке
 * `apu://a/<узел>/<никнейм>` и в QR-коде: кириллица требует URL-кодирования,
 * где одна буква превращается в шесть символов, и ссылка снова распухает.
 * Кроме того, по @никнейму человека связывают между переустановками, его
 * диктуют голосом и набирают руками - похожие на вид кириллические и
 * латинские буквы делали бы два разных имени неотличимыми.
 *
 * Проверяемые здесь функции не трогают Android, поэтому тест идёт в гейте.
 */
class UsernameRulesTest {

    @Test
    fun latinNamesAreAccepted() {
        assertTrue(UsernameHolder.isValid("vladimir"))
        assertTrue(UsernameHolder.isValid("stas_77"))
        assertTrue(UsernameHolder.isValid("APU2026"))
        assertTrue(UsernameHolder.isValid("@vladimir"))
    }

    @Test
    fun cyrillicIsRejected() {
        assertFalse(UsernameHolder.isValid("владимир"))
        assertFalse(UsernameHolder.isValid("vladimir_я"))
        assertNull(UsernameHolder.normalize("владимир"))
    }

    @Test
    fun punctuationAndSpacesAreRejected() {
        assertFalse(UsernameHolder.isValid("vlad imir"))
        assertFalse(UsernameHolder.isValid("vlad.imir"))
        assertFalse(UsernameHolder.isValid("vlad-imir"))
        assertFalse(UsernameHolder.isValid("vlad!"))
        assertFalse(UsernameHolder.isValid("влад🙂"))
        assertFalse(UsernameHolder.isValid(""))
        assertFalse(UsernameHolder.isValid(null))
    }

    /** Чистка при наборе: недопустимый знак просто не появляется в поле. */
    @Test
    fun sanitizeKeepsOnlyTheAllowedCharacters() {
        assertEquals("vladimir", UsernameHolder.sanitize("vladimir"))
        assertEquals("vladimir", UsernameHolder.sanitize("@vladimir"))
        assertEquals("vladimir", UsernameHolder.sanitize("vlad imir"))
        assertEquals("vladimir", UsernameHolder.sanitize("vlad.imir"))
        assertEquals("vlad_77", UsernameHolder.sanitize("vlad_77"))
        assertEquals("", UsernameHolder.sanitize("владимир"))
        assertEquals("77", UsernameHolder.sanitize("владимир77"))
        assertEquals("", UsernameHolder.sanitize(null))
    }

    @Test
    fun tooLongNamesAreCutToTheLimit() {
        val long = "a".repeat(60)

        assertEquals(UsernameHolder.MAX_CHARS, UsernameHolder.sanitize(long).length)
        assertFalse(UsernameHolder.isValid(long))
        assertTrue(UsernameHolder.isValid("a".repeat(UsernameHolder.MAX_CHARS)))
    }

    /** Имя без собаки: собака - неснимаемый префикс при показе. */
    @Test
    fun theLeadingAtIsNotStored() {
        assertEquals("vladimir", UsernameHolder.normalize("  @vladimir  "))
    }

    /**
     * Главное ради чего правило и вводилось: годное имя не требует
     * URL-кодирования, поэтому ссылка остаётся короткой.
     */
    @Test
    fun anAcceptedNameGoesIntoTheShortLinkUnchanged() {
        val node = "pk_" + "0123456789abcdef".repeat(2)
        val link = ApuLink.build(node, "vladimir")!!

        assertTrue("ссылка выросла до ${link.length} символов", link.length <= 70)
        assertFalse("имя пришлось перекодировать: $link", link.contains('%'))
        assertEquals("vladimir", ApuLink.parse(link)?.nickname)
    }

    /** Случайные имена, выдаваемые при регистрации, обязаны проходить правило. */
    @Test
    fun generatedNamesSatisfyTheRule() {
        val words = listOf(
            "astrid", "vega", "altair", "sirius", "lyra", "orion", "draco",
            "persei", "foton", "kvazar", "pulsar", "neon", "argon", "krypton",
            "xenon", "radon", "cobalt", "titan", "zircon", "osmium", "iridium",
            "hafnium", "photon", "quasar", "nova", "comet", "meteor", "orbit",
            "sputnik", "vostok",
        )

        for (word in words) {
            assertTrue("негодное слово: $word", UsernameHolder.isValid(word + "457"))
        }
    }
}
