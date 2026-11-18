package com.vladimir.messenger.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =============================================================================
// CHATROWTIME.KT — время строки списка: личные чаты, группы и каналы
// =============================================================================

/**
 * Время строки группы или канала: сегодня - часы и минуты, раньше - дата.
 *
 * Вынесено из `ChatListScreen.kt` в общий файл по правилу god-файлов: экран на
 * потолке строк, а формат нужен ещё и строке «Избранного». Логика прежняя.
 */
fun formatGroupRowTime(timestamp: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return if (timestamp > today.timeInMillis) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    } else {
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(timestamp))
    }
}
