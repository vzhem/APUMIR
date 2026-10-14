package com.vladimir.messenger.data.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

enum class DiagnosticPhase { PROCESS_START, SERVICE_START, CORE_READY, SERVICE_STOP, FOREGROUND, BACKGROUND }

/** Only fixed phases/times and numeric storage baselines are persisted, never correlation IDs. */
object OperationalDiagnostics {
    val tracker = OperationalHealthTracker { SystemClock.uptimeMillis() }
    val wake = WakeHoldTracker()
    private val lock = Any()
    private var file: AtomicFile? = null
    private val phases = ArrayDeque<Pair<DiagnosticPhase, Long>>()
    private var baselineAt: Long? = null
    private var baselineBytes: Long? = null
    private var baselineLiveRows: Long? = null
    private var baselineExpiredRows: Long? = null
    private val latestWrite = AtomicReference<String?>()
    private val writing = AtomicBoolean()
    private val writer by lazy { Executors.newSingleThreadExecutor { Thread(it, "apu-health-journal").apply { isDaemon = true } } }
    @Volatile private var persistence = "not_installed"

    fun install(context: Context) {
        synchronized(lock) {
            if (file != null) return
            file = AtomicFile(File(context.noBackupFilesDir, "operational-health-v1.json"))
            persistence = "ready"
            runCatching {
                val source = file!!.baseFile
                if (!source.exists()) return@runCatching
                require(source.length() <= 32768)
                val json = JSONObject(file!!.openRead().bufferedReader().use { it.readText() })
                val history = json.optJSONArray("phases") ?: JSONArray()
                require(history.length() <= 64)
                for (i in 0 until history.length()) {
                    val item = history.getJSONObject(i)
                    val phase = runCatching { DiagnosticPhase.valueOf(item.getString("phase")) }.getOrNull() ?: continue
                    phases.addLast(phase to item.getLong("at"))
                }
                if (json.has("baselineAt")) {
                    baselineAt = json.getLong("baselineAt")
                    baselineBytes = json.getLong("baselineBytes")
                    baselineLiveRows = json.optLong("baselineLive", -1).takeIf { it >= 0 }
                    baselineExpiredRows = json.optLong("baselineExpired", -1).takeIf { it >= 0 }
                }
            }.onFailure { phases.clear(); baselineAt = null; baselineBytes = null; persistence = "read_failed" }
        }
        phase(DiagnosticPhase.PROCESS_START)
    }

    fun phase(phase: DiagnosticPhase) {
        synchronized(lock) {
            if (file == null) return
            phases.addLast(phase to System.currentTimeMillis())
            while (phases.size > 64) phases.removeFirst()
            queueWriteLocked()
        }
    }

    fun storageTrend(storage: RelayStorageDiagnostics): String = synchronized(lock) {
        val current = storage.stores.firstOrNull { !it.legacy && it.status == "ready" }
            ?: return@synchronized "storage_trend=unavailable (partial/missing audit)\n"
        val bytes = current.dbBytes + current.walBytes + current.shmBytes
        val full = current.tables.all { it.totalRows != null && it.expiredTotalRows != null && it.expiredTotalRows <= it.totalRows && it.status == "ready" }
        val expired = if (full) current.tables.sumOf { it.expiredTotalRows!! } else null
        val live = if (full) current.tables.sumOf { it.totalRows!! - it.expiredTotalRows!! } else null
        val now = System.currentTimeMillis()
        val output = "[динамика хранилища]\n" +
            "baseline_age_ms=${baselineAt?.let { (now - it).coerceAtLeast(0) } ?: -1} · physical_delta_bytes=${baselineBytes?.let { bytes - it } ?: "unknown"}\n" +
            "live_rows_delta=${if (live != null && baselineLiveRows != null) live - baselineLiveRows!! else "unknown"} · expired_rows_delta=${if (expired != null && baselineExpiredRows != null) expired - baselineExpiredRows!! else "unknown"}\n" +
            "Сравнение current SQLite/WAL/SHM с прошлым успешным отчётом, не суточная скорость и не причина удаления. Отрицательные дельты допустимы.\n"
        baselineAt = now; baselineBytes = bytes; baselineLiveRows = live; baselineExpiredRows = expired
        if (file != null) queueWriteLocked()
        output
    }

    fun wakeReport(): String {
        val sample = wake.snapshot(SystemClock.elapsedRealtime())
        return "[энергия и фон]\ncore_wake_acquisitions=${sample.first} · core_wake_timed_upper_ms=${sample.second}\n" +
            "Учитываются только успешные timed acquire ядра, release и таймаут; это верхняя оценка удержания, не измерение расхода батареи и не все wakelock приложения.\n"
    }

    fun journalReport(): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        buildString {
            appendLine("[устойчивый журнал фаз]")
            appendLine("phase_journal=$persistence · max_entries=64 · max_read_bytes=32768 · local_only=true")
            for ((phase, at) in phases) appendLine("phase=${phase.name.lowercase()} · ago_ms=${(now - at).coerceAtLeast(0)}")
            appendLine("AtomicFile + coalesced writer; последнее событие перед убийством может не сохраниться. Нет трасс, текстов и идентификаторов.")
        }
    }

    private fun queueWriteLocked() {
        val json = JSONObject().put("phases", JSONArray().apply {
            phases.forEach { (phase, at) -> put(JSONObject().put("phase", phase.name).put("at", at)) }
        })
        if (baselineAt != null) json.put("baselineAt", baselineAt).put("baselineBytes", baselineBytes)
            .put("baselineLive", baselineLiveRows ?: -1).put("baselineExpired", baselineExpiredRows ?: -1)
        latestWrite.set(json.toString())
        scheduleWrite()
    }
    private fun scheduleWrite() {
        if (!writing.compareAndSet(false, true)) return
        writer.execute {
            try {
                while (true) {
                    val body = latestWrite.getAndSet(null) ?: break
                    val target = synchronized(lock) { file } ?: break
                    var stream: java.io.FileOutputStream? = null
                    try {
                        stream = target.startWrite()
                        target.baseFile.parentFile?.mkdirs()
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        check(bytes.size <= 32768)
                        stream.write(bytes)
                        target.finishWrite(stream)
                    } catch (_: Exception) { stream?.let { target.failWrite(it) }; persistence = "write_failed" }
                }
            } finally {
                writing.set(false)
                if (latestWrite.get() != null) scheduleWrite()
            }
        }
    }
}
