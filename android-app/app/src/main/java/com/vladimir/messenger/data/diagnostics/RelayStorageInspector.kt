package com.vladimir.messenger.data.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import com.vladimir.messenger.data.swarm.StorageSettings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** On-demand, read-only metadata inspection. Never decrypts, deletes, checkpoints or vacuums. */
internal object RelayStorageInspector {
    internal const val SAMPLE_ROWS_PER_TABLE = 512
    internal const val MAX_TOTAL_SAMPLE_ROWS = 2048
    internal const val QUERY_BUDGET_MS = 2000L

    fun collect(context: Context): RelayStorageDiagnostics {
        val app = context.applicationContext
        val own = app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getString("node_id", "").orEmpty()
        return inspect(app.filesDir, own, System.currentTimeMillis(), StorageSettings.quota(app))
    }

    /** Isolated directory seam for instrumentation; does not access app identity/keys. */
    internal fun inspect(root: File, own: String, nowMs: Long, quota: Long, budgetMs: Long = QUERY_BUDGET_MS): RelayStorageDiagnostics {
        val started = SystemClock.elapsedRealtime()
        val cancel = CancellationSignal()
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "relay-diag-budget").apply { isDaemon = true }
        }
        val boundedBudget = budgetMs.coerceIn(0L, QUERY_BUDGET_MS)
        if (boundedBudget == 0L) cancel.cancel()
        val timeout = timer.schedule({ cancel.cancel() }, boundedBudget, TimeUnit.MILLISECONDS)
        val accumulator = RelayStorageAccumulator(own, nowMs)
        var remaining = MAX_TOTAL_SAMPLE_ROWS
        try {
            val stores = listOf(false, true).map { legacy ->
                val file = File(root, if (legacy) "apu_relay.sqlite.relay.sqlite" else "apu_relay.sqlite")
                inspectStore(file, legacy, cancel, accumulator, nowMs = nowMs, capacity = { minOf(SAMPLE_ROWS_PER_TABLE, remaining) },
                    consumed = { remaining -= it })
            }
            return RelayStorageDiagnostics(stores, quota, SystemClock.elapsedRealtime() - started,
                if (stores.all { it.status in setOf("ready", "missing") }) "ready" else "partial")
        } finally {
            timeout.cancel(false)
            timer.shutdownNow()
        }
    }

    private fun inspectStore(
        file: File,
        legacy: Boolean,
        cancel: CancellationSignal,
        accumulator: RelayStorageAccumulator,
        nowMs: Long,
        capacity: () -> Int,
        consumed: (Int) -> Unit,
    ): RelayStoreDiagnostic {
        var result = RelayStoreDiagnostic(legacy, dbBytes = file.length(),
            walBytes = File(file.path + "-wal").length(), shmBytes = File(file.path + "-shm").length())
        if (!file.isFile) return result.copy(status = "missing")
        if (cancel.isCanceled) return result.copy(status = "timeout")
        try {
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                // Connection setting only; do not wait seconds for a concurrent writer.
                db.rawQuery("PRAGMA busy_timeout=100", null, cancel).use { it.moveToFirst() }
                fun scalar(sql: String): Long = db.rawQuery(sql, null, cancel).use {
                    check(it.moveToFirst())
                    it.getLong(0)
                }
                fun exists(name: String): Boolean = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name), cancel,
                ).use { it.moveToFirst() }
                result = result.copy(
                    pageSize = scalar("PRAGMA page_size"), pages = scalar("PRAGMA page_count"),
                    freePages = scalar("PRAGMA freelist_count"),
                    unknownTables = scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT IN " +
                        "('relay_messages','relay_records_enc','relay_quarantine','relay_tombstones','relay_schema_version','android_metadata','sqlite_sequence')").toInt(),
                )
                val tables = mutableListOf<RelayTableDiagnostic>()
                for (kind in RelayTableKind.entries) {
                    val name = when (kind) {
                        RelayTableKind.LEGACY -> "relay_messages"
                        RelayTableKind.ENCRYPTED -> "relay_records_enc"
                        RelayTableKind.QUARANTINE -> "relay_quarantine"
                    }
                    if (cancel.isCanceled) { result = result.copy(status = "timeout"); break }
                    if (!exists(name)) continue
                    val sample = accumulator.table(kind)
                    val limit = capacity()
                    if (limit == 0) {
                        tables.add(sample.finish(null, limited = true, status = "partial"))
                        result = result.copy(tables = tables.toList(), status = "partial")
                        continue
                    }
                    var limited = false
                    var examined = 0
                    try {
                        val sender = if (kind == RelayTableKind.LEGACY) boundedId("origin_sender") else "NULL"
                        val created = if (kind == RelayTableKind.LEGACY) "created_at_ms" else "NULL"
                        val payloadColumn = if (kind == RelayTableKind.LEGACY) "e2e_payload" else "envelope"
                        // Only metadata and length(BLOB), never the BLOB, a prefix or its hash.
                        val sql = "SELECT ${boundedId("msg_id")}, ${boundedId("recipient")}, $sender, " +
                            "length($payloadColumn), $created, expires_at_ms FROM $name LIMIT ${limit + 1}"
                        db.rawQuery(sql, null, cancel).use { rows ->
                            while (rows.moveToNext()) {
                                cancel.throwIfCanceled()
                                if (examined == limit) { limited = true; break }
                                sample.add(rows.getString(0), rows.getString(1), rows.getString(2), rows.getLong(3),
                                    if (rows.isNull(4)) null else rows.getLong(4),
                                    if (rows.isNull(5)) null else rows.getLong(5))
                                examined++
                            }
                        }
                        // Count uses SQLite's indexes, not payload contents; cancellation bounds it too.
                        val count = scalar("SELECT COUNT(*) FROM $name")
                        val expiredCount = scalar("SELECT COUNT(*) FROM $name WHERE expires_at_ms <= $nowMs")
                        val earliestExpiry = db.rawQuery("SELECT MIN(expires_at_ms) FROM $name", null, cancel).use {
                            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                        }
                        tables.add(sample.finish(count, limited).copy(expiredTotalRows = expiredCount, earliestExpiryMs = earliestExpiry))
                    } catch (_: OperationCanceledException) {
                        tables.add(sample.finish(null, limited = true, status = "timeout"))
                        result = result.copy(status = "timeout")
                    } catch (_: Exception) {
                        tables.add(sample.finish(null, limited = true, status = if (cancel.isCanceled) "timeout" else "unavailable"))
                        result = result.copy(status = if (cancel.isCanceled) "timeout" else "partial")
                    } finally {
                        consumed(examined)
                        result = result.copy(tables = tables.toList())
                    }
                }
                if (!cancel.isCanceled && exists("relay_tombstones")) {
                    result = result.copy(tombstones = scalar("SELECT COUNT(*) FROM relay_tombstones"))
                }
                if (cancel.isCanceled) result = result.copy(status = "timeout")
                else if (result.status == "unavailable") result = result.copy(status = "ready")
            }
        } catch (_: OperationCanceledException) {
            result = result.copy(status = "timeout")
        } catch (_: Exception) {
            // Never print SQLite exceptions: they may include paths, SQL or private values.
            result = result.copy(status = if (cancel.isCanceled) "timeout" else "unavailable")
        }
        return result
    }

    private fun boundedId(column: String): String = "CASE WHEN length($column)<=256 THEN $column ELSE NULL END"
}
