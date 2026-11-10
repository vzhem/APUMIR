package com.vladimir.messenger.service

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory

private fun expect(value: Boolean, detail: String) { if (!value) error(detail) }

private data class Reply(val status: Int, val body: String = "", val cacheHeader: String? = null)
private object Network {
    var github = Reply(503)
    var relay = Reply(503)
    var site = Reply(503)
    val requested = mutableListOf<String>()
    fun reset() { github = Reply(503); relay = Reply(503); site = Reply(503); requested.clear() }

    fun install() = URL.setURLStreamHandlerFactory(URLStreamHandlerFactory { protocol ->
        if (protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                private val reply: Reply
                    get() = when {
                        url.host == "api.github.com" -> github
                        url.host == "apu.clan.su" -> site
                        url.host == "p2p-relay.apuchat.workers.dev" -> relay
                        else -> error("unexpected URL $url")
                    }
                override fun getResponseCode(): Int {
                    expect(url.query?.contains("nocache=") == true, "metadata URL did not bypass cache: $url")
                    expect(!useCaches, "connection uses a local cache")
                    expect(getRequestProperty("Cache-Control") == "no-cache, no-store", "cache headers missing")
                    synchronized(requested) { requested.add(url.host + url.path) }
                    return reply.status
                }
                override fun getInputStream() = ByteArrayInputStream(reply.body.toByteArray())
                override fun getHeaderField(name: String?): String? =
                    if (name.equals("X-Apu-Cache", ignoreCase = true)) reply.cacheHeader else null
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun connect() {}
            }
        }
    })
}

private class MemoryPrefs : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
    override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = data[key] as? Set<String> ?: defValues
    override fun contains(key: String) = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    inner class Editor : SharedPreferences.Editor {
        private val values = mutableMapOf<String, Any?>()
        override fun putLong(key: String, value: Long) = apply { values[key] = value }
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        override fun putStringSet(key: String, value: Set<String>?) = apply { values[key] = value?.toSet() }
        override fun remove(key: String) = apply { values[key] = null }
        override fun commit(): Boolean {
            values.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
            return true
        }
        override fun apply() { commit() }
    }
}
private data class Row(val id: Long, val request: DownloadManager.Request, var status: Int, var reason: Int = 0)
private class FakeDownloads : DownloadManager() {
    val rows = linkedMapOf<Long, Row>()
    private var nextId = 1L
    override fun enqueue(request: Request): Long {
        val id = nextId++
        rows[id] = Row(id, request, STATUS_PENDING)
        return id
    }
    override fun remove(vararg ids: Long): Int = ids.count { rows.remove(it) != null }
    override fun query(query: Query): Cursor = Rows(rows.values.filter { row ->
        (query.ids == null || row.id in query.ids!!) &&
            (query.statuses == null || row.status and query.statuses!! != 0)
    })
}
private class Rows(private val rows: List<Row>) : Cursor {
    private var current = -1
    private val cols = listOf(DownloadManager.COLUMN_ID, DownloadManager.COLUMN_TITLE, DownloadManager.COLUMN_URI,
        DownloadManager.COLUMN_STATUS, DownloadManager.COLUMN_REASON, DownloadManager.COLUMN_LOCAL_URI,
        DownloadManager.COLUMN_DESCRIPTION, DownloadManager.COLUMN_MEDIA_TYPE,
        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
    override fun moveToFirst() = if (rows.isEmpty()) false else { current = 0; true }
    override fun moveToNext() = ++current < rows.size
    override fun getColumnIndex(name: String) = cols.indexOf(name)
    override fun getColumnIndexOrThrow(name: String) = getColumnIndex(name).also { check(it >= 0) }
    private fun value(index: Int): Any? = rows[current].let { row -> when (cols[index]) {
        DownloadManager.COLUMN_ID -> row.id
        DownloadManager.COLUMN_TITLE -> row.request.title
        DownloadManager.COLUMN_URI -> row.request.uri.toString()
        DownloadManager.COLUMN_DESCRIPTION -> row.request.description
        DownloadManager.COLUMN_STATUS -> row.status
        DownloadManager.COLUMN_REASON -> row.reason
        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, DownloadManager.COLUMN_TOTAL_SIZE_BYTES -> 0L
        else -> null
    } }
    override fun getInt(index: Int) = value(index) as Int
    override fun getLong(index: Int) = value(index) as Long
    override fun getString(index: Int) = value(index) as String?
    override fun close() {}
}
private class FakeContext : Context() {
    private val preferences = mutableMapOf<String, MemoryPrefs>()
    val dm = FakeDownloads()
    override val packageManager = object : PackageManager() {}
    override fun getSystemService(name: String): Any? = if (name == DOWNLOAD_SERVICE) dm else null
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getOrPut(name) { MemoryPrefs() }
}
private const val OLD = "v11.74.501"
private const val NEW = "v11.74.502"
private val oldSha = "a".repeat(64)
private val newSha = "b".repeat(64)
private fun card(marker: Boolean = true, version: String = OLD) = UpdateChecker.ReleaseInfo(
    version = version, downloadUrl = "https://old.example/apk", releaseNotes = "old", publishedAt = "",
    apkSha256 = oldSha, allowSiteMirror = marker,
)
private fun releaseGate() = UpdateDownloadGate.lost(NEW)

private suspend fun freshSiteAfterSavedCard() {
    for (marker in listOf(true, false)) {
        Network.reset()
        Network.github = Reply(200, "github|$NEW|$newSha")
        Network.site = Reply(200, "site|$NEW|$newSha")
        val context = FakeContext(); val checker = UpdateChecker(context)
        val id = checker.downloadApk(card(marker), preferOfficial = true)
        val row = context.dm.rows[id] ?: error("no download")
        expect(row.request.uri.toString() == UpdateSiteMirror.APK_URL, "fresh site was not chosen")
        expect(row.request.title == "APU $NEW", "download used saved card version")
        expect(context.getSharedPreferences(OfficialUpdateDownloads.PREFS, 0)
            .getString("expected_sha256", "") == newSha, "download retained the saved hash")
        expect(Network.requested.any { it.startsWith("api.github.com/") }, "saved marker bypassed metadata refresh")
        releaseGate()
    }
}
private suspend fun oldSiteThenPinnedFallback() {
    Network.reset()
    Network.github = Reply(200, "github|$NEW|$newSha")
    Network.site = Reply(200, "site|$OLD|$oldSha")
    val context = FakeContext(); val checker = UpdateChecker(context)
    val id = checker.downloadApk(card(), preferOfficial = true)
    val row = context.dm.rows[id] ?: error("no relay download")
    expect(row.request.uri.toString().endsWith("/update/apk"), "old matching site/card was not skipped")
    expect(row.request.title == "APU $NEW", "relay used old version")
    row.status = DownloadManager.STATUS_FAILED; row.reason = 503
    expect(checker.reconcileOfficialDownload(id) == OfficialDownloadResult.SWITCHED, "no fallback")
    val next = context.dm.rows.values.single()
    expect(next.request.uri.toString() == pinnedGithubApkUrl(NEW), "fallback pinned old card")
    releaseGate()
}
private suspend fun staleRelayRejected(header: String?, marker: String) {
    Network.reset()
    Network.github = Reply(503)
    Network.relay = Reply(200, "relay|$OLD|$oldSha|$marker", header)
    Network.site = Reply(200, "site|$OLD|$oldSha")
    val context = FakeContext(); val checker = UpdateChecker(context)
    try {
        checker.downloadApk(card(), preferOfficial = true)
        error("stale relay authorized an update")
    } catch (_: UpdateChecker.UpdateMetadataUnavailableException) { /* expected */ }
    expect(context.dm.rows.isEmpty(), "unconfirmed release was enqueued")
    expect(Network.requested.none { it.startsWith("apu.clan.su/") }, "stale relay reached site selection")
}
private suspend fun freshLegacyRelayWorks() {
    Network.reset()
    Network.github = Reply(503)
    Network.relay = Reply(200, "relay|$NEW||absent", "redirect")
    Network.site = Reply(200, "site|$NEW|$newSha")
    val context = FakeContext(); val checker = UpdateChecker(context)
    val id = checker.downloadApk(card(), preferOfficial = true)
    expect(context.dm.rows[id]?.request?.uri.toString() == UpdateSiteMirror.APK_URL,
        "fresh response from the deployed legacy relay should select site")
    releaseGate()
}
private suspend fun regressionAndUnavailableFailClosed() {
    for (reply in listOf(Reply(200, "github|$OLD|$oldSha"), Reply(503))) {
        Network.reset(); Network.github = reply
        Network.site = Reply(200, "site|$OLD|$oldSha")
        val context = FakeContext(); val checker = UpdateChecker(context)
        try {
            checker.downloadApk(card(version = NEW), preferOfficial = true)
            error("older or unavailable metadata authorized a download")
        } catch (_: UpdateChecker.UpdateMetadataUnavailableException) { /* expected */ }
        expect(context.dm.rows.isEmpty(), "metadata failure produced a download")
    }
}
fun main() = runBlocking {
    Network.install()
    freshSiteAfterSavedCard()
    oldSiteThenPinnedFallback()
    staleRelayRejected("stale", "absent")
    staleRelayRejected(null, "true")
    freshLegacyRelayWorks()
    regressionAndUnavailableFailClosed()
    println("Actual UpdateChecker + DownloadManager + fake HTTP: 6 scenarios passed")
}
