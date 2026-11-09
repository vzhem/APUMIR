package com.vladimir.messenger.service

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.link.ServiceHost
import com.vladimir.messenger.data.update.ApkUpdate
import com.vladimir.messenger.worker.OfficialUpdateMonitor
import java.io.File
import java.io.IOException
import java.net.URI

internal enum class OfficialDownloadResult { IGNORED, ACTIVE, SWITCHED, VERIFIED, FAILED }

/**
 * Manages one user-requested update across DownloadManager jobs. State is saved
 * before switching sources, so a late broadcast, cancellation or process
 * restart cannot turn an old job into a second independent download.
 */
internal class OfficialUpdateDownloads(
    private val context: Context,
    private val renewManualWindow: () -> Unit,
) {
    companion object {
        const val PREFS = "update_official_download"
        const val KEY_ID = "download_id"
        const val KEY_VERSION = "version"
        private const val KEY_SOURCE = "source"
        private const val KEY_SHA = "expected_sha256"
        private const val KEY_SITE_SHA = "site_sha256"
        private const val KEY_VERIFIED_ID = "verified_id"
        private const val KEY_NEXT_SOURCE = "next_source"
        private const val KEY_START_AFTER_ID = "start_after_id"
        private const val KEY_TERMINAL_FAILURE = "terminal_failure"
        // DownloadManager row IDs are positive. Zero addresses an interrupted start.
        const val RECOVER_START_ID = 0L
        private const val KEY_REJECTED_IDS = "rejected_download_ids"
        private const val MAX_REJECTED_IDS = 64
        private const val TAG = "OfficialUpdate"

        /** Exported receiver only schedules work for our currently tracked job. */
        fun isTracked(context: Context, id: Long): Boolean {
            if (id < 0L) return false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.contains(KEY_SOURCE) && prefs.getLong(KEY_ID, -1L) == id
        }

        fun trackedId(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_SOURCE) || prefs.getBoolean(KEY_TERMINAL_FAILURE, false)) return null
            if (prefs.contains(KEY_START_AFTER_ID)) return RECOVER_START_ID
            return prefs.getLong(KEY_ID, -1L).takeIf { it > 0L }
        }
    }

    // Shared with UpdateChecker's one-tap gate and cancel operation.
    val lock = Any()
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manager() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun start(release: UpdateChecker.ReleaseInfo, site: UpdateSiteMirror.Match?): Long = synchronized(lock) {
        require(ApkUpdate.parseVersion(release.version) != null) { "Invalid update version" }
        val sha = normalizedUpdateSha256(release.apkSha256)
        require(release.apkSha256.isNullOrBlank() || sha != null) { "Invalid update digest" }
        val source = initialOfficialApkSource(site != null)
        val downloadManager = manager()
        val start = OfficialApkAttempt(latestDownloadId(downloadManager), release.version, source)
        // Persist the intent before enqueue: a process death between enqueue and
        // recording its ID must not expose an unverified, untracked APK.
        check(prefs.edit().putLong(KEY_ID, -1L).putLong(KEY_START_AFTER_ID, start.downloadId)
            .putString(KEY_VERSION, release.version).putString(KEY_SOURCE, source.name)
            // A hash supplied only by the site cannot authenticate other mirrors.
            .putString(KEY_SHA, sha.orEmpty()).putString(KEY_SITE_SHA, site?.sha256.orEmpty())
            .remove(KEY_VERIFIED_ID).remove(KEY_NEXT_SOURCE).remove(KEY_TERMINAL_FAILURE).commit()) {
            "Could not save update download intent"
        }
        // Recover even if this process dies before it can schedule the real ID.
        OfficialUpdateMonitor.watch(context, RECOVER_START_ID)
        val id = try {
            enqueue(downloadManager, release.version, source)
        } catch (error: Exception) {
            // A failed Binder call might nevertheless have enqueued the job.
            val existing = findEnqueuedReplacement(downloadManager, start, source)
            if (existing == null) {
                markTerminalFailure(release.version)
                throw error
            }
            existing
        }
        if (!saveStartedId(id)) {
            downloadManager.remove(id)
            markTerminalFailure(release.version)
            throw IOException("Could not save update download")
        }
        Log.i(TAG, "Started $source for ${release.version}, id=$id")
        OfficialUpdateMonitor.watch(context, id)
        id
    }

    /** The old non-site-first path remains compatible with its existing downloads. */
    fun rememberLegacy(id: Long, version: String) = synchronized(lock) {
        prefs.edit().putLong(KEY_ID, id).putString(KEY_VERSION, version)
            .remove(KEY_SOURCE).remove(KEY_SHA).remove(KEY_SITE_SHA).remove(KEY_VERIFIED_ID)
            .remove(KEY_NEXT_SOURCE).remove(KEY_START_AFTER_ID).remove(KEY_TERMINAL_FAILURE).apply()
    }

    /** True only after validation for managed jobs; older DownloadManager jobs are unchanged. */
    fun mayExposeCompleted(id: Long): Boolean = synchronized(lock) {
        if (rejectedIds().contains(id.toString())) return@synchronized false
        if (prefs.getLong(KEY_ID, -1L) == id && prefs.contains(KEY_SOURCE)) {
            return@synchronized !prefs.getBoolean(KEY_TERMINAL_FAILURE, false) &&
                !prefs.contains(KEY_NEXT_SOURCE) && prefs.getLong(KEY_VERIFIED_ID, -1L) == id
        }
        // Between enqueue and saving its ID, a replacement may already finish.
        // It must not be mistaken for a legacy APK by the seeder after a crash.
        val start = startingAttempt()
        val current = currentAttempt()
        val next = sourceFrom(prefs.getString(KEY_NEXT_SOURCE, null))
        val pending = start ?: current?.takeIf { next != null } ?: return@synchronized true
        val source = if (start != null) start.source else next ?: return@synchronized true
        if (id <= pending.downloadId) return@synchronized true
        try {
            !matchesReplacement(manager(), id, pending, source)
        } catch (error: Exception) {
            Log.w(TAG, "Could not check pending update identity: ${error.message}")
            false // Cannot safely expose a potentially unverified replacement.
        }
    }

    /** A completed or failed job can still be in the middle of verification/failover. */
    fun blocksNewDownload(): Boolean = synchronized(lock) {
        if (startingAttempt() != null) return@synchronized true
        val attempt = currentAttempt() ?: return@synchronized false
        if (prefs.getBoolean(KEY_TERMINAL_FAILURE, false)) return@synchronized false
        if (prefs.contains(KEY_NEXT_SOURCE)) return@synchronized true
        val snapshot = snapshot(manager(), attempt.downloadId) ?: return@synchronized false
        when (officialDownloadAction(attempt, attempt.downloadId, observation(snapshot, attempt))) {
            OfficialDownloadAction.Verify, is OfficialDownloadAction.TrySource -> true
            else -> false // Running/paused jobs are already found by DownloadManager's active query.
        }
    }

    fun displayStatus(id: Long, status: Int, reason: Int): String? = synchronized(lock) {
        val attempt = currentAttempt()?.takeIf { it.downloadId == id } ?: return@synchronized null
        if (prefs.getBoolean(KEY_TERMINAL_FAILURE, false)) return@synchronized "failed"
        if (prefs.contains(KEY_NEXT_SOURCE)) return@synchronized "retrying"
        val action = officialDownloadAction(attempt, id, observation(Snapshot(status, reason, null), attempt))
        when (action) {
            OfficialDownloadAction.Verify -> "verifying"
            is OfficialDownloadAction.TrySource -> "retrying"
            else -> null
        }
    }

    fun sourceFor(id: Long): String? = synchronized(lock) {
        val attempt = if (id == RECOVER_START_ID) startingAttempt() else currentAttempt()?.takeIf { it.downloadId == id }
        attempt?.source?.name?.lowercase()
    }

    fun startingVersion(): String? = synchronized(lock) { startingAttempt()?.version }

    fun transitionPending(id: Long): Boolean = synchronized(lock) {
        currentAttempt()?.downloadId == id && prefs.contains(KEY_NEXT_SOURCE)
    }

    fun terminalFailureVersion(): String? = synchronized(lock) {
        if (!prefs.getBoolean(KEY_TERMINAL_FAILURE, false)) null
        else prefs.getString(KEY_VERSION, null)
    }

    /** Clear state before removing the system job, so an old broadcast cannot restart it. */
    fun clearForCancellation(): Pair<Long, String> = synchronized(lock) {
        val id = prefs.getLong(KEY_ID, -1L)
        val version = prefs.getString(KEY_VERSION, "").orEmpty()
        val attempt = currentAttempt()
        val start = startingAttempt()
        val next = sourceFrom(prefs.getString(KEY_NEXT_SOURCE, null))
        val downloadManager = manager()
        val replacement = when {
            start != null -> findEnqueuedReplacement(downloadManager, start, start.source)
            attempt != null && next != null -> findEnqueuedReplacement(downloadManager, attempt, next)
            else -> null
        }
        val editor = prefs.edit()
        if (prefs.contains(KEY_SOURCE)) {
            editor.putStringSet(KEY_REJECTED_IDS, withRejected(id, replacement ?: -1L))
        }
        check(editor.remove(KEY_ID).remove(KEY_VERSION).remove(KEY_SOURCE).remove(KEY_SHA).remove(KEY_SITE_SHA)
            .remove(KEY_VERIFIED_ID).remove(KEY_NEXT_SOURCE).remove(KEY_START_AFTER_ID)
            .remove(KEY_TERMINAL_FAILURE).commit()) {
            "Could not cancel update download"
        }
        if (replacement != null) downloadManager.remove(replacement)
        id to version
    }

    fun reconcile(id: Long): OfficialDownloadResult = synchronized(lock) {
        val start = startingAttempt()
        if (start != null) {
            if (id != RECOVER_START_ID) return@synchronized OfficialDownloadResult.IGNORED
            val downloadManager = manager()
            val recovered = findEnqueuedReplacement(downloadManager, start, start.source)
            if (recovered == null) {
                // No job was enqueued (or the person removed it). Do not restart it silently.
                markTerminalFailure(start.version)
                return@synchronized OfficialDownloadResult.FAILED
            }
            OfficialUpdateMonitor.watch(context, recovered)
            check(saveStartedId(recovered)) { "Could not save recovered update download" }
            Log.i(TAG, "Recovered interrupted ${start.source} update, id=$recovered")
            return@synchronized reconcile(recovered)
        }
        val attempt = currentAttempt() ?: return@synchronized OfficialDownloadResult.IGNORED
        if (attempt.downloadId != id || prefs.getBoolean(KEY_TERMINAL_FAILURE, false)) {
            return@synchronized OfficialDownloadResult.IGNORED
        }
        val downloadManager = manager()
        val pendingSource = sourceFrom(prefs.getString(KEY_NEXT_SOURCE, null))
        if (pendingSource != null) return@synchronized finishSwitch(downloadManager, attempt, pendingSource)
        val snapshot = snapshot(downloadManager, id)
        val observed = if (snapshot == null) OfficialDownloadObservation.MISSING else observation(snapshot, attempt)
        when (val action = officialDownloadAction(attempt, id, observed)) {
            OfficialDownloadAction.Ignore -> OfficialDownloadResult.IGNORED
            OfficialDownloadAction.Wait -> OfficialDownloadResult.ACTIVE
            OfficialDownloadAction.Ready -> OfficialDownloadResult.VERIFIED
            OfficialDownloadAction.Fail -> {
                UpdateDownloadGate.lost(attempt.version)
                OfficialDownloadResult.FAILED
            }
            is OfficialDownloadAction.TrySource -> beginSwitch(downloadManager, attempt, action.source)
            OfficialDownloadAction.Verify -> {
                val officialSha = prefs.getString(KEY_SHA, null)?.takeIf { it.isNotBlank() }
                val siteSha = prefs.getString(KEY_SITE_SHA, null)?.takeIf { it.isNotBlank() }
                val expectedSha = officialSha ?: siteSha.takeIf { attempt.source == OfficialApkSource.SITE }
                if (snapshot != null && validate(snapshot.localUri, attempt.version, expectedSha)) {
                    check(prefs.edit().putLong(KEY_VERIFIED_ID, id).commit()) { "Could not save verified update" }
                    UpdateDownloadGate.finished(attempt.version)
                    Log.i(TAG, "Verified ${attempt.source} update ${attempt.version}")
                    OfficialDownloadResult.VERIFIED
                } else {
                    Log.w(TAG, "Downloaded ${attempt.source} update failed validation")
                    when (val invalid = officialDownloadAction(attempt, id, OfficialDownloadObservation.INVALID_FILE)) {
                        is OfficialDownloadAction.TrySource -> beginSwitch(downloadManager, attempt, invalid.source)
                        else -> {
                            rejectAndRemove(downloadManager, attempt.downloadId)
                            markTerminalFailure(attempt.version)
                            OfficialDownloadResult.FAILED
                        }
                    }
                }
            }
        }
    }

    private data class Snapshot(val status: Int, val reason: Int, val localUri: String?)

    private fun snapshot(manager: DownloadManager, id: Long): Snapshot? =
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            Snapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                localUri = if (uriIndex >= 0) cursor.getString(uriIndex) else null,
            )
        }

    private fun observation(snapshot: Snapshot, attempt: OfficialApkAttempt): OfficialDownloadObservation =
        when (snapshot.status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> OfficialDownloadObservation.ACTIVE
            DownloadManager.STATUS_PAUSED -> if (snapshot.reason == DownloadManager.PAUSED_WAITING_TO_RETRY) {
                OfficialDownloadObservation.SOURCE_PAUSED
            } else OfficialDownloadObservation.WAITING_FOR_NETWORK
            DownloadManager.STATUS_FAILED -> if (isSourceFailure(snapshot.reason)) {
                OfficialDownloadObservation.SOURCE_FAILED
            } else OfficialDownloadObservation.LOCAL_FAILED
            DownloadManager.STATUS_SUCCESSFUL -> if (prefs.getLong(KEY_VERIFIED_ID, -1L) == attempt.downloadId) {
                OfficialDownloadObservation.VERIFIED
            } else OfficialDownloadObservation.DOWNLOADED
            else -> OfficialDownloadObservation.LOCAL_FAILED
        }

    private fun isSourceFailure(reason: Int): Boolean = reason in 400..599 || reason in setOf(
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
        DownloadManager.ERROR_HTTP_DATA_ERROR,
        DownloadManager.ERROR_TOO_MANY_REDIRECTS,
        DownloadManager.ERROR_CANNOT_RESUME,
    )

    private fun beginSwitch(
        manager: DownloadManager,
        attempt: OfficialApkAttempt,
        next: OfficialApkSource,
    ): OfficialDownloadResult {
        // Persist the transition and rejection BEFORE removing the old job.
        check(prefs.edit().putStringSet(KEY_REJECTED_IDS, withRejected(attempt.downloadId))
            .putString(KEY_NEXT_SOURCE, next.name).commit()) { "Could not save update source transition" }
        return finishSwitch(manager, attempt, next)
    }

    private fun finishSwitch(
        manager: DownloadManager,
        attempt: OfficialApkAttempt,
        next: OfficialApkSource,
    ): OfficialDownloadResult {
        val allowed = officialDownloadAction(attempt, attempt.downloadId, OfficialDownloadObservation.SOURCE_FAILED)
        if (allowed != OfficialDownloadAction.TrySource(next)) {
            Log.w(TAG, "Invalid saved update source transition")
            markTerminalFailure(attempt.version)
            return OfficialDownloadResult.FAILED
        }
        return try {
            // This is still the same explicit user request, even after a long pause.
            renewManualWindow()
            manager.remove(attempt.downloadId)
            // If the process died after enqueue but before saving the new id,
            // recover that exact job instead of enqueuing another one.
            val id = findEnqueuedReplacement(manager, attempt, next) ?: try {
                enqueue(manager, attempt.version, next)
            } catch (error: Exception) {
                Log.w(TAG, "Could not enqueue replacement update: ${error.message}")
                markTerminalFailure(attempt.version)
                return OfficialDownloadResult.FAILED
            }
            check(prefs.edit().putLong(KEY_ID, id).putString(KEY_SOURCE, next.name)
                .remove(KEY_NEXT_SOURCE).remove(KEY_VERIFIED_ID).commit()) { "Could not save replacement download" }
            Log.i(TAG, "Switched ${attempt.source} to $next for ${attempt.version}, id=$id")
            OfficialUpdateMonitor.watch(context, id)
            OfficialDownloadResult.SWITCHED
        } catch (error: Exception) {
            // Do not clear the transition: a later worker can recover an enqueue
            // that succeeded just before the preferences write or process died.
            Log.w(TAG, "Could not switch update source: ${error.message}")
            OfficialDownloadResult.ACTIVE
        }
    }

    private fun latestDownloadId(manager: DownloadManager): Long {
        val cursor = manager.query(DownloadManager.Query()) ?: throw IOException("Cannot query update downloads")
        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            var latest = 0L
            while (it.moveToNext()) latest = maxOf(latest, it.getLong(idIndex))
            latest
        }
    }

    private fun saveStartedId(id: Long): Boolean = prefs.edit().putLong(KEY_ID, id)
        .remove(KEY_START_AFTER_ID).commit()

    private fun matchesReplacement(
        manager: DownloadManager,
        id: Long,
        previous: OfficialApkAttempt,
        next: OfficialApkSource,
    ): Boolean {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            ?: throw IOException("Cannot query update download")
        return cursor.use { it.moveToFirst() && matchesReplacementRow(it, previous, next) }
    }

    private fun matchesReplacementRow(cursor: Cursor, previous: OfficialApkAttempt, next: OfficialApkSource): Boolean {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        val rowTitle = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
        val url = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
        val description = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)).orEmpty()
        // A permanent redirect may have changed COLUMN_URI. The stable source
        // host at the end of our description also identifies the job.
        val sameSource = url == sourceUrl(previous.version, next) || description.endsWith(sourceHost(next))
        return id > previous.downloadId && rowTitle == title(previous.version) && sameSource
    }

    private fun findEnqueuedReplacement(
        manager: DownloadManager,
        previous: OfficialApkAttempt,
        next: OfficialApkSource,
    ): Long? {
        var found: Long? = null
        val cursor = manager.query(DownloadManager.Query()) ?: throw IOException("Cannot query update downloads")
        cursor.use {
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (cursor.moveToNext()) {
                if (matchesReplacementRow(cursor, previous, next)) {
                    val id = cursor.getLong(idIndex)
                    if (found == null || id > found!!) found = id
                }
            }
        }
        return found
    }

    private fun rejectAndRemove(manager: DownloadManager, id: Long) {
        check(prefs.edit().putStringSet(KEY_REJECTED_IDS, withRejected(id)).commit()) {
            "Could not quarantine invalid update"
        }
        manager.remove(id)
    }

    private fun markTerminalFailure(version: String) {
        check(prefs.edit().putLong(KEY_ID, -1L).putBoolean(KEY_TERMINAL_FAILURE, true)
            .remove(KEY_NEXT_SOURCE).remove(KEY_START_AFTER_ID).remove(KEY_VERIFIED_ID).commit()) {
            "Could not save update failure"
        }
        UpdateDownloadGate.lost(version)
    }

    private fun enqueue(manager: DownloadManager, version: String, source: OfficialApkSource): Long {
        // Distinct destinations prevent a partially removed system download from
        // blocking its replacement with ERROR_FILE_ALREADY_EXISTS.
        val suffix = when (source) {
            OfficialApkSource.SITE -> ""
            OfficialApkSource.RELAY -> "-relay"
            OfficialApkSource.GITHUB -> "-github"
        }
        val fileName = "APU-v${ApkUpdate.normalize(version)}$suffix.apk"
        val request = DownloadManager.Request(Uri.parse(sourceUrl(version, source))).apply {
            setTitle(title(version))
            setDescription(context.getString(R.string.upd_download_source_tpl, sourceHost(source)))
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        return manager.enqueue(request)
    }

    private fun sourceUrl(version: String, source: OfficialApkSource): String = when (source) {
        OfficialApkSource.SITE -> UpdateSiteMirror.APK_URL
        OfficialApkSource.RELAY -> "${ServiceHost.BASE}/update/apk"
        OfficialApkSource.GITHUB -> requireNotNull(pinnedGithubApkUrl(version))
    }

    private fun sourceHost(source: OfficialApkSource): String = when (source) {
        OfficialApkSource.SITE -> "apu.clan.su"
        OfficialApkSource.RELAY -> ServiceHost.HOST
        OfficialApkSource.GITHUB -> "github.com"
    }

    private fun title(version: String) = "APU v${ApkUpdate.normalize(version)}"

    private fun validate(localUri: String?, version: String, expectedSha: String?): Boolean {
        return try {
            val uri = localUri?.let(::URI) ?: return false
            if (!uri.scheme.equals("file", ignoreCase = true)) return false
            val file = File(uri)
            if (!file.isFile || file.length() > GroupWire.MAX_UPDATE_SIZE_BYTES || !ApkUpdate.looksLikeApk(file)) return false
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
            if (archive.packageName != context.packageName || !ApkUpdate.isSame(archive.versionName.orEmpty(), version)) return false
            val sha = normalizedUpdateSha256(expectedSha)
            if (!expectedSha.isNullOrBlank() && sha == null) return false
            sha == null || ApkUpdate.sha256OfFile(file).equals(sha, ignoreCase = true)
        } catch (error: Exception) {
            Log.w(TAG, "Could not validate downloaded update: ${error.javaClass.simpleName}")
            false
        }
    }

    /** While starting, the attempt ID is the last existing DownloadManager ID. */
    private fun startingAttempt(): OfficialApkAttempt? {
        if (!prefs.contains(KEY_START_AFTER_ID) || prefs.getBoolean(KEY_TERMINAL_FAILURE, false)) return null
        val watermark = prefs.getLong(KEY_START_AFTER_ID, -1L)
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val source = sourceFrom(prefs.getString(KEY_SOURCE, null)) ?: return null
        return if (watermark >= 0L) OfficialApkAttempt(watermark, version, source) else null
    }

    private fun currentAttempt(): OfficialApkAttempt? {
        val id = prefs.getLong(KEY_ID, -1L)
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val source = sourceFrom(prefs.getString(KEY_SOURCE, null)) ?: return null
        return if (id > 0L) OfficialApkAttempt(id, version, source) else null
    }

    private fun sourceFrom(value: String?): OfficialApkSource? =
        OfficialApkSource.entries.firstOrNull { it.name == value }

    private fun rejectedIds(): Set<String> = prefs.getStringSet(KEY_REJECTED_IDS, emptySet()).orEmpty()

    private fun withRejected(vararg ids: Long): Set<String> = (rejectedIds() + ids.filter { it >= 0L }.map { it.toString() })
        .mapNotNull { it.toLongOrNull() }.sortedDescending().take(MAX_REJECTED_IDS).map { it.toString() }.toSet()
}
