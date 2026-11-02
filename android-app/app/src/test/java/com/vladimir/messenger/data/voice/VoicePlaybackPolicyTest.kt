package com.vladimir.messenger.data.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackPolicyTest {
    @Test
    fun onlyAnExplicitUserTapMayStartPlayback() {
        assertTrue(VoicePlaybackPolicy.mayStartPlayback(VoicePlaybackPolicy.Trigger.USER_TAP))
        assertFalse(VoicePlaybackPolicy.mayStartPlayback(VoicePlaybackPolicy.Trigger.APPEAR_ON_SCREEN))
        assertFalse(VoicePlaybackPolicy.mayStartPlayback(VoicePlaybackPolicy.Trigger.PREPARED))
        assertFalse(VoicePlaybackPolicy.mayStartPlayback(VoicePlaybackPolicy.Trigger.SPEED_CHANGE))
    }

    @Test
    fun playbackParamsAreAppliedOnlyWhileAlreadyPlaying() {
        assertTrue(VoicePlaybackPolicy.shouldApplyPlaybackParams(playing = true))
        assertFalse(VoicePlaybackPolicy.shouldApplyPlaybackParams(playing = false))
    }
}
