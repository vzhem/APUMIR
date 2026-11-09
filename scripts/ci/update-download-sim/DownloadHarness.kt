package com.vladimir.messenger.service

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import com.vladimir.messenger.data.update.ApkUpdate
import com.vladimir.messenger.worker.OfficialUpdateMonitor
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private class MemoryPrefs : SharedPreferences {
    val values = mutableMapOf<String, Any>()
    var failNextCommit = false
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String, defValues: Set<String>?) = (values[key] as? Set<String>) ?: defValues
    override fun contains(key: String) = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Edit()
    inner class Edit : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun putStringSet(key: String, value: Set<String>?) = apply { changes[key] = value?.toSet() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun commit(): Boolean {
            if (failNextCommit) { failNextCommit = false; return false }
            changes.forEach { (key,value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() { check(commit()) }
    }
}
private data class Entry(
    val id: Long, val title: String, var url: String, val description: String,
    var status: Int = DownloadManager.STATUS_PENDING, var reason: Int = 0, var uri: String? = null,
)
private class FakeManager : DownloadManager() {
    val entries = linkedMapOf<Long, Entry>()
    var nextId = 1L
    var afterEnqueue: (() -> Unit)? = null
    var failEnqueue = false
    override fun enqueue(request: Request): Long {
        if (failEnqueue) throw IllegalStateException("enqueue rejected")
        val id = nextId++
        entries[id] = Entry(id, request.title, request.uri.toString(), request.description)
        afterEnqueue?.invoke()
        return id
    }
    override fun query(query: Query): Cursor = FakeCursor(entries.values.filter { query.ids == null || it.id in query.ids!! })
    override fun remove(vararg ids: Long): Int = ids.count { entries.remove(it) != null }
}
private class FakeCursor(private val rows: List<Entry>) : Cursor {
    private var pos = -1
    private val columns = listOf(DownloadManager.COLUMN_ID, DownloadManager.COLUMN_TITLE, DownloadManager.COLUMN_URI,
        DownloadManager.COLUMN_STATUS, DownloadManager.COLUMN_REASON, DownloadManager.COLUMN_LOCAL_URI, DownloadManager.COLUMN_DESCRIPTION)
    override fun moveToFirst() = if (rows.isEmpty()) false else { pos=0; true }
    override fun moveToNext() = (++pos < rows.size)
    override fun getColumnIndex(name: String) = columns.indexOf(name)
    override fun getColumnIndexOrThrow(name: String) = getColumnIndex(name).also { check(it >= 0) }
    private fun value(index: Int): Any? = rows[pos].let { row -> when(columns[index]) {
        DownloadManager.COLUMN_ID -> row.id; DownloadManager.COLUMN_TITLE -> row.title; DownloadManager.COLUMN_URI -> row.url
        DownloadManager.COLUMN_STATUS -> row.status; DownloadManager.COLUMN_REASON -> row.reason; DownloadManager.COLUMN_DESCRIPTION -> row.description; else -> row.uri
    } }
    override fun getLong(index: Int) = value(index) as Long
    override fun getInt(index: Int) = value(index) as Int
    override fun getString(index: Int) = value(index) as String?
    override fun close() {}
}
private class FakeContext : Context() {
    val prefs = MemoryPrefs()
    val dm = FakeManager()
    var archivePackage = "com.vladimir.messenger"
    var archiveVersion = "v11.74.501"
    override val packageManager = object : PackageManager() {
        override fun getPackageArchiveInfo(path: String, flags: Int) = PackageInfo(archivePackage, archiveVersion)
    }
    override fun getSystemService(name: String): Any? = if (name == DOWNLOAD_SERVICE) dm else null
    override fun getSharedPreferences(name: String, mode: Int) = prefs
}
private fun release(sha: String? = null) = UpdateChecker.ReleaseInfo("v11.74.501", "ignored", "", "", sha)
private fun newController(context: FakeContext) = OfficialUpdateDownloads(context) {}
private fun currentId(context: FakeContext) = context.prefs.getLong(OfficialUpdateDownloads.KEY_ID, -1L)
private fun expect(value: Boolean, message: String) { if (!value) throw AssertionError(message) }
private fun apk(): File {
    val file = Files.createTempFile("apu-update", ".apk").toFile()
    ZipOutputStream(file.outputStream()).use { zip ->
        for (name in listOf("AndroidManifest.xml", "classes.dex")) {
            zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
        }
    }
    return file
}
private fun siteThenFallbackAndStaleEvents() {
    val c = FakeContext(); val d = newController(c)
    val first = d.start(release(), UpdateSiteMirror.Match(null))
    expect(c.dm.entries[first]!!.url == UpdateSiteMirror.APK_URL, "site must start first")
    c.dm.entries[first]!!.apply { status=DownloadManager.STATUS_PAUSED; reason=DownloadManager.PAUSED_WAITING_FOR_NETWORK }
    expect(d.reconcile(first) == OfficialDownloadResult.ACTIVE, "offline wait should not switch")
    expect(currentId(c) == first, "offline wait replaced site")
    c.dm.entries[first]!!.reason = DownloadManager.PAUSED_WAITING_TO_RETRY
    expect(d.reconcile(first) == OfficialDownloadResult.SWITCHED, "site retry pause should switch")
    val second = currentId(c)
    expect(second != first && c.dm.entries.size == 1, "one replacement only")
    expect(c.dm.entries[second]!!.url.endsWith("/update/apk"), "relay is second")
    expect(!d.mayExposeCompleted(first), "old id must stay quarantined")
    expect(d.reconcile(first) == OfficialDownloadResult.IGNORED, "stale event ignored")
    c.dm.entries[second]!!.apply { status=DownloadManager.STATUS_FAILED; reason=503 }
    expect(d.reconcile(second) == OfficialDownloadResult.SWITCHED, "relay failure should switch")
    val third = currentId(c)
    expect(c.dm.entries[third]!!.url == pinnedGithubApkUrl("v11.74.501"), "GitHub is version-pinned")
    expect(c.dm.entries.size == 1 && OfficialUpdateMonitor.watched.containsAll(listOf(first, second, third)), "one active job with monitors")
}
private fun missingSiteAndLocalErrors() {
    val c = FakeContext(); val d = newController(c)
    val id = d.start(release(), null)
    expect(c.dm.entries[id]!!.url.endsWith("/update/apk"), "unavailable site must skip to relay")
    c.dm.entries[id]!!.apply { status=DownloadManager.STATUS_FAILED; reason=DownloadManager.ERROR_INSUFFICIENT_SPACE }
    expect(d.reconcile(id) == OfficialDownloadResult.FAILED, "disk failure must not switch source")
    expect(currentId(c) == id && c.dm.entries.size == 1, "local failure replaced job")
}
private fun verifiedAndCorruptFiles() {
    val valid = apk()
    try {
        val c = FakeContext(); val d = newController(c); val sha = ApkUpdate.sha256OfFile(valid)
        val id = d.start(release(sha), UpdateSiteMirror.Match(sha))
        c.dm.entries[id]!!.apply { status=DownloadManager.STATUS_SUCCESSFUL; uri=valid.toURI().toString() }
        expect(!d.mayExposeCompleted(id) && d.blocksNewDownload(), "unverified APK escaped")
        expect(d.displayStatus(id, DownloadManager.STATUS_SUCCESSFUL, 0) == "verifying", "verification should be visible")
        expect(d.reconcile(id) == OfficialDownloadResult.VERIFIED && d.mayExposeCompleted(id), "valid APK not accepted")
        expect(d.reconcile(id) == OfficialDownloadResult.VERIFIED, "verification not idempotent")
        val bad = FakeContext(); val badController = newController(bad)
        val badId = badController.start(release("0".repeat(64)), UpdateSiteMirror.Match("0".repeat(64)))
        bad.dm.entries[badId]!!.apply { status=DownloadManager.STATUS_SUCCESSFUL; uri=valid.toURI().toString() }
        expect(badController.reconcile(badId) == OfficialDownloadResult.SWITCHED, "bad SHA must try relay")
        expect(!badController.mayExposeCompleted(badId), "bad bytes must not be adopted")
        val wrongVersion = FakeContext(); wrongVersion.archiveVersion = "v11.74.500"
        val wrongController = newController(wrongVersion); val wrongId = wrongController.start(release(), UpdateSiteMirror.Match(null))
        wrongVersion.dm.entries[wrongId]!!.apply { status=DownloadManager.STATUS_SUCCESSFUL; uri=valid.toURI().toString() }
        expect(wrongController.reconcile(wrongId) == OfficialDownloadResult.SWITCHED, "wrong version must switch")

        // A bad hash from the site alone must not poison a valid backup. There
        // is no authoritative release hash in this case; version/package still apply.
        val siteOnly = FakeContext(); val siteOnlyCtl = newController(siteOnly)
        val siteId = siteOnlyCtl.start(release(), UpdateSiteMirror.Match("0".repeat(64)))
        siteOnly.dm.entries[siteId]!!.apply { status=DownloadManager.STATUS_SUCCESSFUL; uri=valid.toURI().toString() }
        expect(siteOnlyCtl.reconcile(siteId) == OfficialDownloadResult.SWITCHED, "bad site-only hash did not switch")
        val backup = currentId(siteOnly)
        siteOnly.dm.entries[backup]!!.apply { status=DownloadManager.STATUS_SUCCESSFUL; uri=valid.toURI().toString() }
        expect(siteOnlyCtl.reconcile(backup) == OfficialDownloadResult.VERIFIED, "site-only hash poisoned backup verification")
    } finally { valid.delete() }
}
private fun interruptedSwitchAndCancellation() {
    val c = FakeContext(); val first = newController(c); val id = first.start(release(), UpdateSiteMirror.Match(null))
    c.dm.entries[id]!!.apply { status=DownloadManager.STATUS_FAILED; reason=503 }
    c.dm.afterEnqueue = { c.prefs.failNextCommit = true; c.dm.afterEnqueue = null }
    expect(first.reconcile(id) == OfficialDownloadResult.ACTIVE, "interrupted transition should remain recoverable")
    expect(c.dm.entries.size == 1 && currentId(c) == id, "replacement should exist while old id stays recorded")
    val savedReplacement = c.dm.entries.keys.single()
    // A server may have permanently redirected the URL before the process died.
    c.dm.entries[savedReplacement]!!.apply {
        url = "https://redirect.example/app.apk"
        status = DownloadManager.STATUS_SUCCESSFUL
    }
    val afterRestart = newController(c)
    expect(!afterRestart.mayExposeCompleted(savedReplacement), "unrecorded replacement escaped before verification")
    expect(afterRestart.reconcile(id) == OfficialDownloadResult.SWITCHED, "transition did not recover")
    expect(currentId(c) == savedReplacement && c.dm.entries.size == 1, "recovery created a duplicate")
    expect(!afterRestart.mayExposeCompleted(savedReplacement), "recovered replacement escaped before verification")

    val d = FakeContext(); val ctl = newController(d); val old = ctl.start(release(), UpdateSiteMirror.Match(null))
    d.dm.entries[old]!!.apply { status=DownloadManager.STATUS_FAILED; reason=503 }
    d.dm.afterEnqueue = { d.prefs.failNextCommit = true; d.dm.afterEnqueue = null }
    ctl.reconcile(old)
    val replacement = d.dm.entries.keys.single()
    val (removedId, _) = ctl.clearForCancellation(); d.dm.remove(removedId)
    expect(d.dm.entries.isEmpty() && !ctl.mayExposeCompleted(replacement), "cancel did not remove pending replacement")
    expect(ctl.reconcile(old) == OfficialDownloadResult.IGNORED, "cancelled attempt resurrected")
}
private class SimulatedProcessDeath : Error()

private fun interruptedInitialEnqueue() {
    val file = apk()
    try {
        val c = FakeContext(); val first = newController(c)
        c.dm.afterEnqueue = { throw SimulatedProcessDeath() }
        try {
            first.start(release(ApkUpdate.sha256OfFile(file)), UpdateSiteMirror.Match(null))
            error("simulated process death did not happen")
        } catch (_: SimulatedProcessDeath) { /* The job exists, but its ID was never recorded. */ }
        c.dm.afterEnqueue = null
        val orphan = c.dm.entries.keys.single()
        c.dm.entries[orphan]!!.apply {
            status = DownloadManager.STATUS_SUCCESSFUL
            url = "https://redirect.example/app.apk"
            uri = file.toURI().toString()
        }
        val restarted = newController(c)
        expect(OfficialUpdateDownloads.trackedId(c) == OfficialUpdateDownloads.RECOVER_START_ID, "start recovery was not scheduled")
        expect(restarted.blocksNewDownload(), "interrupted start permits a duplicate")
        expect(!restarted.mayExposeCompleted(orphan), "unrecorded initial APK escaped validation")
        expect(restarted.reconcile(OfficialUpdateDownloads.RECOVER_START_ID) == OfficialDownloadResult.VERIFIED, "initial job did not recover and verify")
        expect(currentId(c) == orphan && c.dm.entries.size == 1 && restarted.mayExposeCompleted(orphan), "recovery duplicated or lost the verified job")

        val cancelled = FakeContext(); val ctl = newController(cancelled)
        cancelled.dm.afterEnqueue = { throw SimulatedProcessDeath() }
        try { ctl.start(release(), UpdateSiteMirror.Match(null)) } catch (_: SimulatedProcessDeath) { /* interrupted start */ }
        val cancelledId = cancelled.dm.entries.keys.single()
        ctl.clearForCancellation()
        expect(cancelled.dm.entries.isEmpty() && !ctl.mayExposeCompleted(cancelledId), "cancel failed to remove an interrupted start")
        expect(OfficialUpdateDownloads.trackedId(cancelled) == null, "cancelled start was restored")

        val removed = FakeContext(); val removedCtl = newController(removed)
        removed.dm.afterEnqueue = { throw SimulatedProcessDeath() }
        try { removedCtl.start(release(), UpdateSiteMirror.Match(null)) } catch (_: SimulatedProcessDeath) { /* interrupted start */ }
        removed.dm.entries.clear() // The user removed the system download.
        expect(removedCtl.reconcile(OfficialUpdateDownloads.RECOVER_START_ID) == OfficialDownloadResult.FAILED, "removed job was silently restarted")
        expect(removed.dm.entries.isEmpty(), "recovery recreated a removed job")
    } finally { file.delete() }
}

private fun duplicateEventsAreSerialized() {
    val c = FakeContext(); val ctl = newController(c); val id = ctl.start(release(), UpdateSiteMirror.Match(null))
    c.dm.entries[id]!!.apply { status=DownloadManager.STATUS_FAILED; reason=404 }
    val start = java.util.concurrent.CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
        val results = (0..1).map { executor.submit<OfficialDownloadResult> { start.await(); ctl.reconcile(id) } }
        start.countDown()
        val outcomes = results.map { it.get() }
        expect(outcomes.count { it == OfficialDownloadResult.SWITCHED } == 1, "concurrent callbacks switched twice")
        expect(outcomes.count { it == OfficialDownloadResult.IGNORED } == 1, "late concurrent callback was not ignored")
        expect(c.dm.entries.size == 1 && currentId(c) != id, "concurrent callbacks made duplicate jobs")
    } finally { executor.shutdownNow() }
}
private fun finalBadFileAndEnqueueFailureAreVisible() {
    val file = apk()
    try {
        val c = FakeContext(); val ctl = newController(c); val site = ctl.start(release("0".repeat(64)), UpdateSiteMirror.Match(null))
        c.dm.entries[site]!!.apply { status=DownloadManager.STATUS_FAILED; reason=503 }; ctl.reconcile(site)
        val relay = currentId(c)
        c.dm.entries[relay]!!.apply { status=DownloadManager.STATUS_FAILED; reason=503 }; ctl.reconcile(relay)
        val github = currentId(c)
        c.dm.entries[github]!!.apply { status=DownloadManager.STATUS_SUCCESSFUL; uri=file.toURI().toString() }
        expect(ctl.reconcile(github) == OfficialDownloadResult.FAILED, "last corrupt file must fail")
        expect(ctl.terminalFailureVersion() == "v11.74.501" && c.dm.entries.isEmpty(), "terminal failure not visible")
        expect(!ctl.mayExposeCompleted(github), "last corrupt file escaped quarantine")
        val failed = FakeContext(); val failedCtl = newController(failed)
        val first = failedCtl.start(release(), UpdateSiteMirror.Match(null))
        failed.dm.entries[first]!!.apply { status=DownloadManager.STATUS_FAILED; reason=503 }
        failed.dm.failEnqueue = true
        expect(failedCtl.reconcile(first) == OfficialDownloadResult.FAILED, "enqueue failure must be visible")
        expect(failedCtl.terminalFailureVersion() == "v11.74.501", "enqueue failure lost status")
    } finally { file.delete() }
}
fun main() {
    siteThenFallbackAndStaleEvents(); missingSiteAndLocalErrors(); verifiedAndCorruptFiles()
    interruptedSwitchAndCancellation(); interruptedInitialEnqueue(); duplicateEventsAreSerialized(); finalBadFileAndEnqueueFailureAreVisible()
    println("Official update DownloadManager simulation: 7 scenarios passed")
}
