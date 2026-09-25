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
     * Раунд 156: служебные конверты роя (каталог стикеров APUSTK1 и
     * провод гифок APUGIF1 - миниатюры/порции) - НЕ сообщения: не должны
     * звонить в уведомлениях и накручивать непрочитанные (решение
     * владельца). Ссылки APUGIFREF1 - настоящие сообщения-карточки, их
     * не трогаем.
     */
    fun isServiceEnvelope(raw: String?): Boolean {
        if (raw == null) return false
        val t = raw.trim()
        return t.startsWith("APUSTK1|") || t.startsWith("APUGIF1|")
    }

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
        // Раунд 165: визитка стикера в группе/канале (имя «Стикер…») -
        // «🖼 Стикер» вместо sha/имени в уведомлениях и списках.
        GroupFileMarker.parse(t)?.let { info ->
            if (info.displayName.startsWith("Стикер")) return "\uD83D\uDDBC Стикер"
        }
        // Визитка файла с uuid-именем (гифки-блобы) - имя в предпросмотре
        // не нужно: внутри и так карточка.
        if (UUID_FILE.matches(t)) return GIF_LABEL
        return raw
    }
}
