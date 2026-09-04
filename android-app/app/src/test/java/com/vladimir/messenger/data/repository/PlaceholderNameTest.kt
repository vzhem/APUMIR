package com.vladimir.messenger.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож для распознавания технических имён.
 *
 * После обмена по QR в чат попадал кусок node_id, и настоящее имя из presence
 * его уже не подменяло. Правило легко испортить, сделав его слишком жадным,
 * поэтому обычные имена проверяются наравне с техническими.
 */
class PlaceholderNameTest {

    /** Логика повторяет ContactRepository.looksTechnical (чистая функция). */
    private fun looksTechnical(name: String): Boolean {
        val clean = name.trim()
        if (clean.startsWith("pk_")) return true
        if (clean.length < 12 || clean.contains(' ')) return false
        if (!clean.all { it.isLetterOrDigit() }) return false
        if (clean.any { it.code > 127 }) return false
        return clean.any { it.isDigit() } && clean.any { it.isLetter() }
    }

    @Test
    fun `node id is technical`() {
        assertTrue(looksTechnical("pk_ae8962d82a9864b611329446a35f3ced"))
        assertTrue(looksTechnical("pk_test"))
    }

    @Test
    fun `long letter digit soup is technical`() {
        assertTrue(looksTechnical("ae8962d82a9864b6"))
        assertTrue(looksTechnical("a1b2c3d4e5f6a7b8"))
    }

    @Test
    fun `human names are not technical`() {
        assertFalse(looksTechnical("Владимир"))
        assertFalse(looksTechnical("Анна"))
        assertFalse(looksTechnical("Alice"))
        assertFalse(looksTechnical("Alexander Smith"))
        // Кириллица с цифрами - живое имя, а не идентификатор.
        assertFalse(looksTechnical("Александр2000"))
        // Короткое слово с цифрой тоже оставляем человеку.
        assertFalse(looksTechnical("Server5"))
    }
}
