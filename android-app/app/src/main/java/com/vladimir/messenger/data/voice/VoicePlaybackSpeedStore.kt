package com.vladimir.messenger.data.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Playback speed shared by all voice messages on this phone.
 *
 * The speed is a local playback preference: it is never included in a message,
 * never sent to the peer, and does not change the recorded audio file.
 */
object VoicePlaybackSpeedStore {
    val OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private const val PREFS = "voice_playback"
    private const val SPEED = "speed"

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    fun initialize(context: Context) {
        _speed.value = normalize(context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(SPEED, 1f))
    }

    fun current(context: Context): Float {
        initialize(context)
        return _speed.value
    }

    fun set(context: Context, value: Float) {
        val normalized = normalize(value)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(SPEED, normalized)
            .apply()
        _speed.value = normalized
    }

    fun normalize(value: Float): Float {
        if (!value.isFinite()) return 1f
        return OPTIONS.minByOrNull { abs(it - value) } ?: 1f
    }
}
