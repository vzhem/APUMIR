package com.vladimir.messenger.service

import com.vladimir.messenger.data.update.ApkUpdate

/**
 * A static uCoz APK is trusted only when its small sidecar identifies the same
 * release as the authoritative GitHub/relay metadata. If the official release
 * has a digest, the sidecar must carry the same SHA-256 too.
 */
internal fun siteApkMatchesLatest(
    siteVersion: String,
    latestVersion: String,
    siteSha256: String?,
    latestSha256: String?,
): Boolean {
    if (!ApkUpdate.isSame(siteVersion, latestVersion)) return false
    val expectedSha = normalizedUpdateSha256(latestSha256) ?: return latestSha256.isNullOrBlank()
    return normalizedUpdateSha256(siteSha256) == expectedSha
}

private fun normalizedUpdateSha256(value: String?): String? {
    val raw = value?.trim().orEmpty()
    val digest = (if (raw.startsWith("sha256:", ignoreCase = true)) raw.substringAfter(':') else raw)
        .lowercase()
    return digest.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
}

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
 * Причины проверки обновления, которые можно писать в «Логи» как есть: всё
 * остальное сохраняется словом «unknown». Правило живёт здесь, а не в
 * [UpdateDiagnosticsStore], по двум причинам: это чистая логика без Android, и
 * её гоняет CI без Android SDK (`scripts/ci/check-chat-history.sh`) — иначе
 * `swarm_first_no_http` и `swarm_search_pending` молча превращались в «unknown»,
 * и по «Логам» нельзя было понять, что проверку остановил рой, а не сеть.
 */
internal fun safeUpdateOutcome(value: String): String = when (value) {
    "checking", "response_received", "update_available", "up_to_date", "http_error", "network_error",
    "invalid_response", "asset_missing", "not_due", "unavailable", "fallback_not_due",
    "never_checked", "not_attempted", "swarm_first_no_http", "swarm_search_pending",
    "swarm_available" -> value
    else -> "unknown"
}

/** Окно ручной проверки: по нему видно, кто дал добро смотреть внешний канал. */
internal fun safeManualWindow(value: String): String = when (value) {
    "not_manual", "not_attempted", "already_allowed", "opened" -> value
    else -> "unknown"
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

/** Единая форма версии для защиты от повторного уведомления: `v` и хвосты не создают дубль. */
internal fun normalizeUpdateVersion(version: String): String =
    version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')

private fun isComparableUpdateVersion(version: String): Boolean =
    version.isNotBlank() && version.split('.').all { it.toIntOrNull() != null }

/**
 * Почему уведомление о версии можно пропустить. `null` — показываем.
 *
 * Правило чистое и проверяется JVM-тестами: не уведомляем о не более новой
 * версии, не дублируем уже показанную (даже если пришёл другой вариант написания `v`/суффикс)
 * и не откатываемся на старую версию после более новой. Заблокированное
 * разрешение/канал сюда не попадает: такая попытка не должна «съедать» версию.
 */
internal fun updateNotificationSkipReason(
    currentVersion: String,
    candidateVersion: String,
    lastNotifiedVersion: String,
): String? {
    val candidate = normalizeUpdateVersion(candidateVersion)
    if (candidate.isBlank() || !isComparableUpdateVersion(candidate)) return "invalid_version"
    if (currentVersion.isNotBlank() && !isVersionNewer(currentVersion, candidateVersion)) return "not_newer"
    val last = normalizeUpdateVersion(lastNotifiedVersion)
    if (last.isBlank()) return null
    if (last == candidate) return "already_notified"
    if (!isComparableUpdateVersion(last)) return null
    return if (isVersionNewer(candidate, last)) "stale_version" else null
}

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
