package com.vladimir.messenger.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
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
            
            // Сравниваем версии (простое сравнение строк)
            if (isVersionNewer(currentVersion, latestVersion)) {
                Log.i(TAG, "New version available: $latestVersion")
                
                // Найти APK asset
                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                
                if (downloadUrl == null) {
                    Log.w(TAG, "No APK found in release")
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
        val request = DownloadManager.Request(Uri.parse(releaseInfo.downloadUrl)).apply {
            setTitle("P2P Messenger Update")
            setDescription("Скачивание версии ${releaseInfo.version}")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "p2p-messenger-${releaseInfo.version}.apk")
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
            
            // Пытаемся получить локальный путь к файлу
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            
            var apkFile: File? = null
            if (cursor != null && cursor.moveToFirst()) {
                val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val localUri = cursor.getString(localUriIdx)
                cursor.close()
                
                if (localUri != null) {
                    apkFile = try { File(java.net.URI(localUri)) } catch (_: Exception) { File(localUri.removePrefix("file://")) }
                }
            }
            
            if (apkFile == null || !apkFile.exists()) {
                Log.e(TAG, "Cannot find downloaded APK file")
                return
            }
            
            Log.i(TAG, "Installing APK from: ${apkFile.absolutePath}")
            
            // Используем FileProvider для безопасного URI
            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, authority, apkFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            context.startActivity(intent)
            Log.i(TAG, "Install intent started with FileProvider URI")
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
        }
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        // Убираем 'v' если есть
        val c = current.removePrefix("v")
        val l = latest.removePrefix("v")
        
        // Простое сравнение: если строки разные и latest > current
        return l != c && l > c
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "P2P-Messenger-Update-Checker")
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
