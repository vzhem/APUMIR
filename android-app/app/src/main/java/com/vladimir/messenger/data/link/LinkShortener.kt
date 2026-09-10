package com.vladimir.messenger.data.link

import android.util.Log
import com.vladimir.messenger.service.BotApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Короткие ссылки через наш сервис (worker, `POST /short`, `GET /short/<код>`).
 *
 * Одна точка для всего приложения: коды запоминаются на время жизни процесса
 * (одна и та же ссылка всегда получает один код, второй раз в сеть не ходим),
 * а после отказа сервиса пару минут не пробуем снова - иначе каждая кнопка
 * «Поделиться» ждала бы таймаут. Сеть - внутри [BotApi], всегда в IO.
 */
@Singleton
class LinkShortener @Inject constructor(
    private val botApi: BotApi,
) {
    private val codes = ConcurrentHashMap<String, String>()

    @Volatile
    private var downUntilMs = 0L

    /** Код короткой ссылки для [target] либо null, если сервис недоступен или отказал. */
    suspend fun codeFor(target: String): String? {
        codes[target]?.let { return it }
        if (System.currentTimeMillis() < downUntilMs) return null
        val code = runCatching { botApi.shortenLink(target) }.getOrNull()
        if (code == null || !ShortLinks.isValidCode(code)) {
            downUntilMs = System.currentTimeMillis() + RETRY_MS
            Log.w(TAG, "short link service unavailable, using long links for a while")
            return null
        }
        codes[target] = code
        return code
    }

    /** Короткая ссылка `https://<хост>/s/<код>` для [target] либо null. */
    suspend fun shorten(target: String): String? = codeFor(target)?.let { ShortLinks.build(it) }

    /**
     * Куда ведёт код. Null - сервис недоступен, код неизвестен либо цель не из
     * тех форм, что строит само приложение (чужие адреса не открываем).
     */
    suspend fun expand(code: String): String? {
        if (!ShortLinks.isValidCode(code)) return null
        val target = runCatching { botApi.expandShortLink(code) }.getOrNull() ?: return null
        if (!ShortLinks.isAllowedTarget(target)) {
            Log.w(TAG, "short link target rejected")
            return null
        }
        return target
    }

    /**
     * Короткую ссылку разворачивает в полную, любую другую строку возвращает
     * как есть. Null - короткая, но развернуть не удалось.
     */
    suspend fun expandIfShort(raw: String): String? {
        val code = ShortLinks.codeOf(raw) ?: return raw
        return expand(code)
    }

    private companion object {
        const val TAG = "LinkShortener"
        /** Пауза перед новой попыткой после отказа сервиса. */
        const val RETRY_MS = 2L * 60 * 1000
    }
}
