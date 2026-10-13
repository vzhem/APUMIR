package com.vladimir.messenger.ui.components

/**
 * Чистая логика жестов камеры истории: масштаб и вспышка.
 * Сам CameraX остаётся в [StoryCameraDialog] — так тесты не зависят от Android.
 */
internal object StoryCameraControls {
    enum class Flash { OFF, ON, AUTO }

    /** Новый масштаб после щипка. Пустой/битый жест не двигает текущее значение. */
    fun zoomAfterPinch(current: Float, pinch: Float, min: Float, max: Float): Float {
        if (!current.isFinite()) return 1f
        val lo = if (min.isFinite()) min else 1f
        val hi = if (max.isFinite() && max >= lo) max else lo
        val factor = if (pinch.isFinite() && pinch > 0f) pinch else 1f
        val next = current * factor
        return if (next.isFinite()) next.coerceIn(lo, hi) else current.coerceIn(lo, hi)
    }

    fun cycleFlash(current: Flash, hasFlash: Boolean): Flash {
        if (!hasFlash) return Flash.OFF
        return when (current) {
            Flash.OFF -> Flash.ON
            Flash.ON -> Flash.AUTO
            Flash.AUTO -> Flash.OFF
        }
    }

    fun zoomLabel(ratio: Float): String {
        if (!ratio.isFinite() || ratio <= 0f) return "1×"
        val tenths = kotlin.math.round(ratio * 10f) / 10f
        return if (tenths % 1f == 0f) "${tenths.toInt()}×" else "${tenths}×"
    }

    /**
     * Нижний отступ затвора: если система не отдала inset (часто в Dialog),
     * берём запас, иначе кнопка проваливается в панель навигации.
     */
    fun shutterBottomPaddingPx(navigationBarPx: Float, fallbackPx: Float): Float =
        maxOf(if (navigationBarPx.isFinite()) navigationBarPx else 0f, fallbackPx.coerceAtLeast(0f))
}
