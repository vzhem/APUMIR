package com.vladimir.messenger.data.diagnostics

/** Fixed-cardinality metrics: never accept a path, ID, exception message or user text as a label. */
enum class DiagnosticOperation {
    CORE_EVENTS, FILE_PUMP, GROUP_FILE_PUMP, APK_PUMP, DIRECT_DELETE_PUMP, GROUP_DELETE_PUMP,
    CHAT_RETRY_PUMP, REPORT_FACTS, LOGCAT_READ, STORAGE_AUDIT, KEY_MODE_PROBE,
}

enum class DiagnosticOutcome { OK, FAILED, CANCELLED }

data class OperationHealth(
    val operation: DiagnosticOperation,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
    val lastMs: Long,
    val maxMs: Long,
    val inFlight: Int,
    val oldestInFlightMs: Long?,
    val lastFinishedAgoMs: Long?,
)

class OperationHealthTracker(private val clock: () -> Long) {
    data class Ticket internal constructor(internal val id: Long, internal val operation: DiagnosticOperation)
    private data class Running(val ticket: Ticket, val at: Long)
    private data class Totals(var completed: Long = 0, var failed: Long = 0, var cancelled: Long = 0,
        var last: Long = 0, var max: Long = 0, var finished: Long? = null)
    private val active = LinkedHashMap<Long, Running>()
    private val totals = LinkedHashMap<DiagnosticOperation, Totals>()
    private var sequence = 0L
    @Volatile var dropped: Long = 0L
        private set

    @Synchronized fun begin(operation: DiagnosticOperation): Ticket? {
        if (active.size >= 32) { dropped++; return null }
        val ticket = Ticket(++sequence, operation)
        active[ticket.id] = Running(ticket, clock())
        return ticket
    }

    @Synchronized fun finish(ticket: Ticket?, outcome: DiagnosticOutcome) {
        if (ticket == null) return
        val candidate = active[ticket.id] ?: return
        if (candidate.ticket !== ticket) return
        val running = active.remove(ticket.id) ?: return
        val now = clock()
        val duration = (now - running.at).coerceAtLeast(0)
        val total = totals.getOrPut(running.ticket.operation) { Totals() }
        total.completed++
        if (outcome == DiagnosticOutcome.FAILED) total.failed++
        if (outcome == DiagnosticOutcome.CANCELLED) total.cancelled++
        total.last = duration
        total.max = maxOf(total.max, duration)
        total.finished = now
    }

    @Synchronized fun snapshot(): List<OperationHealth> {
        val now = clock()
        return DiagnosticOperation.entries.mapNotNull { operation ->
            val running = active.values.filter { it.ticket.operation == operation }
            val total = totals[operation]
            if (total == null && running.isEmpty()) return@mapNotNull null
            OperationHealth(operation, total?.completed ?: 0, total?.failed ?: 0, total?.cancelled ?: 0,
                total?.last ?: 0, total?.max ?: 0, running.size,
                running.minOfOrNull { it.at }?.let { (now - it).coerceAtLeast(0) },
                total?.finished?.let { (now - it).coerceAtLeast(0) })
        }
    }
}

data class UiResponsiveness(val active: Boolean, val samples: Long, val lateCompleted: Long,
    val overdueProbes: Long, val maxCompletedLagMs: Long, val pendingAgeMs: Long?)

/** Uptime clock + visibility gate: screen-off/background time is not called an ANR. */
class UiResponsivenessTracker {
    private var active = false
    private var sequence = 0L
    private var pending: Pair<Long, Long>? = null
    private var reported = false
    private var samples = 0L
    private var late = 0L
    private var overdue = 0L
    private var maxLag = 0L

    @Synchronized fun visible(value: Boolean) {
        if (active != value) { active = value; pending = null; reported = false }
    }
    @Synchronized fun begin(now: Long): Long? {
        if (!active || pending != null) return null
        val id = ++sequence
        pending = id to now
        reported = false
        return id
    }
    @Synchronized fun complete(id: Long, now: Long) {
        val current = pending ?: return
        if (!active || id != current.first) return
        val lag = (now - current.second).coerceAtLeast(0)
        samples++
        if (lag >= LATE_MS) late++
        maxLag = maxOf(maxLag, lag)
        pending = null
    }
    @Synchronized fun checkOverdue(now: Long): Long? {
        val current = pending ?: return null
        val age = (now - current.second).coerceAtLeast(0)
        if (!active || reported || age < LATE_MS) return null
        reported = true
        overdue++
        return age
    }
    @Synchronized fun snapshot(now: Long) = UiResponsiveness(active, samples, late, overdue, maxLag,
        pending?.let { (now - it.second).coerceAtLeast(0) })

    companion object { const val LATE_MS = 1500L }
}

data class RuntimeHealthSnapshot(
    val ui: UiResponsiveness? = null,
    val operations: List<OperationHealth> = emptyList(),
    val droppedTimings: Long = 0,
    val nativeHeapBytes: Long? = null,
    val pssKiB: Int? = null,
    val trimCallbacks: Long = 0,
    val lastTrimLevel: Int? = null,
    val exitHistory: List<PreviousProcessExit> = emptyList(),
    val exitStatus: ExitHistoryStatus = ExitHistoryStatus.UNAVAILABLE,
) {
    fun render(): String = buildString {
        appendLine("[отзывчивость и работа служб]")
        appendLine("UI: foreground/screen-on probes, uptime clock; задержка не является доказательством ANR.")
        val state = ui
        appendLine("ui_probe_active=${state?.active ?: false} · completed=${state?.samples ?: 0} · late_completed=${state?.lateCompleted ?: 0} · overdue_probes=${state?.overdueProbes ?: 0}")
        appendLine("ui_max_completed_lag_ms=${state?.maxCompletedLagMs ?: -1} · ui_pending_age_ms=${state?.pendingAgeMs ?: -1}")
        appendLine("late_completed и overdue_probes могут относиться к одной пробе; не складывать.")
        appendLine("native_heap_bytes=${nativeHeapBytes ?: -1} · process_pss_kib=${pssKiB ?: -1}")
        appendLine("memory_trim_callbacks=$trimCallbacks · last_trim_level=${lastTrimLevel ?: -1} (включает уход UI в фон, не только нехватку памяти)")
        appendLine("operation_timing_dropped=$droppedTimings")
        for (op in operations) appendLine("op=${op.operation.name.lowercase()} · completed=${op.completed} · failed=${op.failed} · cancelled=${op.cancelled} · last_ms=${op.lastMs} · max_ms=${op.maxMs} · in_flight=${op.inFlight} · oldest_in_flight_ms=${op.oldestInFlightMs ?: -1} · last_finished_ago_ms=${op.lastFinishedAgoMs ?: -1}")
        appendLine("Время операции включает ожидание IO/очередей; задержка не доказывает причину и не подтверждает доставку.")
        appendLine("[предыдущие завершения]")
        appendLine("os_exit_history=${exitStatus.name.lowercase()}")
        for (exit in exitHistory.take(3)) appendLine("reason=${exit.reason.name.lowercase()} · ago_ms=${exit.agoMs} · importance=${exit.importance}")
        appendLine("Это сведения Android, не полный журнал падений; описания, трассы, PID и имена процессов не копируются.")
    }
}

enum class ExitHistoryStatus { READY, UNAVAILABLE, UNSUPPORTED }
enum class ProcessExitReason { ANR, CRASH, NATIVE_CRASH, LOW_MEMORY, USER_REQUESTED, OTHER }
data class PreviousProcessExit(val reason: ProcessExitReason, val agoMs: Long, val importance: Int)
