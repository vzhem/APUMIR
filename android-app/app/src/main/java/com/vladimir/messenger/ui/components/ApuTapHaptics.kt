package com.vladimir.messenger.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * Тактильный отклик для окон-диалогов: они живут в отдельном окне, и отклик
 * окна Activity до них не доходит. Модификатор только наблюдает за касаниями
 * (не потребляет их), поэтому кнопки внутри работают как обычно.
 */
fun Modifier.apuTapHaptics(): Modifier = composed {
    val view = LocalView.current
    val slop = LocalViewConfiguration.current.touchSlop
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start: Offset = down.position
            val startAt = down.uptimeMillis
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - start).getDistance() > slop) moved = true
                if (!change.pressed) {
                    if (!moved) {
                        val held = change.uptimeMillis - startAt
                        view.performHapticFeedback(
                            if (held >= 450L) HapticFeedbackConstants.LONG_PRESS
                            else HapticFeedbackConstants.CONTEXT_CLICK,
                        )
                    }
                    break
                }
            }
        }
    }
}
