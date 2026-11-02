package com.vladimir.messenger.data.voice

/**
 * Voice playback must be user-initiated.
 *
 * Android MediaPlayer.setPlaybackParams() can move a prepared/paused player
 * into playback on some devices. Therefore playback params are applied only
 * while the user has already started playback; a speed change while idle or
 * paused is remembered and applied on the next explicit play tap.
 */
object VoicePlaybackPolicy {
    /** Speed/pitch may be pushed to the platform player only during active playback. */
    fun shouldApplyPlaybackParams(playing: Boolean): Boolean = playing

    /** Appearance, preparation and speed changes are never playback triggers. */
    fun mayStartPlayback(trigger: Trigger): Boolean = trigger == Trigger.USER_TAP

    enum class Trigger {
        USER_TAP,
        APPEAR_ON_SCREEN,
        PREPARED,
        SPEED_CHANGE,
    }
}
