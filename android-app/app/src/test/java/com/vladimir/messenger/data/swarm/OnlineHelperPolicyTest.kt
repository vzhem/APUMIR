package com.vladimir.messenger.data.swarm

import org.junit.Assert.*
import org.junit.Test

class OnlineHelperPolicyTest {
    @Test fun allModesStayInsideNativeCapsAndPreserveOptOut() {
        for (mode in SwarmMode.entries) for (metered in listOf(false, true)) for (low in listOf(false, true)) {
            val p = OnlineHelperPolicy.limits(true, mode, metered, low)
            assertTrue(p.enabled)
            assertTrue(p.routes in 1..32)
            assertTrue(p.bytesPerMinute in 1..4 * 1024 * 1024)
            assertTrue(p.relaysPerMinute in 15..240)
            assertFalse(OnlineHelperPolicy.limits(false, mode, metered, low).enabled)
        }
    }
    @Test fun economyAndEnvironmentReduceOnlyBackgroundHelp() {
        val normal = OnlineHelperPolicy.limits(true, SwarmMode.NORMAL, false, false)
        val economy = OnlineHelperPolicy.limits(true, SwarmMode.ECONOMY, false, false)
        assertEquals(normal.routes / 2, economy.routes)
        assertEquals(normal.bytesPerMinute / 2, economy.bytesPerMinute)
        assertEquals(normal.relaysPerMinute / 2, economy.relaysPerMinute)
        val limited = OnlineHelperPolicy.limits(true, SwarmMode.NORMAL, true, true)
        assertEquals(normal.routes / 4, limited.routes)
        assertEquals(normal.bytesPerMinute / 4, limited.bytesPerMinute)
        assertEquals(normal.relaysPerMinute / 4, limited.relaysPerMinute)
    }
    @Test fun explicitFullSpeedModeStillHasCapsButNoEnvironmentDiscount() {
        assertEquals(OnlineHelperPolicy.limits(true, SwarmMode.UNLIMITED, false, false),
            OnlineHelperPolicy.limits(true, SwarmMode.UNLIMITED, true, true))
    }
}
