package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Large native Android emoji with a restrained glossy animated halo. */
@Composable
fun ShinyEmoji(
    emoji: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 26.sp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "emoji-gloss")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "emoji-pulse",
    )
    val shine by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "emoji-shine",
    )
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((fontSize.value * 1.18f).dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .blur(8.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Text(
            emoji,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                translationX = shine * 1.2f
            },
        )
    }
}
