package com.vladimir.messenger.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackSpeedStoreTest {
    @Test
    fun supportsBothSlowerAndFasterPlayback() {
        assertEquals(0.5f, VoicePlaybackSpeedStore.normalize(0.5f))
        assertEquals(0.75f, VoicePlaybackSpeedStore.normalize(0.75f))
        assertEquals(1.25f, VoicePlaybackSpeedStore.normalize(1.25f))
        assertEquals(2f, VoicePlaybackSpeedStore.normalize(2f))
    }

    @Test
    fun arbitraryValuesSnapToTheNearestSupportedSpeed() {
        assertEquals(0.5f, VoicePlaybackSpeedStore.normalize(0.42f))
        assertEquals(1f, VoicePlaybackSpeedStore.normalize(1.1f))
        assertEquals(1.5f, VoicePlaybackSpeedStore.normalize(1.6f))
        assertEquals(1f, VoicePlaybackSpeedStore.normalize(Float.NaN))
    }
}
