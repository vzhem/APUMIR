package com.vladimir.messenger.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vladimir.messenger.data.diagnostics.UpdateDiagnosticFacts

/**
 * Persists only a small, privacy-safe summary of update checks and notification
 * delivery. Release URLs, notes, and network exception messages are never stored.
 */
object UpdateDiagnosticsStore {
    private const val PREFS = "update_diagnostics"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_CHECK_DURATION = "check_duration_ms"
    private const val KEY_CURRENT_VERSION = "current_version"
    private const val KEY_LATEST_VERSION = "latest_version"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_SOURCE = "source"
    private const val KEY_RESULT = "result"
    private const val KEY_HTTP_STATUS = "http_status"
    private const val KEY_MANUAL_WINDOW = "manual_window"
    private const val KEY_GITHUB_RESULT = "github_result"
    private const val KEY_GITHUB_HTTP_STATUS = "github_http_status"
    private const val KEY_RELAY_RESULT = "relay_result"
    private const val KEY_RELAY_HTTP_STATUS = "relay_http_status"
    private const val KEY_NOTIFICATION_VERSION = "notification_version"
    private const val KEY_NOTIFICATION_KIND = "notification_kind"
    private const val KEY_NOTIFICATION_AT = "notification_at"
    private const val KEY_NOTIFICATION_RESULT = "notification_result"

    internal class CheckTrace(
        val startedAtMs: Long,
        val currentVersion: String,
        val trigger: String,
    ) {
        var latestVersion: String = ""
        var source: String = "none"
        var outcome: String = "checking"
        var httpStatus: Int? = null
        /**
         * Что сделала ручная кнопка с внешним каналом: `not_manual` -
         * авто-проверка, `not_attempted` - ручная, но до канала не дошла,
         * `opened` - открыла внешний канал (живой сосед или ядро не поднято),
         * `already_allowed` - он и так был открыт.
         */
        var manualWindow: String = "not_manual"
        var githubOutcome: String = "not_attempted"
        var githubHttpStatus: Int? = null
        var relayOutcome: String = "not_attempted"
        var relayHttpStatus: Int? = null
    }

    internal fun begin(context: Context, currentVersion: String, trigger: String): CheckTrace {
        val startedAt = System.currentTimeMillis()
        val trace = CheckTrace(startedAt, safeVersion(currentVersion), safeTrigger(trigger))
        // Ручная проверка, оборвавшаяся до запроса (ядро не поднято), не должна
        // выглядеть в «Логах» как авто-проверка.
        trace.manualWindow = if (trace.trigger == "manual") "not_attempted" else "not_manual"
        prefs(context).edit()
            .putLong(KEY_LAST_CHECK_AT, 0L)
            .putLong(KEY_CHECK_DURATION, -1L)
            .putString(KEY_CURRENT_VERSION, trace.currentVersion)
            .putString(KEY_LATEST_VERSION, "")
            .putString(KEY_TRIGGER, trace.trigger)
            .putString(KEY_SOURCE, "none")
            .putString(KEY_RESULT, "checking")
            .putString(KEY_MANUAL_WINDOW, trace.manualWindow)
            .putString(KEY_GITHUB_RESULT, "not_attempted")
            .putString(KEY_RELAY_RESULT, "not_attempted")
            .remove(KEY_HTTP_STATUS)
            .remove(KEY_GITHUB_HTTP_STATUS)
            .remove(KEY_RELAY_HTTP_STATUS)
            .apply()
        return trace
    }

    internal fun markHttp(trace: CheckTrace, source: String, status: Int) {
        val safeSource = if (source == "relay") "relay" else "github"
        trace.source = safeSource
        trace.httpStatus = status.takeIf { it in 100..599 }
        when (safeSource) {
            "github" -> trace.githubHttpStatus = trace.httpStatus
            "relay" -> trace.relayHttpStatus = trace.httpStatus
        }
        val result = if (status == 200) "response_received" else "http_error"
        markSource(trace, safeSource, result)
    }

    internal fun markSource(
        trace: CheckTrace,
        source: String,
        result: String,
        latestVersion: String? = null,
    ) {
        val safeSource = if (source == "relay") "relay" else "github"
        val safeResult = safeOutcome(result)
        // A scheduled relay check that is not due is not an endpoint attempt;
        // preserve the source/status of the GitHub request that led to fallback.
        if (!(safeSource == "relay" && safeResult == "not_due")) trace.source = safeSource
        if (latestVersion != null) {
            val candidate = safeVersion(latestVersion)
            if (candidate.isNotBlank() &&
                (trace.latestVersion.isBlank() || isVersionNewer(trace.latestVersion, candidate))
            ) {
                trace.latestVersion = candidate
            }
        }
        when (safeSource) {
            "github" -> trace.githubOutcome = safeResult
            "relay" -> trace.relayOutcome = safeResult
        }
        trace.outcome = safeResult
    }

    internal fun markNetworkFailure(trace: CheckTrace, source: String) {
        trace.httpStatus = null
        markSource(trace, source, "network_error")
    }

    internal fun finish(context: Context, trace: CheckTrace) {
        val finishedAt = System.currentTimeMillis()
        val editor = prefs(context).edit()
            .putLong(KEY_LAST_CHECK_AT, finishedAt)
            .putLong(KEY_CHECK_DURATION, (finishedAt - trace.startedAtMs).coerceAtLeast(0L))
            .putString(KEY_CURRENT_VERSION, trace.currentVersion)
            .putString(KEY_LATEST_VERSION, trace.latestVersion)
            .putString(KEY_TRIGGER, trace.trigger)
            .putString(KEY_SOURCE, safeSource(trace.source))
            .putString(KEY_RESULT, safeOutcome(trace.outcome))
            .putString(KEY_MANUAL_WINDOW, safeManualWindow(trace.manualWindow))
            .putString(KEY_GITHUB_RESULT, safeOutcome(trace.githubOutcome))
            .putString(KEY_RELAY_RESULT, safeOutcome(trace.relayOutcome))
        trace.httpStatus?.let { editor.putInt(KEY_HTTP_STATUS, it) } ?: editor.remove(KEY_HTTP_STATUS)
        trace.githubHttpStatus?.let { editor.putInt(KEY_GITHUB_HTTP_STATUS, it) }
            ?: editor.remove(KEY_GITHUB_HTTP_STATUS)
        trace.relayHttpStatus?.let { editor.putInt(KEY_RELAY_HTTP_STATUS, it) }
            ?: editor.remove(KEY_RELAY_HTTP_STATUS)
        editor.apply()
    }

    fun recordNotification(context: Context, version: String, kind: String, result: String) {
        prefs(context).edit()
            .putString(KEY_NOTIFICATION_VERSION, safeVersion(version))
            .putString(KEY_NOTIFICATION_KIND, if (kind == "peer") "peer" else "official")
            .putLong(KEY_NOTIFICATION_AT, System.currentTimeMillis())
            .putString(KEY_NOTIFICATION_RESULT, safeNotificationOutcome(result))
            .apply()
    }

    fun snapshot(context: Context): UpdateDiagnosticFacts {
        val preferences = prefs(context)
        val (allowed, channelEnabled, channelImportance) = notificationState(context)
        return UpdateDiagnosticFacts(
            currentVersion = preferences.getString(KEY_CURRENT_VERSION, "").orEmpty(),
            latestVersion = preferences.getString(KEY_LATEST_VERSION, "").orEmpty(),
            lastCheckAtMs = preferences.getLong(KEY_LAST_CHECK_AT, 0L),
            checkDurationMs = preferences.getLong(KEY_CHECK_DURATION, -1L).takeIf { it >= 0L },
            trigger = preferences.getString(KEY_TRIGGER, "never").orEmpty(),
            source = preferences.getString(KEY_SOURCE, "none").orEmpty(),
            outcome = preferences.getString(KEY_RESULT, "never_checked").orEmpty(),
            manualExternalWindow = preferences.getString(KEY_MANUAL_WINDOW, "unknown").orEmpty(),
            httpStatus = preferences.getInt(KEY_HTTP_STATUS, -1).takeIf { it in 100..599 },
            githubOutcome = preferences.getString(KEY_GITHUB_RESULT, "not_attempted").orEmpty(),
            githubHttpStatus = preferences.getInt(KEY_GITHUB_HTTP_STATUS, -1).takeIf { it in 100..599 },
            relayOutcome = preferences.getString(KEY_RELAY_RESULT, "not_attempted").orEmpty(),
            relayHttpStatus = preferences.getInt(KEY_RELAY_HTTP_STATUS, -1).takeIf { it in 100..599 },
            notificationVersion = preferences.getString(KEY_NOTIFICATION_VERSION, "").orEmpty(),
            notificationKind = preferences.getString(KEY_NOTIFICATION_KIND, "never").orEmpty(),
            notificationAtMs = preferences.getLong(KEY_NOTIFICATION_AT, 0L),
            notificationResult = preferences.getString(KEY_NOTIFICATION_RESULT, "never").orEmpty(),
            notificationsAllowed = allowed,
            notificationChannelEnabled = channelEnabled,
            notificationChannelImportance = channelImportance,
        )
    }

    private fun notificationState(context: Context): Triple<Boolean?, Boolean?, Int?> {
        val allowed = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
        }.getOrNull()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Triple(allowed, allowed, null)
        val channel = runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(UpdateNotifier.CHANNEL_ID)
        }.getOrNull() ?: return Triple(allowed, null, null)
        return Triple(
            allowed,
            channel.importance != NotificationManager.IMPORTANCE_NONE,
            channel.importance,
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun safeVersion(value: String): String =
        value.filter { it.isLetterOrDigit() || it in ".+-_" }.take(64)

    private fun safeTrigger(value: String): String = when (value) {
        "manual", "foreground", "background" -> value
        else -> "automatic"
    }

    private fun safeSource(value: String): String = when (value) {
        "github", "relay" -> value
        else -> "none"
    }

    internal fun safeOutcome(value: String): String = when (value) {
        "checking", "response_received", "update_available", "up_to_date", "http_error", "network_error",
        "invalid_response", "asset_missing", "not_due", "unavailable", "fallback_not_due",
        "never_checked", "not_attempted", "swarm_first_no_http", "swarm_search_pending",
        "swarm_available" -> value
        else -> "unknown"
    }

    /**
     * «swarm_first_no_http» и «swarm_search_pending» раньше не были в списке и
     * превращались в «unknown»: по «Логам» нельзя было понять, что проверку
     * остановил рой, а не сеть. Теперь причина видна как есть.
     */
    internal fun safeManualWindow(value: String): String = when (value) {
        "not_manual", "not_attempted", "already_allowed", "opened" -> value
        else -> "unknown"
    }

    private fun safeNotificationOutcome(value: String): String = when (value) {
        "posted", "already_notified", "permission_blocked", "channel_blocked", "post_failed", "never" -> value
        else -> "unknown"
    }
}
