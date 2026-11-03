package com.vladimir.messenger.service

/**
 * «1 собеседник», «2 собеседника», «5 собеседников» для постоянного
 * уведомления службы.
 *
 * Вынесено из `CoreServerService.kt` при добавлении опросов личных чатов
 * (р252): это подпись, а не логика доставки, и god-файл от неё не должен расти.
 */
internal fun peersWord(peers: Long): String {
    val n = (peers % 100).toInt()
    val word = when {
        n in 11..14 -> "собеседников"
        n % 10 == 1 -> "собеседник"
        n % 10 in 2..4 -> "собеседника"
        else -> "собеседников"
    }
    return "$peers $word"
}
