package com.vladimir.messenger.data.group

import java.net.URI
import java.security.SecureRandom
import java.util.regex.Pattern

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

    /**
     * Основная ссылка — её показываем текстом и кодируем в QR.
     *
     * Кроме slug ссылка несёт id группы (`g`) и адрес владельца (`o`): без них
     * вступающий телефон не знает, у кого спрашивать группу. Пригласительная
     * запись живёт только в базе создателя, поэтому «найти приглашение по
     * slug» на чужом телефоне невозможно — ссылка обязана быть самодостаточной.
     * Оба параметра необязательны: старая ссылка без них тоже разбирается.
     */
    fun build(
        slug: String,
        groupId: String? = null,
        ownerId: String? = null,
        isChannel: Boolean = false,
        requestApproval: Boolean = false,
    ): String {
        val sb = StringBuilder(APP_LINK_PREFIX).append(slug)
        if (!groupId.isNullOrBlank()) sb.append("&g=").append(groupId)
        if (!ownerId.isNullOrBlank()) sb.append("&o=").append(ownerId)
        // Признаки нужны вступающему телефону: по ним он пишет правду -
        // «заявка отправлена» или «входим сразу», и называет канал каналом.
        // Владелец им не доверяет и проверяет всё по своей базе.
        if (isChannel) sb.append("&c=1")
        if (requestApproval) sb.append("&a=1")
        return sb.toString()
    }

    fun buildTelegramLink(slug: String): String =
        "https://t.me/" + TELEGRAM_BOT_USERNAME + "?start=" + TELEGRAM_START_PREFIX + slug

    fun isValidSlug(slug: String?): Boolean =
        !slug.isNullOrBlank() &&
            slug.length in 8..32 &&
            slug.all { it in SLUG_ALPHABET }

    /**
     * Разобранная ссылка-приглашение. `groupId` и `ownerId` есть только в
     * ссылках нового образца — без них вступить с другого телефона нельзя.
     */
    data class InviteTarget(
        val slug: String,
        val groupId: String?,
        val ownerId: String?,
        /** Приглашение в канал, а не в группу. В старых ссылках признака нет. */
        val isChannel: Boolean = false,
        /** Ссылка создана с одобрением: владелец должен подтвердить вход. */
        val needsApproval: Boolean = false,
    ) {
        /** Хватает ли данных, чтобы попросить группу по сети. */
        val isRoutable: Boolean
            get() = !groupId.isNullOrBlank() && !ownerId.isNullOrBlank()
    }

    /**
     * Достаёт slug из любой поддерживаемой формы. Возвращает null, если строка
     * не похожа на приглашение в группу — тогда её не надо путать с личным
     * приглашением контакта.
     */
    fun parseSlug(raw: String?): String? = parseTarget(raw)?.slug

    /**
     * Полная разборка ссылки: slug плюс, если они есть, id группы и адрес
     * владельца. Принимает и голый slug (старый QR, вставка из буфера).
     */
    fun parseTarget(raw: String?): InviteTarget? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null

        // Голый slug без схемы — старый образец ссылки.
        normalizeSlug(text)?.let { return InviteTarget(it, null, null) }

        // Мессенджеры (MAX и подобные) переносят длинную ссылку по словам, а
        // скопировать только её нельзя - копируется всё сообщение, с переводами
        // строк внутри ссылки. Убираем переводы строк.
        val glued = text.replace("\r", "").replace("\n", "")

        // Ссылку СНАЧАЛА вырезаем, и только потом разбираем. Разбирать строку
        // целиком нельзя: java.net.URI разрешает не-ASCII символы, поэтому
        // приклеившийся русский текст («...o=pk_ownerСкачатьAPU») уедет прямо в
        // адрес владельца. Регулярка обрывается на первом же пробеле или
        // кириллическом символе.
        val matcher = LINK_PATTERN.matcher(glued)
        if (matcher.find()) {
            parseClean(matcher.group().orEmpty())?.let { return it }
        }

        // Регулярка не нашла - пробуем разобрать как есть (на случай формы,
        // которую она не покрывает).
        return parseClean(glued)
    }

    /** Разбор уже выделенной ссылки, без поиска внутри текста. */
    private fun parseClean(text: String): InviteTarget? {
        if (text.startsWith(SHORT_LINK_PREFIX)) {
            val rest = text.removePrefix(SHORT_LINK_PREFIX)
            val slug = normalizeSlug(rest.substringBefore('?')) ?: return null
            val query = if (rest.contains('?')) rest.substringAfter('?') else ""
            return InviteTarget(
                slug = slug,
                groupId = queryParam(query, "g"),
                ownerId = queryParam(query, "o"),
                isChannel = queryParam(query, "c") == "1",
                needsApproval = queryParam(query, "a") == "1",
            )
        }

        return try {
            val uri = URI(text)
            val slug = when (uri.scheme?.lowercase()) {
                "p2pmessenger" -> {
                    if (!uri.host.equals("group", ignoreCase = true)) return null
                    parseQuerySlug(uri.rawQuery)
                }
                "https", "http" -> parseTelegramStart(uri.rawQuery)
                else -> null
            } ?: return null
            InviteTarget(
                slug = slug,
                groupId = queryParam(uri.rawQuery, "g"),
                ownerId = queryParam(uri.rawQuery, "o"),
                isChannel = queryParam(uri.rawQuery, "c") == "1",
                needsApproval = queryParam(uri.rawQuery, "a") == "1",
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Поиск ссылки внутри произвольного текста. Классы символов намеренно без
     * пробелов и без кириллицы: на первом же русском слове или пробеле ссылка
     * заканчивается, поэтому приклеившийся текст сообщения в неё не попадает.
     */
    private val LINK_PATTERN: Pattern = Pattern.compile(
        "p2pmessenger://group[?&=A-Za-z0-9_.%/-]*" +
            "|p2p://group/[A-Za-z0-9_.%/?&=-]*" +
            "|https?://t\\.me/[A-Za-z0-9_]+\\?start=" + TELEGRAM_START_PREFIX + "[A-Za-z0-9]+",
        Pattern.CASE_INSENSITIVE,
    )

    /** Один параметр запроса по имени; null, если его нет или он пустой. */
    private fun queryParam(query: String?, name: String): String? {
        if (query.isNullOrBlank()) return null
        val value = query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].equals(name, ignoreCase = true) }
            ?.get(1)?.trim()
            .orEmpty()
        return value.ifBlank { null }
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
