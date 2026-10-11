package com.vladimir.messenger.util

/**
 * Тихое сообщение: получатель видит его без звука в уведомлении.
 * Признак едет префиксом в тексте (как у файлов и гифок), поэтому протокол
 * ядра не меняется. Все места показа снимают префикс через [strip].
 */
object QuietMessage {
    const val PREFIX = "APUQUIET1:"

    fun wrap(text: String): String = PREFIX + text

    fun isQuiet(content: String?): Boolean = content?.startsWith(PREFIX) == true

    fun strip(content: String): String =
        if (content.startsWith(PREFIX)) content.removePrefix(PREFIX) else content
}
