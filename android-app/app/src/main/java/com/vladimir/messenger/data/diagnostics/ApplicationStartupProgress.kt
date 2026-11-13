package com.vladimir.messenger.data.diagnostics

/** Process-local measurements of main-thread Application.onCreate; no identity data. */
internal class ApplicationStartupProgress(private val clock: () -> Long) {
    enum class Phase { NOT_STARTED, INJECTION, PREPARING, RESTORING, IDENTITY_HISTORY, OBSOLETE_CLEANUP, AFTER_CLEANUP, FINISHED }
    data class Snapshot(val phase: Phase, val totalMs: Long?, val phaseMs: Map<Phase, Long>)

    private var phase = Phase.NOT_STARTED
    private var started: Long? = null
    private var phaseStarted = 0L
    private var finishedAt: Long? = null
    private val durations = mutableMapOf<Phase, Long>()

    @Synchronized fun start() {
        val now = clock()
        started = now
        phaseStarted = now
        finishedAt = null
        phase = Phase.INJECTION
        durations.clear()
    }

    /** Refuse repeats and backward transitions so they cannot mislabel measured work. */
    @Synchronized fun advance(next: Phase) {
        if (started == null || finishedAt != null || next <= phase || next == Phase.FINISHED) return
        move(next, clock())
    }

    @Synchronized fun finish() {
        if (started == null || finishedAt != null) return
        val now = clock()
        move(Phase.FINISHED, now)
        finishedAt = now
    }

    @Synchronized fun snapshot(): Snapshot {
        val now = clock()
        val elapsed = started?.let { ((finishedAt ?: now) - it).coerceAtLeast(0) }
        val measured = durations.toMutableMap()
        if (started != null && finishedAt == null) {
            measured[phase] = (measured[phase] ?: 0L) + (now - phaseStarted).coerceAtLeast(0)
        }
        return Snapshot(phase, elapsed, measured)
    }

    private fun move(next: Phase, now: Long) {
        durations[phase] = (durations[phase] ?: 0L) + (now - phaseStarted).coerceAtLeast(0)
        phase = next
        phaseStarted = now
    }
}
