package com.vladimir.messenger.service

/**
 * Identifies the service instance that owns process-wide startup observations.
 * Callbacks run while the short gate is held: they must not wait for native,
 * disk or network work. An old service may finish a blocking call after it was
 * destroyed; it must not overwrite the next instance's status.
 */
internal class CoreServiceGate {
    private var current: Any? = null

    @Synchronized fun activate(owner: Any, publish: () -> Unit) {
        current = owner
        publish()
    }

    @Synchronized fun <T> ifCurrent(owner: Any, publish: () -> T): T? =
        if (current === owner) publish() else null

    @Synchronized fun isCurrent(owner: Any): Boolean = current === owner

    @Synchronized fun retire(owner: Any, publish: () -> Unit): Boolean {
        if (current !== owner) return false
        current = null
        publish()
        return true
    }
}
