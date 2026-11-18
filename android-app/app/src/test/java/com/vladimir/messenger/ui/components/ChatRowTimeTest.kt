package com.vladimir.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRowTimeTest {

    @Test
    fun todayIsShownAsHoursAndMinutes() {
        val now = System.currentTimeMillis()
        // Формат фиксирован: строка списка не должна показывать дату у свежего сообщения.
        assertEquals(true, formatGroupRowTime(now).matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun olderThanTodayIsShownAsDayAndMonth() {
        val old = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000
        assertEquals(true, formatGroupRowTime(old).matches(Regex("\\d{2}\\.\\d{2}")))
    }
}
