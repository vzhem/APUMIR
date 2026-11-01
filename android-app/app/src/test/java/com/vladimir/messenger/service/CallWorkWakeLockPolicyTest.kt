package com.vladimir.messenger.service

import com.vladimir.messenger.data.call.CallStateMachine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CPU-замок звонка живёт только пока звонок действительно идёт. */
class CallWorkWakeLockPolicyTest {
    @Test
    fun `idle and ended do not hold the cpu`() {
        assertFalse(CallWorkWakeLock.isActive(CallStateMachine.Phase.IDLE))
        assertFalse(CallWorkWakeLock.isActive(CallStateMachine.Phase.ENDED))
    }

    @Test
    fun `ringing connecting and active hold the cpu`() {
        assertTrue(CallWorkWakeLock.isActive(CallStateMachine.Phase.OFFERING))
        assertTrue(CallWorkWakeLock.isActive(CallStateMachine.Phase.RINGING))
        assertTrue(CallWorkWakeLock.isActive(CallStateMachine.Phase.INCOMING))
        assertTrue(CallWorkWakeLock.isActive(CallStateMachine.Phase.CONNECTING))
        assertTrue(CallWorkWakeLock.isActive(CallStateMachine.Phase.ACTIVE))
    }
}
