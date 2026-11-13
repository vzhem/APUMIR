package com.vladimir.messenger.ui.screens.call

/**
 * One call preview owns only its own CameraX use cases. Provider callbacks may
 * arrive after navigation or after a newer preview started, so a stale owner
 * cannot bind and can never unbind the newer owner's use cases.
 */
internal class CallCameraLease<T : Any>(private val release: (T) -> Unit) {
    private var generation = 0L
    private var active: Pair<Long, T>? = null

    @Synchronized fun newOwner(): Long = ++generation

    @Synchronized fun isCurrent(owner: Long): Boolean = owner == generation

    @Synchronized fun install(owner: Long, binding: T, activate: () -> Unit): Boolean {
        if (owner != generation) return false
        active?.second?.let { active = null; release(it) }
        try {
            activate()
            // Be safe even if activation synchronously starts a newer owner.
            if (owner != generation) {
                release(binding)
                return false
            }
            active = owner to binding
        } catch (error: Exception) {
            // CameraX may have attached part of the requested group.
            release(binding)
            throw error
        }
        return true
    }

    @Synchronized fun dispose(owner: Long) {
        if (owner == generation) generation++
        if (active?.first == owner) {
            val binding = active?.second
            active = null
            if (binding != null) release(binding)
        }
    }
}
