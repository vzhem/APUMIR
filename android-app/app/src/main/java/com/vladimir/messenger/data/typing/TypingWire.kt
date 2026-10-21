package com.vladimir.messenger.data.typing

/**
 * р235: «печатает…» - самый лёгкий служебный пакет приложения.
 *
 * Формат: `APUTYP1|1` (печатает) или `APUTYP1|0` (перестал/отправил).
 * Разбирается ДО сохранения в чат, поэтому в переписке не остаётся мусора;
 * старые версии приложения такого пакета не знают и показали бы его текстом,
 * поэтому пакет уходит ТОЛЬКО собеседнику-узлу (pk_…) и не рассылается
 * никому больше.
 */
object TypingWire {

    const val PREFIX = "APUTYP1|"

    fun isTypingPacket(text: String): Boolean = text.startsWith(PREFIX)

    fun build(typing: Boolean): String = PREFIX + if (typing) "1" else "0"

    /**
     * null - это не наш пакет; true/false - печатает/перестал.
     *
     * Строго: после префикса допустимы только «1» и «0». Раньше любое другое
     * содержимое читалось как «перестал печатать», и основной путь приёма
     * молча проглатывал так конверты ранга и сердечек - они едут в этой же
     * обёртке (APUTYP1|APURANK1|…, APUTYP1|APUGAME1|…), а ранг разбирался
     * позже «печатает». Именно поэтому значок VIP не появлялся: конверт
     * умирал за шаг до таблицы рангов.
     */
    fun parse(text: String): Boolean? {
        if (!isTypingPacket(text)) return null
        return when (text.substring(PREFIX.length).trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
}
