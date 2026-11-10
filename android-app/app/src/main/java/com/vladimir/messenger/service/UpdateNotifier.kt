package com.vladimir.messenger.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.R
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Системное уведомление о новой версии APU.
 *
 * Оно отделено от окна в [MainActivity]: проверка может сработать в фоне через
 * WorkManager или прийти от соседнего телефона, когда интерфейс приложения
 * вообще не открыт. Для каждой версии уведомление приходит только один раз,
 * а официальная карточка сохраняется локально, чтобы тап сразу мог открыть
 * обычное окно скачивания.
 */
@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "apu_updates"
        const val EXTRA_OPEN_UPDATE = "extra_open_update"

        private const val TAG = "UpdateNotifier"
        private const val NOTIFICATION_ID = 42_020
        private const val PREFS = "update_notification_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val KEY_OFFICIAL_RELEASE = "official_release"
        private const val KEY_LAST_NOTIFIED_NORMALIZED = "last_notified_normalized"
    }

    private val postLock = Any()

    /** Сохранить официальный релиз и, если он ещё не был показан, уведомить. */
    fun notifyOfficialRelease(release: UpdateChecker.ReleaseInfo) {
        saveOfficialRelease(release)
        postOnce(
            version = release.version,
            kind = "official",
            text = context.getString(R.string.update_notification_official_text, displayVersion(release.version)),
        )
    }

    /**
     * Сосед в рое раздаёт версию новее этой установки. Файл по-прежнему
     * запрашивается только руками из «Настройки → Обновления».
     */
    fun notifyPeerRelease(version: String, compact: Boolean = false) {
        val text = if (compact) {
            context.getString(R.string.update_notification_compact_text, displayVersion(version))
        } else {
            context.getString(R.string.update_notification_peer_text, displayVersion(version))
        }
        postOnce(version = version, kind = "peer", text = text)
    }

    /**
     * Повторить сохранённую официальную карточку после выдачи разрешения.
     * Возвращает карточку, если она ещё новее установленной версии.
     */
    fun notifyPendingOfficialRelease(currentVersion: String): UpdateChecker.ReleaseInfo? {
        val release = pendingOfficialRelease(currentVersion) ?: return null
        notifyOfficialRelease(release)
        return release
    }

    /**
     * Релиз, сохранённый фоновой задачей. Установленная версия сверяется ещё
     * раз, поэтому после успешной установки старое окно не покажется.
     */
    fun pendingOfficialRelease(currentVersion: String): UpdateChecker.ReleaseInfo? {
        val raw = prefs().getString(KEY_OFFICIAL_RELEASE, null) ?: return null
        val release = runCatching {
            val json = JSONObject(raw)
            val savedUrl = json.getString("downloadUrl")
            UpdateChecker.ReleaseInfo(
                version = json.getString("version"),
                downloadUrl = savedUrl,
                releaseNotes = json.optString("releaseNotes", ""),
                publishedAt = json.optString("publishedAt", ""),
                patchUrl = json.optString("patchUrl", "").ifBlank { null },
                patchFrom = json.optString("patchFrom", "").ifBlank { null },
                apkSha256 = json.optString("apkSha256", "").ifBlank { null },
                // All cards are refreshed on the download tap. Keep the legacy
                // flag for compatibility with saved cards from older versions.
                allowSiteMirror = json.optBoolean("allowSiteMirror", false),
            )
        }.getOrElse { error ->
            Log.w(TAG, "Saved release is malformed: ${error.message}")
            prefs().edit().remove(KEY_OFFICIAL_RELEASE).apply()
            return null
        }
        if (!isVersionNewer(currentVersion, release.version)) {
            prefs().edit().remove(KEY_OFFICIAL_RELEASE).apply()
            UpdateDiagnosticsStore.recordPendingOfficial(context, "")
            return null
        }
        UpdateDiagnosticsStore.recordPendingOfficial(context, release.version)
        return release
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_notification_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun saveOfficialRelease(release: UpdateChecker.ReleaseInfo) {
        val json = JSONObject()
            .put("version", release.version)
            .put("downloadUrl", release.downloadUrl)
            .put("releaseNotes", release.releaseNotes)
            .put("publishedAt", release.publishedAt)
            .put("patchUrl", release.patchUrl.orEmpty())
            .put("patchFrom", release.patchFrom.orEmpty())
            .put("apkSha256", release.apkSha256.orEmpty())
            .put("allowSiteMirror", release.allowSiteMirror)
        prefs().edit().putString(KEY_OFFICIAL_RELEASE, json.toString()).apply()
        UpdateDiagnosticsStore.recordPendingOfficial(context, release.version)
    }

    private fun postOnce(version: String, kind: String, text: String) {
        synchronized(postLock) {
            val prefs = prefs()
            val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_NORMALIZED, null)
                ?: prefs.getString(KEY_LAST_NOTIFIED_VERSION, null).orEmpty()
            val skip = updateNotificationSkipReason(currentAppVersion(), version, lastNotified)
            if (skip != null) {
                UpdateDiagnosticsStore.recordNotification(context, version, kind, skip)
                return
            }
            if (!canPostNotifications()) {
                Log.i(TAG, "Update ${displayVersion(version)} found, notification permission is unavailable")
                UpdateDiagnosticsStore.recordNotification(context, version, kind, "permission_blocked")
                return
            }

            ensureChannel()
            if (!isUpdateChannelEnabled()) {
                UpdateDiagnosticsStore.recordNotification(context, version, kind, "channel_blocked")
                return
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
                prefs.edit()
                    .putString(KEY_LAST_NOTIFIED_VERSION, version)
                    .putString(KEY_LAST_NOTIFIED_NORMALIZED, normalizeUpdateVersion(version))
                    .apply()
                UpdateDiagnosticsStore.recordNotification(context, version, kind, "posted")
            }.onFailure { error ->
                Log.w(TAG, "Could not post update notification: ${error.message}")
                UpdateDiagnosticsStore.recordNotification(context, version, kind, "post_failed")
            }
        }
    }

    private fun currentAppVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrElse { error ->
        Log.w(TAG, "Could not read installed version for update notification: ${error.message}")
        ""
    }

    private fun isUpdateChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun displayVersion(version: String): String =
        if (version.startsWith("v", ignoreCase = true)) version else "v$version"
}
