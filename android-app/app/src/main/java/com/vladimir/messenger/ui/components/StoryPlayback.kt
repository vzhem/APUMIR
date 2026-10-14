package com.vladimir.messenger.ui.components

import kotlin.math.abs

/** Pure playback rules shared by the viewer and unit tests. */
internal object StoryPlayback {
    fun durationMs(textLength: Int): Long = (5_000L + textLength.coerceAtLeast(0) * 25L).coerceAtMost(10_000L)

    fun advance(progress: Float, frameMs: Long, durationMs: Long): Float =
        (progress + frameMs.coerceIn(0L, 100L).toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

    /**
     * Диалог «удалить?» должен останавливать ленту. Иначе за время подтверждения
     * долистывается следующая история, и корзина снимает уже её.
     */
    fun frozen(held: Boolean, userPaused: Boolean, confirmDelete: Boolean, resumed: Boolean): Boolean =
        held || userPaused || confirmDelete || !resumed

    enum class Gesture { NONE, PREVIOUS, NEXT, DISMISS }

    fun gesture(dx: Float, dy: Float, dragged: Boolean, cancelled: Boolean, elapsedMs: Long,
                longPressMs: Long, tapX: Float, width: Int, density: Float): Gesture = when {
        cancelled -> Gesture.NONE
        dragged && dy > 80f * density && dy > abs(dx) -> Gesture.DISMISS
        dragged && abs(dx) > 64f * density && abs(dx) > abs(dy) ->
            if (dx < 0) Gesture.NEXT else Gesture.PREVIOUS
        !dragged && elapsedMs < longPressMs -> if (tapX < width * .33f) Gesture.PREVIOUS else Gesture.NEXT
        else -> Gesture.NONE
    }
}
