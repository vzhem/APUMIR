package com.vladimir.messenger.service

import java.lang.ref.WeakReference

/**
 * The saved timestamp is useful history, but cannot prove a service survived
 * process death, reboot, or replacing the APK. Wake decisions use only this
 * process-local state. Ownership is registered at Service.onCreate, before a
 * start can fail; an old callback cannot refresh or stop a replacement, even
 * while the replacement is still starting or has itself been refused.
 *
 * Creation callbacks must be registered in lifecycle order (Android delivers
 * them on the main thread). Owners are weak so a missed lifecycle callback
 * cannot retain a destroyed service. Swiping a task alone does not stop it.
 */
internal class ProcessHeartbeat(private val persistEveryMs: Long) {
    private var owner: WeakReference<Any>? = null
    private var acceptingCallbacks = false
    private var active = false
    private var lastObservedMs = 0L
    private var lastPersistedMs = 0L

    @Synchronized
    fun created(service: Any) {
        owner = WeakReference(service)
        acceptingCallbacks = true
        active = false
        lastObservedMs = 0L
    }

    @Synchronized
    fun started(service: Any, nowMs: Long, persist: (Long) -> Unit): Boolean {
        if (!owns(service)) return false
        if (active) {
            pulse(service, nowMs, persist)
            return true
        }
        active = true
        lastObservedMs = nowMs
        persist(nowMs)
        lastPersistedMs = nowMs
        return true
    }

    @Synchronized
    fun pulse(service: Any, nowMs: Long, persist: (Long) -> Unit) {
        if (!active || !owns(service)) return
        lastObservedMs = nowMs
        if (lastPersistedMs <= 0L || nowMs < lastPersistedMs ||
            nowMs - lastPersistedMs >= persistEveryMs
        ) {
            persist(nowMs)
            lastPersistedMs = nowMs
        }
    }

    /** A foreground refusal can be followed by another start or by onDestroy. */
    @Synchronized
    fun refused(service: Any): Boolean {
        if (!owns(service)) return false
        active = false
        lastObservedMs = 0L
        return true
    }

    /** A destroyed owner cannot start or schedule another stop without a new creation. */
    @Synchronized
    fun stopped(service: Any): Boolean {
        if (!owns(service)) return false
        active = false
        acceptingCallbacks = false
        lastObservedMs = 0L
        return true
    }

    @Synchronized
    fun owns(service: Any): Boolean = acceptingCallbacks && owner?.get() === service

    @Synchronized
    fun lastForWake(): Long = if (active && owner?.get() != null) lastObservedMs else 0L
}
