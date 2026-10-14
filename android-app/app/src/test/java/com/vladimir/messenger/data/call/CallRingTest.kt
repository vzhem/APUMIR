package com.vladimir.messenger.data.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRingTest {
    @Test
    fun ringbackOnOutgoingWait() {
        assertTrue(CallRing.ringbackWhile(CallStateMachine.Phase.OFFERING))
        assertTrue(CallRing.ringbackWhile(CallStateMachine.Phase.RINGING))
        assertFalse(CallRing.ringbackWhile(CallStateMachine.Phase.INCOMING))
        assertFalse(CallRing.ringbackWhile(CallStateMachine.Phase.ACTIVE))
        assertFalse(CallRing.ringbackWhile(CallStateMachine.Phase.CONNECTING))
    }

    @Test
    fun ringtoneOnlyIncoming() {
        assertTrue(CallRing.ringtoneWhile(CallStateMachine.Phase.INCOMING))
        assertFalse(CallRing.ringtoneWhile(CallStateMachine.Phase.RINGING))
        assertFalse(CallRing.ringtoneWhile(CallStateMachine.Phase.ACTIVE))
    }

    @Test
    fun speakerButtonAlreadyOnDial() {
        assertTrue(CallRing.speakerButtonWhile(CallStateMachine.Phase.OFFERING))
        assertTrue(CallRing.speakerButtonWhile(CallStateMachine.Phase.RINGING))
        assertTrue(CallRing.speakerButtonWhile(CallStateMachine.Phase.CONNECTING))
        assertTrue(CallRing.speakerButtonWhile(CallStateMachine.Phase.ACTIVE))
        assertFalse(CallRing.speakerButtonWhile(CallStateMachine.Phase.INCOMING))
        assertFalse(CallRing.speakerButtonWhile(CallStateMachine.Phase.ENDED))
    }
}
