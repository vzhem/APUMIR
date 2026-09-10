package com.vladimir.messenger.data.link

import com.vladimir.messenger.data.group.GroupInviteLinks
import java.util.regex.Pattern

/**
 * Короткие ссылки для пересылки наружу: `https://<хост>/s/<код>`.
 *
 * Зачем. Пересланная ссылка на пост выглядела так:
 *   https://<хост>/i?slug=…&g=<канал>&o=pk_<владелец>&p=<запись>&c=1
 * и показывала посторонним идентификаторы канала и владельца (владелец,
 * 2026-09-10: «чтобы в ссылках не было видно информацию»). Короткая ссылка
 * несёт только код: сама ссылка лежит на нашем сервисе (worker, POST /short),
 * и приложение спрашивает у него, куда ведёт код (GET /short/<код>).
 *
 * Код считает сервис - это отпечаток ссылки в алфавите без похожих знаков.
 * Обычно 10 знаков; при совпадении отпечатков сервис удлиняет до 16.
 *
 * Путь `/s/` нарочно отличается от `/i`: старые версии APU перехватывают
 * только `/i`, поэтому короткая ссылка у них уйдёт в браузер, а страница
 * сервиса уже отдаст полную ссылку p2pmessenger://, которую они понимают.
 *
 * Чистый Kotlin без Android: разбор проверяется обычными JVM-тестами.
 */
object ShortLinks {

    const val PATH = "/s/"

    private const val GROUP_PREFIX = "p2pmessenger://group?"
    private const val CONTACT_PREFIX = "p2pmessenger://add?"
    private const val APU_CONTACT_PREFIX = "apu://a/"

    private val CODE = Regex("^[A-Za-z0-9]{6,32}$")

    /**
     * Поиск короткой ссылки внутри произвольного текста (её копируют вместе с
     * сообщением). Класс символов кода без пробелов и кириллицы: на первом же
     * русском слове или пробеле ссылка заканчивается.
     */
    private val PATTERN: Pattern = Pattern.compile(
        "https://(?:" + Pattern.quote(GroupInviteLinks.WEB_HOST) + "|" +
            Pattern.quote(GroupInviteLinks.OFFICIAL_HOST) + ")" +
            Pattern.quote(PATH) + "([A-Za-z0-9]{6,32})",
        Pattern.CASE_INSENSITIVE,
    )

    fun isValidCode(code: String?): Boolean = code != null && CODE.matches(code)

    /** Короткая ссылка на нашем хосте по коду. */
    fun build(code: String): String = "https://" + GroupInviteLinks.WEB_HOST + PATH + code

    /**
     * Код из короткой ссылки либо null, если в тексте её нет. Понимает и
     * голую ссылку, и ссылку внутри пересланного сообщения.
     */
    fun codeOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val matcher = PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun isShortLink(raw: String?): Boolean = codeOf(raw) != null

    /**
     * Куда может вести короткая ссылка: только формы, которые строит само
     * приложение - приглашение в группу, канал или к записи и ссылка на
     * контакт. Всё остальное приложение открывать не станет, даже если сервис
     * такое вернул.
     */
    fun isAllowedTarget(target: String?): Boolean {
        val text = target?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_TARGET_CHARS) return false
        if (text.any { it.isWhitespace() }) return false
        val lower = text.lowercase()
        return lower.startsWith(GROUP_PREFIX) ||
            lower.startsWith(CONTACT_PREFIX) ||
            lower.startsWith(APU_CONTACT_PREFIX)
    }

    private const val MAX_TARGET_CHARS = 1024
}
