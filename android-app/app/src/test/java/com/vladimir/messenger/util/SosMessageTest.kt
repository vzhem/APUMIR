package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Признак тревожного сообщения SOS в тексте: префикс не должен ни теряться
 * при показе, ни срабатывать на обычном тексте, где человек сам написал
 * похожее слово.
 */
class SosMessageTest {

    @Test
    fun wrapMarksAndStripRestores() {
        val wrapped = SosMessage.wrap(SosMessage.DEFAULT_TEXT)
        assertTrue(SosMessage.isSos(wrapped))
        assertEquals(SosMessage.DEFAULT_TEXT, SosMessage.strip(wrapped))
    }

    @Test
    fun plainTextIsNotSos() {
        assertFalse(SosMessage.isSos("SOS! Нужна помощь!"))
        assertFalse(SosMessage.isSos(null))
        assertEquals("обычный текст", SosMessage.strip("обычный текст"))
    }

    @Test
    fun defaultTextIsHumanReadableWithoutAppSupport() {
        // Старые версии покажут сам текст: он обязан быть понятен без прикрас.
        assertTrue(SosMessage.DEFAULT_TEXT.contains("SOS"))
    }
}
