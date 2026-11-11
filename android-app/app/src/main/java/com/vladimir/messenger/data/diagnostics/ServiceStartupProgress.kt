package com.vladimir.messenger.data.diagnostics

/** Process-local, fixed-label observations. Times include device sleep and are not CPU times. */
internal class ServiceStartupProgress(private val clock: () -> Long) {
    enum class Phase { NOT_STARTED, SERVICE_CREATED, PREPARING_KEYS, CORE_REQUESTED, WAITING_LIFECYCLE, INITIALIZING_ENGINE, FINISHED, STOPPED }
    data class Snapshot(val phase: Phase, val totalMs: Long?, val phaseMs: Map<Phase, Long>)

    private var token = Any()
    private var phase = Phase.NOT_STARTED
    private var started: Long? = null
    private var phaseStarted = 0L
    private var finishedAt: Long? = null
    private val durations = mutableMapOf<Phase, Long>()

    @Synchronized fun start() {
        val now = clock()
        token = Any()
        phase = Phase.SERVICE_CREATED
        started = now
        phaseStarted = now
        finishedAt = null
        durations.clear()
    }

    @Synchronized fun preparingKeys() = advanceIfActive(Phase.PREPARING_KEYS)
    @Synchronized fun coreRequested() = advanceIfActive(Phase.CORE_REQUESTED)

    /** Token prevents a late native task from changing a newer service's trace. */
    @Synchronized fun currentToken(): Any? = if (started != null && finishedAt == null) token else null

    @Synchronized fun lifecycleQueued(): Any? = lifecycleQueued(token)

    @Synchronized fun lifecycleQueued(expected: Any?): Any? {
        if (expected == null || expected !== token || phase != Phase.CORE_REQUESTED || finishedAt != null) return null
        advance(Phase.WAITING_LIFECYCLE, clock())
        return token
    }

    @Synchronized fun lifecycleEntered(expected: Any?) {
        if (expected != null && expected === token && phase == Phase.WAITING_LIFECYCLE && finishedAt == null) {
            advance(Phase.INITIALIZING_ENGINE, clock())
        }
    }

    /** Means the splash can close, including mirror and limited modes, not engine success. */
    @Synchronized fun finished() {
        if (started == null || finishedAt != null) return
        val now = clock()
        advance(Phase.FINISHED, now)
        finishedAt = now
    }

    @Synchronized fun stopped() {
        if (started == null) return
        val now = clock()
        if (finishedAt == null) {
            advance(Phase.STOPPED, now)
            finishedAt = now
        } else {
            // Preserve startup durations; the service no longer owns the core.
            phase = Phase.STOPPED
        }
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

    private fun advanceIfActive(next: Phase) {
        if (started != null && finishedAt == null) advance(next, clock())
    }

    private fun advance(next: Phase, now: Long) {
        durations[phase] = (durations[phase] ?: 0L) + (now - phaseStarted).coerceAtLeast(0)
        phase = next
        phaseStarted = now
    }
}
