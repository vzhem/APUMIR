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
 *  - https://p2p-relay.1985vzhem.workers.dev/i?slug=<slug> (длинная веб-ссылка)
 *  - https://t.me/p2p_messenger_relay_bot?start=grp_<slug>
 *
 * Для пересылки наружу с версии 11.70.9 используется КОРОТКАЯ ссылка
 * https://<хост>/s/<код> (data.link.ShortLinks): она не показывает ни канал,
 * ни владельца, ни запись. Здесь она не разбирается - её сначала разворачивает
 * сервис (GroupRepository.expandLink), а результат уже разбирает parseTarget.
 */
object GroupInviteLinks {

    const val APP_LINK_PREFIX = "p2pmessenger://group?slug="
    const val SHORT_LINK_PREFIX = "p2p://group/"

    /**
     * Хост нашего сервиса. Он отдаёт страницу `/i` (открыть в APU или
     * установить) и `/.well-known/assetlinks.json`, по которому Android
     * убеждается, что ссылки этого хоста можно отдавать APU без вопросов.
     * Тот же хост стоит в intent-filter манифеста - менять только вместе.
     */
    const val WEB_HOST = "p2p-relay.1985vzhem.workers.dev"

    /** Официальный домен подписанных приглашений (util.ReferralInviteLink). */
    const val OFFICIAL_HOST = "apumir.app"

    /**
     * Веб-адрес для пересылки наружу. Ведёт на наш сервис: он либо открывает
     * APU, либо предлагает установить его.
     */
    const val WEB_LINK_PREFIX = "https://$WEB_HOST/i?slug="
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
        /**
         * Тема поста: ссылка ведёт не просто в канал, а к конкретной записи.
         * Нужна для «поделиться постом» - получатель подписывается и сразу
         * попадает на нужный пост, а не в начало ленты.
         */
        postTopicId: String? = null,
    ): String {
        val sb = StringBuilder(APP_LINK_PREFIX).append(slug)
        if (!groupId.isNullOrBlank()) sb.append("&g=").append(groupId)
        if (!ownerId.isNullOrBlank()) sb.append("&o=").append(ownerId)
        if (!postTopicId.isNullOrBlank()) sb.append("&p=").append(postTopicId)
        // Признаки нужны вступающему телефону: по ним он пишет правду -
        // «заявка отправлена» или «входим сразу», и называет канал каналом.
        // Владелец им не доверяет и проверяет всё по своей базе.
        if (isChannel) sb.append("&c=1")
        if (requestApproval) sb.append("&a=1")
        return sb.toString()
    }

    /**
     * Ссылка для ПЕРЕСЫЛКИ в чужие мессенджеры.
     *
     * Схема `p2pmessenger://` в них не кликабельна: они подсвечивают только
     * http(s), поэтому пересланный пост выглядел мёртвым текстом. Здесь
     * обычный веб-адрес - он подсвечивается везде.
     *
     * Открывает его наш же сервис: если APU установлен, Android перехватит
     * адрес и откроет приложение сразу на посте; если нет - человек попадёт
     * на страницу с кнопкой установки, а после установки та же ссылка
     * доведёт его до канала и записи.
     */
    fun buildWebLink(
        slug: String,
        groupId: String? = null,
        ownerId: String? = null,
        isChannel: Boolean = false,
        postTopicId: String? = null,
        requestApproval: Boolean = false,
    ): String {
        val sb = StringBuilder(WEB_LINK_PREFIX).append(slug)
        if (!groupId.isNullOrBlank()) sb.append("&g=").append(groupId)
        if (!ownerId.isNullOrBlank()) sb.append("&o=").append(ownerId)
        if (!postTopicId.isNullOrBlank()) sb.append("&p=").append(postTopicId)
        if (isChannel) sb.append("&c=1")
        if (requestApproval) sb.append("&a=1")
        return sb.toString()
    }

    /**
     * Та же ссылка в основном виде `p2pmessenger://group?…` из любой
     * поддерживаемой формы. Именно её прячем за коротким кодом: у сервиса
     * код - отпечаток строки, и одинаковые приглашения в разной записи
     * должны давать один и тот же код. Null, если строка не приглашение.
     */
    fun toDeepLink(raw: String?): String? {
        val target = parseTarget(raw) ?: return null
        return build(
            slug = target.slug,
            groupId = target.groupId,
            ownerId = target.ownerId,
            isChannel = target.isChannel,
            requestApproval = target.needsApproval,
            postTopicId = target.postTopicId,
        )
    }

    /**
     * Та же ссылка в веб-виде для пересылки: из любой поддерживаемой формы
     * (обычно p2pmessenger://group?…). Null, если строка не приглашение.
     * Запасной путь, когда короткую ссылку получить не удалось.
     */
    fun toWebLink(raw: String?): String? {
        val target = parseTarget(raw) ?: return null
        return buildWebLink(
            slug = target.slug,
            groupId = target.groupId,
            ownerId = target.ownerId,
            isChannel = target.isChannel,
            postTopicId = target.postTopicId,
            requestApproval = target.needsApproval,
        )
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
        /** Ссылка ведёт к конкретному посту канала, а не просто в канал. */
        val postTopicId: String? = null,
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
                postTopicId = queryParam(query, "p"),
            )
        }

        return try {
            val uri = URI(text)
            val slug = when (uri.scheme?.lowercase()) {
                "p2pmessenger" -> {
                    if (!uri.host.equals("group", ignoreCase = true)) return null
                    parseQuerySlug(uri.rawQuery)
                }
                "https", "http" -> {
                    // Наш веб-адрес для пересылки: /i?slug=...
                    if (isOwnWebHost(uri.host)) {
                        parseQuerySlug(uri.rawQuery)
                    } else {
                        parseTelegramStart(uri.rawQuery)
                    }
                }
                else -> null
            } ?: return null
            InviteTarget(
                slug = slug,
                groupId = queryParam(uri.rawQuery, "g"),
                ownerId = queryParam(uri.rawQuery, "o"),
                isChannel = queryParam(uri.rawQuery, "c") == "1",
                needsApproval = queryParam(uri.rawQuery, "a") == "1",
                postTopicId = queryParam(uri.rawQuery, "p"),
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
            // Веб-ссылка пересылки поста: её тоже копируют вместе с текстом
            // сообщения, и без этой ветки «Войти по ссылке» не находил её.
            "|https://(?:" + Pattern.quote(WEB_HOST) + "|" + Pattern.quote(OFFICIAL_HOST) + ")" +
            "/i\\?[?&=A-Za-z0-9_.%/-]*" +
            "|https?://t\\.me/[A-Za-z0-9_]+\\?start=" + TELEGRAM_START_PREFIX + "[A-Za-z0-9]+",
        Pattern.CASE_INSENSITIVE,
    )

    /** Ссылка на нашем хосте: её разбираем как приглашение, а не как t.me. */
    private fun isOwnWebHost(host: String?): Boolean =
        host.equals(WEB_HOST, ignoreCase = true) || host.equals(OFFICIAL_HOST, ignoreCase = true)

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
