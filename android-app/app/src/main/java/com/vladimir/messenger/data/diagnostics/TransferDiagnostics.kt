package com.vladimir.messenger.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A small, privacy-conscious diagnostic trail for direct file-transfer acceptance testing.
 *
 * Android does not grant ordinary applications permission to read every application's logcat. We
 * therefore keep a bounded in-process record for the important F4 decisions and, where Android
 * permits it, append only relevant lines from this process's logcat. The report deliberately omits
 * chat text, names, filenames, encrypted bytes, keys, node IDs and IP addresses.
 */
object TransferDiagnostics {
    private const val TAG = "ApuDiagnostics"
    private const val MAX_EVENTS = 160
    private const val MAX_LOGCAT_LINES = 100
    private val lock = Any()
    private val events = ArrayDeque<String>(MAX_EVENTS)
    private val eventTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Records only a short, caller-supplied operational fact; never pass message/file contents. */
    fun record(area: String, detail: String) {
        val safeArea = area.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        val safeDetail = redact(detail).replace('\n', ' ').take(220)
        val line = "${eventTimeFormat.format(Date())} $safeArea: $safeDetail"
        synchronized(lock) {
            while (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
        Log.i(TAG, line)
    }

    /**
     * Builds text intended for manual copy into a test report. It must run off the main thread:
     * native status calls and the optional `logcat -d` subprocess can block briefly.
     */
    fun buildReport(context: Context): String = buildString {
        appendLine("APU direct-file diagnostics")
        appendLine("Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
        appendLine("Privacy: this report excludes chat text, filenames, ciphertext, keys, contact IDs and IP addresses.")
        appendLine()
        appendLine("[environment]")
        appendLine("app=${appVersion(context)}")
        appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("device=${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(40)}")
        appendLine("core=${safeNative { RustBridge.coreBuildInfo() }}")
        appendLine("network=${safeNative { RustBridge.networkStatus() }}")
        appendLine("connected_peers=${safeNative { RustBridge.connectedPeers().toString() }}")
        appendLine("relay_custody=${safeNative { RustBridge.relayCustodyMode() }}")
        appendLine("pending_core_events=${safeNative { RustBridge.pendingEvents().toString() }}")
        appendLine()
        appendLine("[F4 app events]")
        val snapshot = synchronized(lock) { events.toList() }
        if (snapshot.isEmpty()) {
            appendLine("No F4 events have been recorded in this app process yet. Run the test, then reopen this screen.")
        } else {
            snapshot.forEach { appendLine(it) }
        }
        appendLine()
        appendLine("[relevant native/app logcat]")
        val logcat = relevantLogcat()
        if (logcat.isEmpty()) {
            appendLine("No relevant process logcat lines were available. The in-app event list above is still usable.")
        } else {
            logcat.forEach { appendLine(it) }
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName ?: "unknown"} ($versionCode)"
    }.getOrDefault("unknown")

    private fun safeNative(value: () -> String): String = redact(runCatching(value).getOrDefault("unavailable"))

    /**
     * `--pid` makes this bounded to APU's own process. Some older Android builds reject that
     * argument; an empty section is safer than falling back to unrestricted logcat.
     */
    private fun relevantLogcat(): List<String> = runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "--pid=${Process.myPid()}",
            "*:V",
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().useLines { sequence ->
            sequence
                .filter(::isRelevantTransferLog)
                .map(::redact)
                .take(MAX_LOGCAT_LINES)
                .toList()
        }
        process.waitFor(2, TimeUnit.SECONDS)
        process.destroy()
        lines
    }.getOrDefault(emptyList())

    private fun isRelevantTransferLog(line: String): Boolean {
        val hasKnownTag = line.contains("p2p_core") ||
            line.contains("FileTransferSender") ||
            line.contains("FileTransferReceiver") ||
            line.contains("FileTransferRouter") ||
            line.contains(TAG)
        if (!hasKnownTag) return false
        return line.contains("F4") ||
            line.contains("FCAP") ||
            line.contains("APUF") ||
            line.contains("FILE CHUNK") ||
            line.contains("file_chunk_received")
    }

    /** Avoid copying durable contact IDs and network addresses into a test report. */
    internal fun redact(text: String): String = text
        .replace(Regex("pk_[0-9a-f]{32,64}"), "[contact]")
        .replace(Regex("(?i)\\b[0-9a-f]{32}\\b"), "[transfer]")
        .replace(Regex("(?i)(\\bfrom\\s+)[0-9a-f]{8}\\b")) { match ->
            "${match.groupValues[1]}[contact]"
        }
        .replace(Regex("(?<![0-9A-Fa-f])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?"), "[ip]")
        .replace(Regex("(?i)(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]*"), "[ipv6]")
}
