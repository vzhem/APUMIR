package com.vladimir.messenger.data.swarm

/** Online transit only, not disk custody. All numbers remain hard safety ceilings, not capacity claims. */
data class OnlineHelperLimits(val enabled: Boolean, val routes: Int, val bytesPerMinute: Int, val relaysPerMinute: Int)

object OnlineHelperPolicy {
    fun limits(enabled: Boolean, mode: SwarmMode, metered: Boolean, lowPower: Boolean): OnlineHelperLimits {
        val base = when (mode) {
            SwarmMode.NORMAL -> OnlineHelperLimits(enabled, 8, 4 * 1024 * 1024, 120)
            SwarmMode.ECONOMY -> OnlineHelperLimits(enabled, 4, 2 * 1024 * 1024, 60)
            // Respect the existing explicit full-speed mode, still below native safety caps.
            SwarmMode.UNLIMITED -> return OnlineHelperLimits(enabled, 32, 4 * 1024 * 1024, 240)
        }
        val divisor = (if (metered) 2 else 1) * (if (lowPower) 2 else 1)
        return base.copy(routes = (base.routes / divisor).coerceAtLeast(1),
            bytesPerMinute = base.bytesPerMinute / divisor,
            relaysPerMinute = (base.relaysPerMinute / divisor).coerceAtLeast(15))
    }
}
