package com.vladimir.messenger.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val SWIPE_TO_REPLY_THRESHOLD = 72.dp

/**
 * A deliberate horizontal swipe in either direction replies to this message.
 * Consuming the horizontal drag here also prevents the screen-level swipe-back
 * gesture from stealing rightward swipes that begin on a message.
 */
fun Modifier.swipeToReply(
    enabled: Boolean = true,
    onReply: () -> Unit,
): Modifier = composed {
    if (!enabled) {
        this
    } else {
        val thresholdPx = with(LocalDensity.current) { SWIPE_TO_REPLY_THRESHOLD.toPx() }
        val latestOnReply by rememberUpdatedState(onReply)
        pointerInput(thresholdPx) {
            var travelled = 0f
            var replied = false
            detectHorizontalDragGestures(
                onDragStart = {
                    travelled = 0f
                    replied = false
                },
                onDragEnd = {
                    travelled = 0f
                    replied = false
                },
                onDragCancel = {
                    travelled = 0f
                    replied = false
                },
            ) { change, dragAmount ->
                travelled += dragAmount
                // Consume both signs so a right swipe cannot also navigate back.
                change.consume()
                if (!replied && abs(travelled) >= thresholdPx) {
                    replied = true
                    latestOnReply()
                }
            }
        }
    }
}
