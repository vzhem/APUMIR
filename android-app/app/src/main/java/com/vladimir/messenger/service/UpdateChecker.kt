package com.vladimir.messenger.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.update.ApkUpdate
import com.vladimir.messenger.data.update.ApkUpdateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        /**
         * Раунд 195 (план разгрузки, шаг 4): авто-проверка версии через наш
         * воркер - не чаще раза в 12 часов + случайный сдвиг 0-6 часов
         * (у каждой установки свой, против «стада» на сбросе лимита в
         * полночь UTC). Ручная проверка (кнопки) - без ограничений.
         * О новой версии рой и так сообщает сам (авто-upask, р193), сайт -
         * ускоритель. Пытаемся редко даже при НЕудаче: недоступный воркер
         * не долбим.
         */
        private const val RELAY_CHECK_PREFS = "update_check_prefs"
        private const val KEY_RELAY_CHECK_AT = "relay_check_at"
        private const val KEY_RELAY_CHECK_JITTER = "relay_check_jitter"
        private const val RELAY_CHECK_MIN_MS = 12L * 60 * 60 * 1000
        private const val RELAY_CHECK_JITTER_MS = 6L * 60 * 60 * 1000

        /**
         * Окно, которое открывает РУЧНАЯ проверка (кнопка «Проверить новую
         * версию»). Владелец разрешил кнопке спрашивать внешний канал даже
         * тогда, когда рядом живой сосед: иначе правило «рой прежде внешнего»
         * закрывало запрос, строка «Скачать v…» не появлялась вовсе, и раздел
         * «Обновления» на таком телефоне выглядел неполным. Окно ограничено:
         * человек успевает увидеть версию и запустить загрузку, после чего
         * снова действует рой. Автоматические проверки (foreground, background,
         * авто-опрос) окно НЕ открывают.
         */
        private const val MANUAL_WINDOW_MS = 30L * 60_000L

        /**
         * Сколько окно живёт после начала ручной загрузки APK. Файл ~40 МБ на
         * слабом LTE качается дольше получаса, а DownloadManager работает в
         * другом процессе: обрыв загрузки на половине выглядел бы как
         * «обновление не работает».
         */
        private const val MANUAL_DOWNLOAD_WINDOW_MS = 90L * 60_000L
        private const val MANUAL_WINDOW_PREFS = "update_manual_window"
        private const val KEY_MANUAL_WINDOW_UNTIL = "until_wall_ms"
    }

    /**
     * Раунд 133: патч успешно применён и проверен — НЕ удаляем его, а
     * отдаём рой-слою (ApkSeeder вешает хук при старте), чтобы телефон
     * раздавал разницу соседям. Вызов синхронный и быстрый: сохранить
     * файл и запомнить метаданные; вся раздача — в фоне у сида.
     */
    var onPatchKept: ((patchFile: File, fromVersion: String, toVersion: String, apkSha256: String) -> Unit)? = null

    /** Installed by ApkSeeder (which already owns the signed peer update protocol). */
    @Volatile var askSwarmForUpdate: (suspend () -> Int)? = null

    @Volatile private var scannedInCurrentSwarmWindow = false

    /**
     * До какого момента (настенные часы) ручная кнопка разрешила внешний канал.
     * -1 - ещё не читали из prefs, 0 - окно закрыто, обычное правило «рой прежде
     * внешнего». Значение переживает перезапуск процесса: загрузку APK ведёт
     * DownloadManager отдельным процессом, и появление соседа не должно сносить
     * её из-за того, что наш процесс перезапустился.
     */
    @Volatile private var manualExternalUntilMs = -1L

    private fun storedManualWindowUntil(): Long {
        val cached = manualExternalUntilMs
        if (cached >= 0L) return cached
        val stored = runCatching {
            context.getSharedPreferences(MANUAL_WINDOW_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_MANUAL_WINDOW_UNTIL, 0L)
        }.getOrDefault(0L)
        manualExternalUntilMs = stored
        return stored
    }

    private fun manualWindowActive(): Boolean =
        manualExternalWindowActive(System.currentTimeMillis(), storedManualWindowUntil())

    /**
     * Продлить окно ручной кнопки. Возвращает true, если окно пришлось открыть
     * именно сейчас (внешний канал был закрыт живым соседом) - этот случай
     * виден в «Логах» отдельной строкой.
     */
    private fun extendManualWindow(windowMs: Long): Boolean {
        val alreadyOpen = manualWindowActive()
        val until = manualExternalWindowUntil(
            System.currentTimeMillis(),
            storedManualWindowUntil(),
            windowMs,
        )
        manualExternalUntilMs = until
        runCatching {
            context.getSharedPreferences(MANUAL_WINDOW_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_MANUAL_WINDOW_UNTIL, until)
                .apply()
        }
        return !alreadyOpen
    }

    /**
     * Внешняя загрузка APK разрешена, если ядро открыло внешний канал само либо
     * это окно, открытое ручной кнопкой: человек сам попросил обновление.
     */
    private fun externalReleaseAllowedNow(): Boolean =
        externalReleaseAllowed(RustBridge.externalTransportAllowed(), manualWindowActive())

    /** DownloadManager lives outside this process. Even if a download was
     * enqueued on a valid fallback path, cancel it when the direct swarm
     * returns; do not mistake a one-time permission check for supervision. */
    fun suspendExternalDownloadsForSwarm() {
        if (RustBridge.externalTransportAllowed()) {
            scannedInCurrentSwarmWindow = false
            return
        }
        // Окно ручной кнопки: загрузку запросил человек, и не сносим её
        // из-под него - иначе «Скачать» на телефоне с соседом обрывалось бы
        // через 2.5 с после нажатия.
        if (manualWindowActive()) return
        if (scannedInCurrentSwarmWindow) return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        runCatching {
            val active = DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED
            val ids = ArrayList<Long>()
            val activeCursor = manager.query(DownloadManager.Query().setFilterByStatus(active))
                ?: return@runCatching
            activeCursor.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    val title = if (titleColumn >= 0) cursor.getString(titleColumn).orEmpty() else ""
                    // Query is scoped to our app, but it may also own a user-
                    // initiated unrelated download: cancel only APU updates.
                    if (title.startsWith("APU v")) ids += cursor.getLong(idColumn)
                }
            }
            for (id in ids) {
                manager.remove(id)
                Log.i(TAG, "Official download $id cancelled: direct swarm/search available")
            }
            scannedInCurrentSwarmWindow = true
        }.onFailure { Log.w(TAG, "DownloadManager swarm barrier failed: ${it.message}") }
    }

    fun downloadSucceeded(downloadId: Long): Boolean = runCatching {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        } ?: false
    }.getOrDefault(false)

    private fun directSwarmHasNewer(currentVersion: String): Boolean {
        val live = RustBridge.liveSwarmPeerIds().toSet()
        if (live.isEmpty()) return false
        val now = System.currentTimeMillis()
        val store = ApkUpdateStore(com.vladimir.messenger.data.update.UpdateFileRoots.seed(context))
        return store.loadOffers().any { offer ->
            offer.nodeId in live && ApkUpdate.isNewer(offer.version, currentVersion) &&
                now - offer.atMs in 0L..(5L * 60_000L)
        } || store.loadPatchOffers().any { offer ->
            offer.nodeId in live && ApkUpdate.isNewer(offer.toVersion, currentVersion) &&
                now - offer.atMs in 0L..(5L * 60_000L)
        }
    }

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
    suspend fun checkForUpdate(
        currentVersion: String,
        manual: Boolean = false,
        trigger: String = if (manual) "manual" else "foreground",
    ): ReleaseInfo? = withContext(Dispatchers.IO) {
        val trace = UpdateDiagnosticsStore.begin(context, currentVersion, trigger)
        try {
            // Ask for the *version and available bytes*, not merely "is a
            // peer online?". A live neighbour offering a newer APK/patch
            // makes the official HTTP lookup redundant for this check.
            if (!RustBridge.isRunning()) {
                // Ядра нет - рой спросить не у кого. Авто-проверка откладывается,
                // а ручная кнопка идёт внешним каналом: человек нажал и ждёт ответ.
                if (!manual) {
                    trace.outcome = "swarm_search_pending"
                    return@withContext null
                }
            } else if (!directSwarmHasNewer(currentVersion)) {
                runCatching { askSwarmForUpdate?.invoke() }
                    .onFailure { Log.w(TAG, "peer update query failed: ${it.message}") }
            }
            if (manual) {
                // Кнопка: рой спрошен, но НЕ ждём его. Владелец разрешил ручной
                // проверке внешний канал даже при живом соседе - иначе телефоны
                // с соседом никогда не находили версию, и раздел «Обновления»
                // выглядел неполным. Окно ограничено: 30 минут.
                trace.manualWindow =
                    if (extendManualWindow(MANUAL_WINDOW_MS)) "opened" else "already_allowed"
                Log.i(
                    TAG,
                    "Manual check: external channel opened for ${MANUAL_WINDOW_MS / 60_000} min " +
                        "(transition=${trace.manualWindow})",
                )
            } else {
                // Авто-проверка: рой прежде внешнего. Ждём, не откроет ли ядро
                // внешний канал, когда рядом нет живого подписанного соседа.
                val until = android.os.SystemClock.elapsedRealtime() + 20_000L
                while (!directSwarmHasNewer(currentVersion) && !RustBridge.externalTransportAllowed() &&
                    android.os.SystemClock.elapsedRealtime() < until
                ) {
                    delay(500L)
                }
                if (directSwarmHasNewer(currentVersion) || !RustBridge.externalTransportAllowed()) {
                    trace.outcome = "swarm_first_no_http"
                    return@withContext null
                }
            }
            val githubRelease = checkGitHub(currentVersion, trace)
            if (githubRelease != null) {
                trace.outcome = "update_available"
                return@withContext githubRelease
            }
            // A valid GitHub response saying «up to date» is authoritative; no
            // need to make a second request to the relay.
            if (!shouldTryRelayFallback(trace.githubOutcome)) {
                trace.outcome = resolveUpdateCheckOutcome(
                    releaseFound = false,
                    githubOutcome = trace.githubOutcome,
                    relayOutcome = trace.relayOutcome,
                )
                return@withContext null
            }

            val relayRelease = relayIfDue(currentVersion, manual, trace)
            trace.outcome = resolveUpdateCheckOutcome(
                releaseFound = relayRelease != null,
                githubOutcome = trace.githubOutcome,
                relayOutcome = trace.relayOutcome,
            )
            relayRelease
        } catch (error: Exception) {
            trace.outcome = "unavailable"
            throw error
        } finally {
            UpdateDiagnosticsStore.finish(context, trace)
        }
    }

    /**
     * Раунд 195: relay-проверка по расписанию (авто) или сразу (кнопка).
     * Метка времени ставится ДО запроса: недоступный/лимитированный воркер
     * не долбится каждым открытием приложения - одну попытку за окно.
     */
    private suspend fun relayIfDue(
        currentVersion: String,
        manual: Boolean,
        trace: UpdateDiagnosticsStore.CheckTrace,
    ): ReleaseInfo? {
        val prefs = context.getSharedPreferences(RELAY_CHECK_PREFS, Context.MODE_PRIVATE)
        if (!manual) {
            val now = System.currentTimeMillis()
            val jitter = prefs.getLong(KEY_RELAY_CHECK_JITTER, -1L).let { stored ->
                if (stored >= 0) stored
                else (0..RELAY_CHECK_JITTER_MS).random().also { fresh ->
                    prefs.edit().putLong(KEY_RELAY_CHECK_JITTER, fresh).apply()
                }
            }
            if (now - prefs.getLong(KEY_RELAY_CHECK_AT, 0L) < RELAY_CHECK_MIN_MS + jitter) {
                Log.d(TAG, "relay check skipped: not due yet")
                UpdateDiagnosticsStore.markSource(trace, "relay", "not_due")
                return null
            }
            prefs.edit().putLong(KEY_RELAY_CHECK_AT, now).apply()
        }
        return checkViaRelay(currentVersion, trace)
    }

    /** Direct query to GitHub Releases API. */
    private suspend fun checkGitHub(
        currentVersion: String,
        trace: UpdateDiagnosticsStore.CheckTrace,
    ): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Checking for updates. Current: $currentVersion")
            val url = "$GITHUB_API/$GITHUB_USER/$GITHUB_REPO/releases/latest"
            val response = httpGet(url, trace, "github") ?: return@withContext null
            val json = JSONObject(response)
            val latestVersion = json.optString("tag_name", "").trim()
            if (latestVersion.isBlank()) {
                UpdateDiagnosticsStore.markSource(trace, "github", "invalid_response")
                return@withContext null
            }
            UpdateDiagnosticsStore.markSource(trace, "github", "response_received", latestVersion)
            Log.i(TAG, "Latest version: $latestVersion")

            // Compare numeric components (11.16 > 11.9), not lexicographic strings.
            if (!isVersionNewer(currentVersion, latestVersion)) {
                Log.i(TAG, "Current version is up to date")
                UpdateDiagnosticsStore.markSource(trace, "github", "up_to_date", latestVersion)
                return@withContext null
            }
            Log.i(TAG, "New version available: $latestVersion")

            // Use a known asset name; GitHub's asset order is not guaranteed.
            val assets = json.optJSONArray("assets")
                ?: run {
                    UpdateDiagnosticsStore.markSource(trace, "github", "asset_missing", latestVersion)
                    return@withContext null
                }
            val canonicalName = "APU-$latestVersion.apk"
            val oldCanonicalName = "P2P-Messenger-$latestVersion.apk"
            var canonicalUrl: String? = null
            var legacyUrl: String? = null
            var apkSha256: String? = null
            var patchUrl: String? = null
            var patchFrom: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                when {
                    name.equals(canonicalName, ignoreCase = true) ||
                        name.equals(oldCanonicalName, ignoreCase = true) -> {
                        canonicalUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                        apkSha256 = asset.optString("digest", "")
                            .removePrefix("sha256:").takeIf { it.length == 64 }
                    }
                    name.equals("app-release.apk", ignoreCase = true) -> {
                        legacyUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                        if (apkSha256 == null) {
                            apkSha256 = asset.optString("digest", "")
                                .removePrefix("sha256:").takeIf { it.length == 64 }
                        }
                    }
                    name.startsWith("patch-") && name.endsWith(".bspatch") -> {
                        // patch-11.74.27-to-11.74.28.bspatch
                        val body = name.removePrefix("patch-").removeSuffix(".bspatch")
                        val from = body.substringBefore("-to-")
                        val to = body.substringAfter("-to-", "")
                        if (to == com.vladimir.messenger.data.update.ApkUpdate.normalize(latestVersion)) {
                            patchUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                            patchFrom = from
                        }
                    }
                }
            }
            // Older Actions workflows name the release APK app-release.apk.
            val downloadUrl = legacyUrl ?: canonicalUrl
            if (downloadUrl == null) {
                Log.w(TAG, "No supported APK asset found in release")
                UpdateDiagnosticsStore.markSource(trace, "github", "asset_missing", latestVersion)
                return@withContext null
            }
            UpdateDiagnosticsStore.markSource(trace, "github", "update_available", latestVersion)
            ReleaseInfo(
                version = latestVersion,
                downloadUrl = downloadUrl,
                releaseNotes = humanNotes(json.optString("body", "")),
                publishedAt = json.optString("published_at", ""),
                patchUrl = patchUrl,
                patchFrom = patchFrom,
                apkSha256 = apkSha256,
            )
        } catch (error: Exception) {
            Log.d(TAG, "GitHub update check failed: ${error.message}")
            UpdateDiagnosticsStore.markSource(
                trace,
                "github",
                if (trace.githubHttpStatus == 200) "invalid_response" else "network_error",
            )
            null
        }
    }

    /**
     * Fallback via the relay worker. Only aggregate outcome/version/status are
     * retained for diagnostics; its URL and response body are not persisted.
     */
    private suspend fun checkViaRelay(
        currentVersion: String,
        trace: UpdateDiagnosticsStore.CheckTrace,
    ): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$RELAY_BASE/update/latest", trace, "relay")
                ?: return@withContext null
            val json = JSONObject(response)
            val latestVersion = json.optString("tag_name", "").trim()
            if (latestVersion.isBlank()) {
                UpdateDiagnosticsStore.markSource(trace, "relay", "invalid_response")
                return@withContext null
            }
            if (!isVersionNewer(currentVersion, latestVersion)) {
                Log.i(TAG, "Relay: no newer version ($latestVersion)")
                UpdateDiagnosticsStore.markSource(trace, "relay", "up_to_date", latestVersion)
                return@withContext null
            }
            val apkUrl = json.optString("apk_url", "").ifBlank { "$RELAY_BASE/update/apk" }
            Log.i(TAG, "New version available via relay: $latestVersion")
            UpdateDiagnosticsStore.markSource(trace, "relay", "update_available", latestVersion)
            ReleaseInfo(
                version = latestVersion,
                downloadUrl = apkUrl,
                releaseNotes = humanNotes(json.optString("notes", "")),
                publishedAt = json.optString("published_at", ""),
            )
        } catch (error: Exception) {
            Log.d(TAG, "Relay update check failed: ${error.message}")
            UpdateDiagnosticsStore.markSource(
                trace,
                "relay",
                if (trace.relayHttpStatus == 200) "invalid_response" else "network_error",
            )
            null
        }
    }

    /**
     * Скачать APK через DownloadManager.
     * @return ID загрузки
     */
    fun downloadApk(releaseInfo: ReleaseInfo, preferOfficial: Boolean = false): Long {
        // An explicit source selection must not silently switch back to peers.
        if (preferOfficial) extendManualWindow(MANUAL_DOWNLOAD_WINDOW_MS)
        if ((!preferOfficial && directSwarmHasNewer(currentAppVersion())) || !externalReleaseAllowedNow()) {
            throw IllegalStateException("Обновление сначала запрашивается у прямого роя")
        }
        // Дальше загрузка идёт долго и не в нашем процессе (DownloadManager),
        // а дифф-патч собирается здесь же и читается по частям: продлеваем
        // окно, чтобы появление соседа не снесло загрузку на половине
        // (цикл 2.5 с в CoreServerService). Сюда попадают только нажатия
        // человека - все вызовы этого метода идут из кнопок.
        extendManualWindow(MANUAL_DOWNLOAD_WINDOW_MS)
        // Раунд 132: если мой телефон ровно той версии, от которой сделан
        // дифф-патч, - качаем РАЗНИЦУ (в разы меньше APK), собираем полный
        // файл у себя, сверяем sha256 релиза и сразу запускаем установку.
        // Не вышло (патча нет, база не та, сборка не сошлась) - обычное
        // скачивание целого APK.
        val currentNormalized = com.vladimir.messenger.data.update.ApkUpdate
            .normalize(currentAppVersion())
        if (!preferOfficial && releaseInfo.patchUrl != null &&
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
        
        if ((!preferOfficial && directSwarmHasNewer(currentAppVersion())) || !externalReleaseAllowedNow()) {
            throw IllegalStateException("Рой ответил до начала внешней загрузки")
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        scannedInCurrentSwarmWindow = false
        // A signed neighbour may have answered between permission and enqueue.
        if (!externalReleaseAllowedNow()) {
            downloadManager.remove(downloadId)
            throw IllegalStateException("Рой появился во время запуска загрузки — внешняя загрузка отменена")
        }
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

    /**
     * Раунд 136: заметки релиза пишутся для страницы GitHub - с разметкой,
     * а в окне обновления markdown выглядел «каким-то кодом» (скрин
     * владельца 2026-09-23: решётки, звёздочки и блок команды). Для окна
     * берём ТОЛЬКО вступление: заголовок и абзац до первого «## » - и
     * снимаем разметку. Детали, команды и патчи остаются на странице
     * релиза, куда окно человека не тащит.
     */
    private fun humanNotes(raw: String): String {
        if (raw.isBlank()) return ""
        // Заголовок и ПЕРВАЯ секция (до второго «## »): у части релизов
        // сразу под заголовком секция - выбрасывать её жаль, а хвост с
        // командами и патчами в окно всё равно не тащим.
        val first = raw.indexOf("\n## ")
        val intro = when {
            first < 0 -> raw
            else -> {
                val second = raw.indexOf("\n## ", first + 1)
                if (second < 0) raw else raw.substring(0, second)
            }
        }
        val plain = intro.lines()
            .mapNotNull { line ->
                val t = line.trim()
                when {
                    t.isEmpty() -> null
                    t.startsWith("```") -> null
                    t == "---" -> null
                    else -> t.trimStart('#').trim()
                }
            }
            .joinToString("\n")
            .replace("**", "")
            .replace("`", "")
        if (plain.isBlank()) return ""
        if (plain.length <= 420) return plain
        // Обрезка по границе слова, не посреди слова.
        val cut = plain.lastIndexOf(' ', 419).coerceAtLeast(400)
        return plain.take(cut).trimEnd() + "\u2026"
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
        // Окно ручной кнопки тоже считается: иначе после ручного «Скачать»
        // дифф-патч молча не скачивался бы, а на телефоне с соседом качался бы
        // целый APK вместо разницы.
        if (!externalReleaseAllowedNow()) return false
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
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!externalReleaseAllowedNow()) {
                            throw java.io.IOException("Прямой рой вернулся; загрузка патча прервана")
                        }
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        output.write(buffer, 0, bytes)
                    }
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

    private fun httpGet(
        url: String,
        trace: UpdateDiagnosticsStore.CheckTrace,
        source: String,
    ): String? {
        // Тот же тормоз, что и в checkForUpdate, но на самом запросе: без него
        // ручная кнопка «открыла бы» канал и всё равно получила отказ здесь -
        // ровно то «обновлений нет», на которое жаловался владелец.
        if (!externalReleaseAllowedNow()) {
            UpdateDiagnosticsStore.markSource(trace, source, "swarm_available")
            return null
        }
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "APU-Update-Checker")
                connectTimeout = HTTP_TIMEOUT
                readTimeout = HTTP_TIMEOUT
            }
        } catch (error: Exception) {
            UpdateDiagnosticsStore.markNetworkFailure(trace, source)
            Log.d(TAG, "Update endpoint unavailable: ${error.message}")
            return null
        }

        return try {
            val code = conn.responseCode
            UpdateDiagnosticsStore.markHttp(trace, source, code)
            if (code != 200) {
                Log.w(TAG, "HTTP GET $url returned $code")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (error: Exception) {
            UpdateDiagnosticsStore.markNetworkFailure(trace, source)
            Log.d(TAG, "Update endpoint unavailable: ${error.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
