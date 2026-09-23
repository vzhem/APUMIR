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

        /**
         * Наш relay-домен (тот же, что раздаёт приглашения и короткие ссылки:
         * он у приложения и так живой). На мобильном интернете с белым списком
         * GitHub недоступен, а этот хост сеть пускает — worker сам ходит на
         * GitHub и отдаёт сведения (`/update/latest`) и APK (`/update/apk`)
         * со своего домена. Исходник — tools/worker/p2p_relay_worker.js.
         */
        private const val RELAY_BASE = "https://p2p-relay.1985vzhem.workers.dev"
    }

    /**
     * Раунд 133: патч успешно применён и проверен — НЕ удаляем его, а
     * отдаём рой-слою (ApkSeeder вешает хук при старте), чтобы телефон
     * раздавал разницу соседям. Вызов синхронный и быстрый: сохранить
     * файл и запомнить метаданные; вся раздача — в фоне у сида.
     */
    var onPatchKept: ((patchFile: File, fromVersion: String, toVersion: String, apkSha256: String) -> Unit)? = null

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String,
        /** Раунд 132: дифф-патч «APUBSP1» - качаем разницу, а не весь APK. */
        val patchUrl: String? = null,
        /** С какой версии применим патч (нормализованная, «11.74.27»). */
        val patchFrom: String? = null,
        /** SHA-256 релизного APK (из GitHub digest) - сверка собранного. */
        val apkSha256: String? = null,
    )

    /** Завершённая загрузка APK: наш файл обновления из DownloadManager. */
    data class CompletedDownload(
        val downloadId: Long,
        val title: String,
        val file: File,
    )

    /**
     * Успешные загрузки APK по данным DownloadManager (наши «APU v…» и любой
     * другой APK, который мы сами ставили в очередь). Раздел «Обновления»
     * забирает их автоматически: файл сам встаёт в карточку и начинает
     * раздаваться соседям (docs/UPDATE_SEEDING.md).
     */
    fun completedApkDownloads(): List<CompletedDownload> {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return emptyList()
        val result = mutableListOf<CompletedDownload>()
        runCatching {
            val cursor = manager.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleIdx = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val uriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val mimeIdx = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                while (it.moveToNext()) {
                    val mime = if (mimeIdx >= 0) it.getString(mimeIdx).orEmpty() else ""
                    val title = if (titleIdx >= 0) it.getString(titleIdx).orEmpty() else ""
                    val isApk = mime == "application/vnd.android.package-archive" ||
                        title.startsWith("APU v")
                    if (!isApk) continue
                    if (uriIdx < 0) continue
                    val localUri = it.getString(uriIdx) ?: continue
                    val file = runCatching { File(java.net.URI(localUri)) }
                        .getOrElse { File(localUri.removePrefix("file://")) }
                    if (file.isFile) result += CompletedDownload(it.getLong(idIdx), title, file)
                }
            }
        }.onFailure { Log.w(TAG, "query completed downloads failed: ${it.message}") }
        return result
    }

    /**
     * Проверить наличие новой версии.
     * @return ReleaseInfo если есть обновление, null если текущая версия актуальна
     *
     * Два пути: (1) напрямую GitHub Releases API; (2) если GitHub недоступен
     * (мобильный интернет с белым списком хостов, лимит запросов, нет сети) —
     * через наш relay-домен: worker ходит на GitHub сам и отвечает тем же
     * сведениями, а APK отдаёт со своего домена (/update/apk). Так обновление
     * находится и скачивается без VPN в жёстких сетях.
     */
    suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        checkGitHub(currentVersion) ?: checkViaRelay(currentVersion)
    }

    /** Прямой запрос к GitHub Releases API (как раньше; null — недоступен). */
    private suspend fun checkGitHub(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
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
                var apkSha256: String? = null
                var patchUrl: String? = null
                var patchFrom: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    when {
                        name.equals(canonicalName, ignoreCase = true) ||
                            name.equals(oldCanonicalName, ignoreCase = true) -> {
                            canonicalUrl = asset.getString("browser_download_url")
                            // GitHub отдаёт «sha256:<hex>» - пригодится патчу.
                            apkSha256 = asset.optString("digest", "")
                                .removePrefix("sha256:").takeIf { it.length == 64 }
                        }
                        name.equals("app-release.apk", ignoreCase = true) -> {
                            legacyUrl = asset.getString("browser_download_url")
                            if (apkSha256 == null) {
                                apkSha256 = asset.optString("digest", "")
                                    .removePrefix("sha256:").takeIf { it.length == 64 }
                            }
                        }
                        name.startsWith("patch-") && name.endsWith(".bspatch") -> {
                            // patch-11.74.27-to-11.74.28.bspatch
                            val body = name.removePrefix("patch-")
                                .removeSuffix(".bspatch")
                            val from = body.substringBefore("-to-")
                            val to = body.substringAfter("-to-", "")
                            if (to == com.vladimir.messenger.data.update.ApkUpdate
                                .normalize(latestVersion)
                            ) {
                                patchUrl = asset.getString("browser_download_url")
                                patchFrom = from
                            }
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
                    publishedAt = json.optString("published_at", ""),
                    patchUrl = patchUrl,
                    patchFrom = patchFrom,
                    apkSha256 = apkSha256,
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
     * Запасной путь: тот же вопрос через наш relay-домен. Отвечает worker
     * (`/update/latest`): tag_name, notes, published_at и apk_url — ссылка
     * на `/update/apk` того же домена, чтобы скачивание тоже прошло сквозь
     * белый список. null — relay недоступен или версии нет/не новее.
     */
    private suspend fun checkViaRelay(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$RELAY_BASE/update/latest") ?: return@withContext null
            val json = JSONObject(response)
            val latestVersion = json.optString("tag_name", "")
            if (latestVersion.isBlank() || !isVersionNewer(currentVersion, latestVersion)) {
                Log.i(TAG, "Relay: no newer version (${latestVersion.ifBlank { "?" }})")
                return@withContext null
            }
            val apkUrl = json.optString("apk_url", "").ifBlank { "$RELAY_BASE/update/apk" }
            Log.i(TAG, "New version available via relay: $latestVersion")
            ReleaseInfo(
                version = latestVersion,
                downloadUrl = apkUrl,
                releaseNotes = json.optString("notes", ""),
                publishedAt = json.optString("published_at", "")
            )
        } catch (e: Exception) {
            Log.d(TAG, "Relay update check skipped: ${e.message}")
            null
        }
    }

    /**
     * Скачать APK через DownloadManager.
     * @return ID загрузки
     */
    fun downloadApk(releaseInfo: ReleaseInfo): Long {
        // Раунд 132: если мой телефон ровно той версии, от которой сделан
        // дифф-патч, - качаем РАЗНИЦУ (в разы меньше APK), собираем полный
        // файл у себя, сверяем sha256 релиза и сразу запускаем установку.
        // Не вышло (патча нет, база не та, сборка не сошлась) - обычное
        // скачивание целого APK.
        val currentNormalized = com.vladimir.messenger.data.update.ApkUpdate
            .normalize(currentAppVersion())
        if (releaseInfo.patchUrl != null &&
            releaseInfo.patchFrom == currentNormalized &&
            releaseInfo.apkSha256 != null
        ) {
            val patched = runCatching { downloadViaPatch(releaseInfo) }.getOrDefault(false)
            if (patched) {
                Log.i(TAG, "Delta update applied: v${releaseInfo.version.removePrefix("v")}")
                return -1L
            }
            Log.w(TAG, "Delta update failed - falling back to full APK")
        }
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

    /** Установленная версия («11.74.27») для проверки применимости патча. */
    private fun currentAppVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * Раунд 132: скачать дифф-патч, собрать полный APK из установленного,
     * сверить sha256 релиза и запустить установщик. false - не вышло
     * (позовём обычное скачивание).
     */
    private fun downloadViaPatch(releaseInfo: ReleaseInfo): Boolean {
        val patchUrl = releaseInfo.patchUrl ?: return false
        val expectedSha = releaseInfo.apkSha256 ?: return false
        val base = File(context.applicationInfo.sourceDir ?: return false)
        if (!base.isFile) return false
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val patchFile = File(dir, "update.bspatch")
        val out = File(dir, "APU-v" + releaseInfo.version.removePrefix("v") + ".apk")

        // Скачать патч (в разы меньше APK).
        val conn = (URL(patchUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT
            readTimeout = 60000
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "patch download: HTTP ${conn.responseCode}")
                return false
            }
            conn.inputStream.use { input ->
                patchFile.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        } finally {
            conn.disconnect()
        }
        Log.i(TAG, "Patch downloaded: ${patchFile.length() / 1024} KB (base ${base.length() / 1024 / 1024} MB)")

        // Собрать новый APK и сверить с релизом.
        if (!com.vladimir.messenger.data.update.ApkDiffPatch.apply(base, patchFile, out)) {
            Log.w(TAG, "patch apply failed")
            runCatching { out.delete() }
            return false
        }
        val actualSha = com.vladimir.messenger.data.update.ApkDiffPatch.sha256OfFile(out)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            Log.w(TAG, "patched apk sha mismatch")
            runCatching { out.delete() }
            return false
        }
        // Раунд 133: патч больше не удаляем — он становится раздачей
        // для соседей с той же исходной версией (только разница по рою).
        runCatching {
            onPatchKept?.invoke(
                patchFile,
                releaseInfo.patchFrom ?: "",
                com.vladimir.messenger.data.update.ApkUpdate.normalize(releaseInfo.version),
                expectedSha,
            )
        }

        // Запустить установку того же пути, что и рой-обновления.
        return runCatching {
            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, out)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            Log.e(TAG, "patch install intent failed: ${error.message}")
            false
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
