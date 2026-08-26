package com.vladimir.messenger.data.group

import java.net.URI
import java.security.SecureRandom

/**
 * Ссылки-приглашения в группу. Рядом со ссылкой экран рисует QR-код того же
 * текста через существующий util.QrCodeGenerator.
 *
 * Поддерживаемые формы (по образцу util.InviteLinkParser для личных контактов):
 *  - p2pmessenger://group?slug=<slug>
 *  - p2p://group/<slug>
 *  - https://t.me/p2p_messenger_relay_bot?start=grp_<slug>
 */
object GroupInviteLinks {

    const val APP_LINK_PREFIX = "p2pmessenger://group?slug="
    const val SHORT_LINK_PREFIX = "p2p://group/"
    const val TELEGRAM_BOT_USERNAME = "p2p_messenger_relay_bot"
    const val TELEGRAM_START_PREFIX = "grp_"

    private const val SLUG_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private const val SLUG_LENGTH = 16

    fun newSlug(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(SLUG_LENGTH)
        repeat(SLUG_LENGTH) {
            sb.append(SLUG_ALPHABET[random.nextInt(SLUG_ALPHABET.length)])
        }
        return sb.toString()
    }

    /** Основная ссылка — её показываем текстом и кодируем в QR. */
    fun build(slug: String): String = APP_LINK_PREFIX + slug

    fun buildTelegramLink(slug: String): String =
        "https://t.me/" + TELEGRAM_BOT_USERNAME + "?start=" + TELEGRAM_START_PREFIX + slug

    fun isValidSlug(slug: String?): Boolean =
        !slug.isNullOrBlank() &&
            slug.length in 8..32 &&
            slug.all { it in SLUG_ALPHABET }

    /**
     * Достаёт slug из любой поддерживаемой формы. Возвращает null, если строка
     * не похожа на приглашение в группу — тогда её не надо путать с личным
     * приглашением контакта.
     */
    fun parseSlug(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null

        if (text.startsWith(SHORT_LINK_PREFIX)) {
            return normalizeSlug(text.removePrefix(SHORT_LINK_PREFIX))
        }

        return try {
            val uri = URI(text)
            when (uri.scheme?.lowercase()) {
                "p2pmessenger" -> {
                    if (!uri.host.equals("group", ignoreCase = true)) return null
                    parseQuerySlug(uri.rawQuery)
                }
                "https", "http" -> parseTelegramStart(uri.rawQuery)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQuerySlug(query: String?): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].equals("slug", ignoreCase = true) }
            ?.let { normalizeSlug(it[1]) }
    }

    private fun parseTelegramStart(query: String?): String? {
        if (query.isNullOrBlank()) return null
        val start = query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].equals("start", ignoreCase = true) }
            ?.get(1) ?: return null
        if (!start.startsWith(TELEGRAM_START_PREFIX)) return null
        return normalizeSlug(start.removePrefix(TELEGRAM_START_PREFIX))
    }

    private fun normalizeSlug(candidate: String): String? {
        val slug = candidate.trim()
        return if (isValidSlug(slug)) slug else null
    }
}
