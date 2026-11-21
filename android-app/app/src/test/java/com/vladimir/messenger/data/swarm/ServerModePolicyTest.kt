package com.vladimir.messenger.data.swarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerModePolicyTest {
    @Test
    fun serverRoleIsOptInByDefault() {
        assertFalse(ServerModePolicy.DEFAULT_ENABLED)
        assertFalse(ServerModePolicy.enabled(null))
    }

    @Test
    fun explicitServerChoiceSurvivesDefaultChange() {
        assertTrue(ServerModePolicy.enabled(true))
        assertFalse(ServerModePolicy.enabled(false))
    }
}
