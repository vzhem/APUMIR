package com.vladimir.messenger.data.gif

/**
 * Одна гифка из каталога (наш сервер /gif/search).
 *
 * [preview] - лёгкая анимированная версия для сетки выбора,
 * [gif] - полная версия: её скачиваем и отправляем как файл через рой.
 */
data class GifItem(
    val id: String,
    val preview: String,
    val gif: String,
)
