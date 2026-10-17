package com.vladimir.messenger.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** One short highlight per selection/click, never an idle animation loop. Apply after clipping. */
fun Modifier.apuSelectionSheen(trigger: Any, active: Boolean = true): Modifier = composed {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger, active) {
        if (active && ValueAnimator.areAnimatorsEnabled()) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(560))
        } else progress.snapTo(1f)
    }
    drawWithContent {
        drawContent()
        if (active && progress.value > 0f && progress.value < 1f) {
            val x = -size.width + progress.value * size.width * 3f
            drawRect(Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
                start = Offset(x - size.width * .4f, 0f),
                end = Offset(x, size.height),
            ))
        }
    }
}
