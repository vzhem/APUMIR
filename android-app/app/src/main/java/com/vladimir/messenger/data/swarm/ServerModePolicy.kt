package com.vladimir.messenger.data.swarm

/** Pure defaults for the optional, higher-cost server role. */
object ServerModePolicy {
    /** A phone acts as a server only after its owner explicitly enables it. */
    const val DEFAULT_ENABLED = false

    /** Missing preference uses the opt-in default; an explicit choice survives upgrades. */
    fun enabled(storedValue: Boolean?): Boolean = storedValue ?: DEFAULT_ENABLED
}
