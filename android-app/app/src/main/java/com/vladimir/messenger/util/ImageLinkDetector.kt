package com.vladimir.messenger.util

/**
 * Определяет, что сообщение — это просто ссылка на картинку или гифку.
 *
 * Зачем: клавиатура (Gboard и подобные) вставляет гифку ссылкой, и в чате
 * вместо картинки висел текст ссылки. Такие сообщения показываем картинкой.
 *
 * Правила намеренно строгие: сообщение должно состоять из одной ссылки без
 * пробелов и переводов строк, а путь — заканчиваться расширением картинки.
 * Всё остальное остаётся обычным текстом со кликабельной ссылкой.
 */
object ImageLinkDetector {

    private val singleHttpUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    private val imageExtensions = listOf(
        ".gif",
        ".jpg",
        ".jpeg",
        ".png",
        ".webp",
        ".bmp",
    )

    /** Ссылка на картинку, если всё сообщение — это одна такая ссылка. Иначе null. */
    fun directImageUrl(text: String?): String? {
        val candidate = text?.trim().orEmpty()
        if (candidate.isBlank()) return null
        if (candidate.any { it.isWhitespace() }) return null
        if (!singleHttpUrl.matches(candidate)) return null
        // Запрос и якорь в расширении не входят: .../cat.gif?size=large
        val path = candidate.substringBefore('?').substringBefore('#').lowercase()
        return if (imageExtensions.any { path.endsWith(it) }) candidate else null
    }

    /** Похоже ли сообщение на ссылку-картинку — для интерфейса. */
    fun isDirectImage(text: String?): Boolean = directImageUrl(text) != null
}
