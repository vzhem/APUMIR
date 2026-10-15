package com.vladimir.messenger.service

/** GitHub outages/invalid responses may use the relay; a valid «current» is final. */
internal fun shouldTryRelayFallback(githubOutcome: String): Boolean =
    githubOutcome != "up_to_date" && githubOutcome != "update_available"

/** Pure outcome mapping, kept separate so fallback behavior is unit-testable. */
internal fun resolveUpdateCheckOutcome(
    releaseFound: Boolean,
    githubOutcome: String,
    relayOutcome: String,
): String {
    val relayConfirmsCurrent = relayOutcome == "up_to_date" &&
        githubOutcome in setOf("network_error", "http_error", "invalid_response")
    return when {
        releaseFound -> "update_available"
        githubOutcome == "up_to_date" || relayConfirmsCurrent -> "up_to_date"
        relayOutcome == "not_due" -> "fallback_not_due"
        else -> "unavailable"
    }
}

/**
 * Окно ручной проверки: кнопка «Проверить новую версию» получила разрешение
 * владельца спрашивать внешний канал даже при живом соседе (авто-проверки
 * остаются «рой прежде внешнего»). Живёт ограниченное время, чтобы дальше снова
 * действовал рой. Чистые функции - правило проверяется без телефона.
 */
internal fun manualExternalWindowActive(nowMs: Long, untilMs: Long): Boolean = nowMs < untilMs

/** Продление окна: уже открытое окно не укорачивается повторным нажатием. */
internal fun manualExternalWindowUntil(nowMs: Long, currentUntilMs: Long, windowMs: Long): Long =
    maxOf(currentUntilMs, nowMs + windowMs)

/**
 * Скачивание официального релиза разрешено, если ядро открыло внешний канал само
 * (рядом нет живого подписанного соседа) либо действует окно ручной кнопки:
 * человек сам попросил обновление в интернете.
 */
internal fun externalReleaseAllowed(transportAllowed: Boolean, manualWindowActive: Boolean): Boolean =
    transportAllowed || manualWindowActive

/** Numeric comparison: `v11.16` is newer than `v11.9`, regardless of string order. */
internal fun isVersionNewer(current: String, latest: String): Boolean {
    fun parse(value: String): List<Int>? {
        val normalized = value
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')

        if (normalized.isBlank()) return null
        return normalized.split('.').map { part ->
            part.toIntOrNull() ?: return null
        }
    }

    val currentParts = parse(current) ?: return false
    val latestParts = parse(latest) ?: return false
    val componentCount = maxOf(currentParts.size, latestParts.size)

    for (index in 0 until componentCount) {
        val currentPart = currentParts.getOrElse(index) { 0 }
        val latestPart = latestParts.getOrElse(index) { 0 }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}
