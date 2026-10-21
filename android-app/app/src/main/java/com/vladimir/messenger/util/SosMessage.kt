package com.vladimir.messenger.util

/**
 * Тревожное сообщение SOS: получатель видит красное сообщение и слышит
 * тревожное уведомление, даже если группа без звука.
 *
 * Признак едет префиксом в тексте (как у тихих сообщений и файлов),
 * поэтому протокол ядра и конверт группового сообщения не меняются, а
 * старые версии приложения покажут текст как обычное сообщение - сигнал
 * всё равно дойдёт, просто без красной раскраски и особого звука.
 * Все места показа снимают префикс через [strip].
 */
object SosMessage {
    const val PREFIX = "APUSOS1:"

    /** Текст тревожного сообщения: короткий и понятный без перевода. */
    const val DEFAULT_TEXT = "🆘 SOS! Срочно нужна помощь!"

    fun wrap(text: String): String = PREFIX + text

    fun isSos(content: String?): Boolean = content?.startsWith(PREFIX) == true

    fun strip(content: String): String =
        if (content.startsWith(PREFIX)) content.removePrefix(PREFIX) else content
}
