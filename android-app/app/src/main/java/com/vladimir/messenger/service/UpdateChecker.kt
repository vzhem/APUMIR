package com.vladimir.messenger.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверяет наличие обновлений через GitHub Releases API.
 * Скачивает APK через DownloadManager.
 * Запускает установку через Intent.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API = "https://api.github.com/repos"
        private const val HTTP_TIMEOUT = 10000
        
        // TODO: заменить на реальные значения
        private const val GITHUB_USER = "vzhem"
        private const val GITHUB_REPO = "APUMIR"
    }

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String,
    )

    /**
     * Проверить наличие новой версии.
     * @return ReleaseInfo если есть обновление, null если текущая версия актуальна
     */
    suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Checking for updates. Current: $currentVersion")
            
            val url = "$GITHUB_API/$GITHUB_USER/$GITHUB_REPO/releases/latest"
            val response = httpGet(url) ?: return@withContext null
            
            val json = JSONObject(response)
            val latestVersion = json.getString("tag_name")
            
            Log.i(TAG, "Latest version: $latestVersion")
            
            // Сравниваем числовые компоненты версии (11.16 > 11.9).
            if (isVersionNewer(currentVersion, latestVersion)) {
                Log.i(TAG, "New version available: $latestVersion")
                
                // Берём только APK с ожидаемым именем. GitHub не гарантирует порядок
                // assets, поэтому "первый .apk" может оказаться ручной/устаревшей сборкой.
                val assets = json.getJSONArray("assets")
                val canonicalName = "APU-$latestVersion.apk"
                val oldCanonicalName = "P2P-Messenger-$latestVersion.apk"
                var canonicalUrl: String? = null
                var legacyUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    when {
                        name.equals(canonicalName, ignoreCase = true) ||
                            name.equals(oldCanonicalName, ignoreCase = true) -> {
                            canonicalUrl = asset.getString("browser_download_url")
                        }
                        name.equals("app-release.apk", ignoreCase = true) -> {
                            legacyUrl = asset.getString("browser_download_url")
                        }
                    }
                }
                // Older workflows use app-release.apk for the Actions-built artifact.
                // Prefer it when a release also contains a manual canonical upload.
                val downloadUrl = legacyUrl ?: canonicalUrl
                
                if (downloadUrl == null) {
                    Log.w(TAG, "No supported APK asset found in release")
                    return@withContext null
                }
                
                ReleaseInfo(
                    version = latestVersion,
                    downloadUrl = downloadUrl,
                    releaseNotes = json.optString("body", ""),
                    publishedAt = json.optString("published_at", "")
                )
            } else {
                Log.i(TAG, "Current version is up to date")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Check for update skipped (no network)")  // тихая обработка
            null
        }
    }

    /**
     * Скачать APK через DownloadManager.
     * @return ID загрузки
     */
    fun downloadApk(releaseInfo: ReleaseInfo): Long {
        // Имя файла и заголовок уведомления - "APU v11.33.0", а не техническое
        // "P2P-Messenger-...": владелец видит в шторке именно эту строку.
        val version = releaseInfo.version.removePrefix("v")
        val fileName = "APU-v$version.apk"
        val request = DownloadManager.Request(Uri.parse(releaseInfo.downloadUrl)).apply {
            setTitle("APU v$version")
            setDescription("Скачивание обновления APU")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        
        Log.i(TAG, "Download started: ID=$downloadId")
        return downloadId
    }

        /**
     * Установить скачанный APK через FileProvider.
     */
    fun installApk(downloadId: Long) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            
            var apkFile: File? = null
            if (cursor != null && cursor.moveToFirst()) {
                val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val localUri = cursor.getString(localUriIdx)
                cursor.close()
                
                if (localUri != null) {
                    apkFile = try { 
                        File(java.net.URI(localUri)) 
                    } catch (_: Exception) { 
                        File(localUri.removePrefix("file://")) 
                    }
                }
            }
            
            if (apkFile == null || !apkFile.exists()) {
                Log.e(TAG, "Cannot find downloaded APK file (id=$downloadId)")
                return
            }
            
            Log.i(TAG, "Installing APK: ${apkFile.absolutePath} (${apkFile.length() / 1024 / 1024}MB)")
            
            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, apkFile)
            Log.i(TAG, "FileProvider URI: $uri")
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
            Log.i(TAG, "Install intent started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
        }
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "APU-Update-Checker")
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        
        return try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "HTTP GET $url returned $code")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "Network unavailable: ${e.message}")  // тихая обработка, не ошибка
            null
        } finally {
            conn.disconnect()
        }
    }
}

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
