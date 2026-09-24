package com.vladimir.messenger.util

/**
 * Раунд 155: человекочитаемые предпросмотры для списков чатов и тем -
 * служебные строки не должны светиться буквами и цифрами на главной
 * (владелец, скрин 2026-09-24: «APUGIFREF1|b11…» у группы, «APUSTK1|ask»
 * в личном чате, «🖼 39513698-…» у файловой визитки).
 *
 * Внутри чата всё рендерится как надо (карточка гифки и т.п.), чистим
 * ТОЛЬКО предпросмотры: список чатов, список тем, поиск.
 */
object ChatPreviews {

    private const val GIF_LABEL = "\uD83D\uDDBC Гифка"

    /** Файловая визитка с uuid-именем: «🖼 39513698-… (1.2 МБ)». */
    private val UUID_FILE = Regex(
        "^\uD83D\uDDBC\\s+[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}" +
            "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*"
    )

    /**
     * Служебная строка -> короткая человеческая подпись; обычный текст
     * возвращается как был (null проходит насквозь).
     */
    fun human(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim()
        // Ссылка на гифку (раунд 128): «APUGIFREF1|<sha256>».
        if (t.startsWith("APUGIFREF1|")) return GIF_LABEL
        // Конверт стикер-роя (раунд 139): «APUSTK1|ask|…».
        if (t.startsWith("APUSTK1|")) return "Стикеры"
        // Визитка файла с uuid-именем (гифки-блобы) - имя в предпросмотре
        // не нужно: внутри и так карточка.
        if (UUID_FILE.matches(t)) return GIF_LABEL
        return raw
    }
}
