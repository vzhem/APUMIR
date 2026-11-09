package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.util.readBytesAtMost
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the small, cache-bypassed sidecar, not the potentially large APK. */
internal object UpdateSiteMirror {
    const val APK_URL = "https://apu.clan.su/downloads/app-release.apk"
    private const val METADATA_URL = "https://apu.clan.su/downloads/latest.json"
    private const val TIMEOUT_MS = 5_000
    private const val MAX_METADATA_BYTES = 64L * 1024
    private const val TAG = "UpdateSiteMirror"

    data class Match(val sha256: String?)

    fun currentCopy(release: UpdateChecker.ReleaseInfo, externalAllowed: Boolean): Match? {
        if (!externalAllowed || !release.allowSiteMirror) return null
        var connection: HttpURLConnection? = null
        return try {
            val current = URL("$METADATA_URL?nocache=${System.currentTimeMillis()}")
                .openConnection() as HttpURLConnection
            connection = current
            current.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "APU-Update-Checker")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
            }
            if (current.responseCode != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "Site metadata unavailable: HTTP ${current.responseCode}")
                return null
            }
            val metadata = current.inputStream.use { it.readBytesAtMost(MAX_METADATA_BYTES).toString(Charsets.UTF_8) }
            val json = JSONObject(metadata)
            val siteVersion = json.optString("version", "").trim()
            val siteSha = json.optString("sha256", "").takeIf { it.isNotBlank() }
            if (!siteApkMatchesLatest(siteVersion, release.version, siteSha, release.apkSha256)) {
                Log.i(TAG, "Site copy is stale or its digest differs from the release")
                return null
            }
            Match(normalizedUpdateSha256(siteSha))
        } catch (error: Exception) {
            Log.i(TAG, "Site metadata check failed: ${error.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
