package com.vladimir.messenger.service

import com.vladimir.messenger.service.DefaultNetworkState.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultNetworkStateTest {
    @Test
    fun `default network is announced once after its capabilities arrive`() {
        val state = DefaultNetworkState<String>()
        state.available("mobile")
        assertEquals(Action.AVAILABLE, state.capabilities("mobile", true, 4, true))
        assertEquals(Action.NONE, state.capabilities("mobile", true, 4, true))
        state.available("mobile")
        assertEquals(Action.NONE, state.capabilities("mobile", true, 4, true))
        assertEquals(Action.LOST, state.lost("mobile"))
        assertEquals(Action.IGNORE, state.lost("mobile"))
    }

    @Test
    fun `a delayed loss or capabilities from the old network cannot reset the new one`() {
        val state = DefaultNetworkState<String>()
        state.available("mobile")
        assertEquals(Action.AVAILABLE, state.capabilities("mobile", true, 4, true))
        state.available("vpn")
        assertEquals(Action.IGNORE, state.lost("mobile"))
        assertEquals(Action.IGNORE, state.capabilities("mobile", true, 4, true))
        assertEquals(Action.AVAILABLE, state.capabilities("vpn", true, 5, true))
        assertEquals(Action.IGNORE, state.lost("mobile"))
        assertEquals(Action.NONE, state.capabilities("vpn", true, 5, true))
        assertEquals(Action.LOST, state.lost("vpn"))
    }

    @Test
    fun `internet loss and recovery on the default network are propagated`() {
        val state = DefaultNetworkState<String>()
        state.available("wifi")
        assertEquals(Action.NONE, state.capabilities("wifi", false, 2, false))
        assertEquals(Action.AVAILABLE, state.capabilities("wifi", true, 2, false))
        assertEquals(Action.LOST, state.capabilities("wifi", false, 2, false))
        assertEquals(Action.NONE, state.capabilities("wifi", false, 2, false))
        assertEquals(Action.AVAILABLE, state.capabilities("wifi", true, 2, false))
    }

    @Test
    fun `underlying vpn route or metering changes refresh once`() {
        val state = DefaultNetworkState<String>()
        state.available("vpn")
        assertEquals(Action.AVAILABLE, state.capabilities("vpn", true, 1 or 4, true))
        assertEquals(Action.NONE, state.capabilities("vpn", true, 1 or 4, true))
        assertEquals(Action.AVAILABLE, state.capabilities("vpn", true, 1 or 2, false))
        assertEquals(Action.NONE, state.capabilities("vpn", true, 1 or 2, false))
        assertEquals(Action.AVAILABLE, state.capabilities("vpn", true, 1 or 2, true))
        assertEquals(Action.NONE, state.capabilities("vpn", true, 1 or 2, true))
    }

    @Test
    fun `a replacement without internet marks the previously usable path lost`() {
        val state = DefaultNetworkState<String>()
        state.available("wifi")
        assertEquals(Action.AVAILABLE, state.capabilities("wifi", true, 2, false))
        state.available("vpn")
        assertEquals(Action.IGNORE, state.lost("wifi"))
        assertEquals(Action.LOST, state.capabilities("vpn", false, 1, true))
        assertEquals(Action.NONE, state.lost("vpn"))
    }
}
