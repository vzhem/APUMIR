package com.vladimir.messenger.util

import com.vladimir.messenger.data.link.ServiceHost

/**
 * Официальный сайт проекта и правило «что можно класть в QR-код знакомства».
 *
 * Владелец 2026-11-19 (скрин «Мой код»): «Нужно чтобы когда показывал qr код,
 * у кого нет приложения — чтобы сразу скачивал с официального сайта приложение
 * и устанавливало. Так как тут показываешь другу лично».
 *
 * В чём была поломка. В QR ехала ссылка `apu://a/<узел>/<ник>` — собственная
 * схема приложения. Телефон без APU такую строку не открывает никак: для
 * камеры это не адрес, а набор символов, и экран при этом обещал «по этому же
 * коду он скачает приложение». Короткая https-ссылка `/s/<код>` подставлялась
 * лишь когда отвечал наш сервис; при любой осечке (сервис молчит, внешний
 * транспорт ещё не разрешён, пауза после отказа) код возвращался к `apu://` —
 * и друг не получал ничего.
 *
 * Правило. В QR попадает только https-адрес, и код контакта не теряется:
 *  * `https://<воркер>/s/<код>` — лучший случай. Код проверен по алгоритму
 *    сервиса (`ShortLinks.matchesWorkerCode`), значит страница существует: у
 *    кого APU стоит, Android откроет приложение сразу на добавлении контакта;
 *    у кого нет — браузер покажет страницу с кнопкой установки;
 *  * иначе страница официального сайта с кодом контакта в параметре:
 *    `https://apu.clan.su/?apu=a/<узел>/<ник>`. Её открывает КАМЕРА любого
 *    телефона — сайт сразу предлагает скачать APK; после установки друг
 *    наводит на этот же код уже сканером APU, и контакт добавляется, потому
 *    что параметр разбирает `contactFromQr`.
 *
 * Адрес сайта один на всё приложение: раньше он жил строкой в настройках
 * (`settings/OfficialSiteItem.kt`), теперь оттуда же берётся и QR. Домен
 * владельца — тот же, с которого раздаются обновления и пакеты функций
 * (`service/UpdateSiteMirror`, `data/featurepacks/FeaturePackSeeder`).
 *
 * Чистый Kotlin без Android: правило проверяется обычным JVM-тестом.
 */
object OfficialSite {

    /** Адрес сайта без завершающей косой черты. */
    const val URL = "https://apu.clan.su"

    /**
     * Параметр с кодом контакта: `?apu=a/<узел base64url>[/<ник>]`.
     *
     * Именно параметр, а не якорь `#…`: якорь браузер встречает прокруткой к
     * несуществующему элементу, и кнопка «Скачать» уезжает с первого экрана —
     * ровно туда, куда друг и смотрит.
     */
    private const val CONTACT_PARAM = "?apu=a/"

    /** Плотная запись узла: 16 или 32 байта в base64url (см. ApuLink.unpackNode). */
    private val PACKED_NODE = Regex("^[A-Za-z0-9_-]{22}$|^[A-Za-z0-9_-]{43}$")

    /** @никнейм — те же правила, что у ApuLink. */
    private val NICK = Regex("^[A-Za-z0-9_]{1,32}$")

    /**
     * Что положить в QR прямо сейчас.
     *
     * @param shortLink короткая https-ссылка нашего сервиса либо null, если он
     *        не ответил или ответил отказом.
     * @param contactCode своя ссылка `apu://a/…`; нужна для страницы сайта и
     *        не используется, когда есть проверенная короткая ссылка.
     */
    fun qrLink(shortLink: String?, contactCode: String?): String {
        val short = shortLink?.trim().orEmpty()
        if (isServicePage(short)) return short
        val contact = contactFragment(contactCode) ?: return URL
        return URL + "/" + CONTACT_PARAM + contact
    }

    /**
     * Ссылка ведёт на страницу нашего сервиса, а не куда попало.
     *
     * Проверяем схему и хост: экран берёт адрес из нескольких источников
     * (короткая ссылка, запасная t.me-ссылка, своя `apu://`), и подсунуть в QR
     * чужой адрес нельзя даже случайно.
     */
    fun isServicePage(link: String?): Boolean {
        val text = link?.trim().orEmpty()
        if (!text.startsWith("https://", ignoreCase = true)) return false
        val rest = text.substring("https://".length)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (!host.equals(ServiceHost.HOST, ignoreCase = true)) return false
        // За хостом обязан идти путь: «https://хост» без косой черты — не страница.
        return rest.length > host.length && rest[host.length] == '/'
    }

    /**
     * Код контакта из адреса страницы сайта — либо null, если его там нет.
     *
     * Возвращает строку в привычном для `InviteLinkParser` виде `apu://a/…`:
     * разбор контакта остаётся в одном месте, а сканер приложения понимает
     * и QR, снятый с экрана «Мой код» после установки.
     */
    fun contactFromQr(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(URL, ignoreCase = true)) return null
        // Слэш перед параметром необязателен: приложение строит адрес с ним
        // (`https://apu.clan.su/?apu=…`), а человек может открыть и без него.
        val tail = text.substring(URL.length).removePrefix("/")
        if (!tail.startsWith(CONTACT_PARAM)) return null
        val contact = "apu://" + ApuLink.HOST_ADD + "/" + tail.substring(CONTACT_PARAM.length)
        return if (contactFragment(contact) == null) null else contact
    }

    /** `apu://a/<узел>[/<ник>]` → `<узел>[/<ник>]`, если запись безопасна. */
    private fun contactFragment(contactCode: String?): String? {
        val text = contactCode?.trim().orEmpty()
        val prefix = ApuLink.SCHEME + "://" + ApuLink.HOST_ADD + "/"
        if (!text.startsWith(prefix)) return null
        val body = text.substring(prefix.length)
        val parts = body.split('/')
        if (parts.size > 2) return null
        if (!PACKED_NODE.matches(parts[0])) return null
        if (parts.size == 2 && !NICK.matches(parts[1])) return null
        return body
    }
}
