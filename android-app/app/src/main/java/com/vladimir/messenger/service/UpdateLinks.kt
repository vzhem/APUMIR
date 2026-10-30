package com.vladimir.messenger.service

import com.vladimir.messenger.data.link.ServiceHost
import java.net.URI

/**
 * Откуда брать установочный файл обновления.
 *
 * Владелец 2026-10-30 прислал ответ сервиса, где `apk_url` вёл на прежний
 * домен: сервис хранил адрес в кэше вместе с версией, а домен за это время
 * сменился (`1985vzhem` → `apuchat`). Телефон послушно шёл качать APK по
 * мёртвому адресу — и обновление просто не начиналось, без внятной ошибки.
 *
 * Поэтому адрес из ответа принимаем только если это https и НАШ хост; любой
 * чужой, устаревший или битый адрес заменяем своим `/update/apk`. Правило
 * чистое (без Android и сети), проверяется JVM-тестом `UpdateLinksTest`.
 */
object UpdateLinks {

    /** Путь, по которому сервис раздаёт APK последнего релиза. */
    private const val APK_PATH = "/update/apk"

    /**
     * @param raw `apk_url` из ответа сервиса (может быть пустым или чужим)
     * @param relayBase корень нашего сервиса
     */
    fun apkDownloadUrl(raw: String?, relayBase: String = ServiceHost.BASE): String {
        val fallback = relayBase.trimEnd('/') + APK_PATH
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return fallback
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return fallback
        if (!parsed.scheme.equals("https", ignoreCase = true)) return fallback
        if (!parsed.host.equals(ServiceHost.HOST, ignoreCase = true)) return fallback
        return candidate
    }
}
