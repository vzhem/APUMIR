package com.vladimir.messenger.service

/**
 * Reduces callbacks for the application's default network to real changes.
 * The key is kept only in memory; it is never included in diagnostics.
 * Android 8+ delivers capabilities after onAvailable, so connectivity is
 * reported only once that network's capabilities are known.
 */
internal class DefaultNetworkState<K : Any> {
    enum class Action { IGNORE, NONE, AVAILABLE, LOST, POLICY }

    private var current: K? = null
    private var usable = false
    private var networkChanged = false
    private var routeMask: Int? = null
    private var metered: Boolean? = null

    @Synchronized
    fun available(network: K) {
        if (current != network) {
            current = network
            networkChanged = true
            routeMask = null
            metered = null
        }
    }

    @Synchronized
    fun capabilities(network: K, internet: Boolean, route: Int, isMetered: Boolean): Action {
        if (network != current) return Action.IGNORE
        // A tariff/data-saver change affects policy, not necessarily the route.
        // Treating it as a new path destroyed otherwise healthy QUIC sessions
        // and paused the broker for another direct-search window.
        val pathChanged = networkChanged || routeMask != route
        val action = when {
            internet && (!usable || pathChanged) -> Action.AVAILABLE
            !internet && usable -> Action.LOST
            internet && metered != isMetered -> Action.POLICY
            else -> Action.NONE
        }
        usable = internet
        networkChanged = false
        routeMask = route
        metered = isMetered
        return action
    }

    @Synchronized
    fun lost(network: K): Action {
        if (network != current) return Action.IGNORE
        current = null
        networkChanged = false
        routeMask = null
        metered = null
        return if (usable) {
            usable = false
            Action.LOST
        } else {
            Action.NONE
        }
    }
}
