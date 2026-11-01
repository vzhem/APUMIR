package com.vladimir.messenger.data.diagnostics

/**
 * Раздел «[обновления]» вынесен из общего рендера, чтобы диагностика запуска,
 * разрешения уведомлений и защиты от дублей не раздувала `DiagnosticsReport`.
 *
 * Здесь только безопасные машинные токены: версия, исход, результат. URL релиза,
 * текст заметок, адреса и идентификаторы сюда не попадают.
 */
data class UpdateDiagnosticFacts(
    val currentVersion: String = "",
    val latestVersion: String = "",
    val lastCheckAtMs: Long = 0L,
    val checkDurationMs: Long? = null,
    val trigger: String = "never",
    val source: String = "none",
    val outcome: String = "never_checked",
    val manualExternalWindow: String = "unknown",
    val httpStatus: Int? = null,
    val githubOutcome: String = "not_attempted",
    val githubHttpStatus: Int? = null,
    val relayOutcome: String = "not_attempted",
    val relayHttpStatus: Int? = null,
    val notificationVersion: String = "",
    val notificationKind: String = "never",
    val notificationAtMs: Long = 0L,
    val notificationResult: String = "never",
    val notificationsAllowed: Boolean? = null,
    val notificationChannelEnabled: Boolean? = null,
    val notificationChannelImportance: Int? = null,
    /** Ждал ли стартовый чек готовности ядра и чем это закончилось. */
    val startupCoreReady: Boolean? = null,
    val startupWaitMs: Long? = null,
    /** Последняя сохранённая официальная карточка, ещё более новая чем установка. */
    val pendingOfficialVersion: String = "",
) {
    companion object {
        val EMPTY = UpdateDiagnosticFacts()
    }
}

internal fun StringBuilder.appendUpdateDiagnostics(facts: DiagnosticsFacts) {
    val update = facts.updateDiagnostics
    appendLine("[обновления]")
    appendLine("current_version=${updateToken(update.currentVersion.ifBlank { facts.appVersion.substringBefore(" (") })}")
    appendLine("latest_version=${updateToken(update.latestVersion).ifBlank { "unknown" }}")
    appendLine("last_check_trigger=${updateToken(update.trigger).ifBlank { "unknown" }}")
    appendLine("last_check_source=${updateToken(update.source).ifBlank { "unknown" }}")
    appendLine("last_check_result=${updateToken(update.outcome).ifBlank { "unknown" }}")
    appendLine("last_check_manual_external_window=${updateToken(update.manualExternalWindow).ifBlank { "unknown" }}")
    appendLine("last_check_http_status=${update.httpStatus?.takeIf { it in 100..599 }?.toString() ?: "unknown"}")
    appendLine("github_check_result=${updateToken(update.githubOutcome).ifBlank { "unknown" }}")
    appendLine("github_http_status=${update.githubHttpStatus?.takeIf { it in 100..599 }?.toString() ?: "unknown"}")
    appendLine("relay_check_result=${updateToken(update.relayOutcome).ifBlank { "unknown" }}")
    appendLine("relay_http_status=${update.relayHttpStatus?.takeIf { it in 100..599 }?.toString() ?: "unknown"}")
    if (update.lastCheckAtMs > 0L) {
        appendLine("last_check_at=${DiagnosticsReport.stamp(update.lastCheckAtMs)}")
        val ageSec = ((facts.createdAtMs - update.lastCheckAtMs).coerceAtLeast(0L) / 1000L).toInt()
        appendLine("last_check_ago=${MqttLinkText.humanAgo(ageSec)}")
    } else {
        appendLine("last_check_at=нет")
    }
    appendLine("last_check_duration_ms=${update.checkDurationMs?.coerceAtLeast(0L)?.toString() ?: "unknown"}")
    appendLine("startup_core_ready=${update.startupCoreReady?.toString() ?: "unknown"}")
    appendLine("startup_wait_ms=${update.startupWaitMs?.coerceAtLeast(0L)?.toString() ?: "unknown"}")
    appendLine("pending_official_version=${updateToken(update.pendingOfficialVersion).ifBlank { "none" }}")
    appendLine("last_notification_attempt_version=${updateToken(update.notificationVersion).ifBlank { "none" }}")
    appendLine("last_notification_attempt_kind=${updateToken(update.notificationKind).ifBlank { "unknown" }}")
    appendLine("last_notification_attempt_result=${updateToken(update.notificationResult).ifBlank { "unknown" }}")
    if (update.notificationAtMs > 0L) {
        appendLine("last_notification_attempt_at=${DiagnosticsReport.stamp(update.notificationAtMs)}")
    } else {
        appendLine("last_notification_attempt_at=нет")
    }
    appendLine("notifications_allowed=${update.notificationsAllowed?.toString() ?: "unknown"}")
    appendLine("update_channel_enabled=${update.notificationChannelEnabled?.toString() ?: "unknown"}")
    appendLine("update_channel_importance=${update.notificationChannelImportance?.toString() ?: "unknown"}")
    appendLine()
}

private fun updateToken(value: String): String =
    value.filter { it.isLetterOrDigit() || it in ".+-_" }.take(64)
