package com.vladimir.messenger.service

/**
 * The saved timestamp is useful history, but cannot prove a service survived
 * process death, reboot, or replacing the APK. Wake decisions use only this
 * process-local state, reset when the real service stops or startup is refused.
 * Synchronization also prevents a late polling callback from resurrecting a
 * stopped service's heartbeat. Swiping a task alone does not call [stopped].
 */
internal class ProcessHeartbeat(private val persistEveryMs: Long) {
    private var active = false
    private var lastObservedMs = 0L
    private var lastPersistedMs = 0L

    @Synchronized
    fun started(nowMs: Long, persist: (Long) -> Unit) {
        if (active) {
            pulse(nowMs, persist)
            return
        }
        active = true
        lastObservedMs = nowMs
        persist(nowMs)
        lastPersistedMs = nowMs
    }

    @Synchronized
    fun pulse(nowMs: Long, persist: (Long) -> Unit) {
        if (!active) return
        lastObservedMs = nowMs
        if (lastPersistedMs <= 0L || nowMs < lastPersistedMs ||
            nowMs - lastPersistedMs >= persistEveryMs
        ) {
            persist(nowMs)
            lastPersistedMs = nowMs
        }
    }

    @Synchronized
    fun stopped() {
        active = false
        lastObservedMs = 0L
    }

    @Synchronized
    fun lastForWake(): Long = if (active) lastObservedMs else 0L
}
