package com.vladimir.messenger.data.diagnostics

/** Process-local, fixed-label observations. Times include device sleep and are not CPU times. */
internal class ServiceStartupProgress(private val clock: () -> Long) {
    enum class Phase { NOT_STARTED, SERVICE_CREATED, PREPARING_KEYS, CORE_REQUESTED, WAITING_LIFECYCLE, INITIALIZING_ENGINE, FINISHED, STOPPED }
    enum class EnginePhase {
        NOT_STARTED, CHECKING_EXISTING, CLEANING_PREVIOUS, RESERVING_SLOT, PREPARING_CORE,
        CREATING_ENGINE, APPLYING_POLICY, STARTING_ENGINE, READING_NODE, READING_PUBLIC_KEY,
        ABORTING, RETURNED,
    }
    data class Snapshot(
        val phase: Phase,
        val totalMs: Long?,
        val phaseMs: Map<Phase, Long>,
        val enginePhase: EnginePhase,
        val enginePhaseMs: Map<EnginePhase, Long>,
    )

    private var token = Any()
    private var phase = Phase.NOT_STARTED
    private var started: Long? = null
    private var phaseStarted = 0L
    private var finishedAt: Long? = null
    private val durations = mutableMapOf<Phase, Long>()
    private var enginePhase = EnginePhase.NOT_STARTED
    private var enginePhaseStarted: Long? = null
    private val engineDurations = mutableMapOf<EnginePhase, Long>()

    @Synchronized fun start() {
        val now = clock()
        token = Any()
        phase = Phase.SERVICE_CREATED
        started = now
        phaseStarted = now
        finishedAt = null
        durations.clear()
        enginePhase = EnginePhase.NOT_STARTED
        enginePhaseStarted = null
        engineDurations.clear()
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
            val now = clock()
            advance(Phase.INITIALIZING_ENGINE, now)
            enginePhase = EnginePhase.CHECKING_EXISTING
            enginePhaseStarted = now
        }
    }

    /** Fixed labels only. A worker or an obsolete service cannot write another owner's trace. */
    @Synchronized fun engineStage(expected: Any?, next: EnginePhase) {
        if (expected == null || expected !== token || phase != Phase.INITIALIZING_ENGINE ||
            finishedAt != null || enginePhaseStarted == null ||
            next == EnginePhase.NOT_STARTED || next == EnginePhase.RETURNED || next == enginePhase
        ) return
        val now = clock()
        finishEngineStage(now)
        enginePhase = next
        enginePhaseStarted = now
    }

    /** Returning is not success: the caller separately reports its actual startup outcome. */
    @Synchronized fun engineReturned(expected: Any?) {
        if (expected == null || expected !== token || phase != Phase.INITIALIZING_ENGINE ||
            finishedAt != null || enginePhaseStarted == null
        ) return
        finishEngineStage(clock())
        enginePhase = EnginePhase.RETURNED
    }

    /** Means the splash can close, including mirror and limited modes, not engine success. */
    @Synchronized fun finished() {
        if (started == null || finishedAt != null) return
        val now = clock()
        finishEngineStage(now)
        advance(Phase.FINISHED, now)
        finishedAt = now
    }

    @Synchronized fun stopped() {
        if (started == null) return
        val now = clock()
        if (finishedAt == null) {
            finishEngineStage(now)
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
        val engineMeasured = engineDurations.toMutableMap()
        enginePhaseStarted?.let { since ->
            engineMeasured[enginePhase] = (engineMeasured[enginePhase] ?: 0L) + (now - since).coerceAtLeast(0)
        }
        return Snapshot(phase, elapsed, measured, enginePhase, engineMeasured)
    }

    private fun finishEngineStage(now: Long) {
        enginePhaseStarted?.let { since ->
            engineDurations[enginePhase] = (engineDurations[enginePhase] ?: 0L) + (now - since).coerceAtLeast(0)
        }
        enginePhaseStarted = null
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

/** Only enum names and durations are exported; missing stages were not observed. */
internal object ServiceStartupReport {
    fun render(sample: ServiceStartupProgress.Snapshot): String {
        fun ms(phase: ServiceStartupProgress.Phase): Long = sample.phaseMs[phase] ?: 0L
        val steps = ServiceStartupProgress.EnginePhase.values().mapNotNull { step ->
            sample.enginePhaseMs[step]?.let { "${step.name.lowercase()}:$it" }
        }.joinToString(",").ifEmpty { "none" }
        return "[запуск службы: текущий процесс]\n" +
            "startup_phase=${sample.phase.name.lowercase()} · startup_elapsed_ms=${sample.totalMs ?: -1}\n" +
            "preparing_keys_ms=${ms(ServiceStartupProgress.Phase.PREPARING_KEYS)} · " +
            "waiting_lifecycle_ms=${ms(ServiceStartupProgress.Phase.WAITING_LIFECYCLE)} · " +
            "initializing_engine_ms=${ms(ServiceStartupProgress.Phase.INITIALIZING_ENGINE)}\n" +
            "engine_step=${sample.enginePhase.name.lowercase()} · engine_step_ms=$steps\n" +
            "Время включает сон устройства; отсчёт начинается с создания службы, не с Application.onCreate. " +
            "FINISHED означает завершение заставки, не подтверждение работающего ядра. " +
            "Возврат и этапы запуска не доказывают успех; при ошибке очистка может войти в последний этап.\n"
    }
}
