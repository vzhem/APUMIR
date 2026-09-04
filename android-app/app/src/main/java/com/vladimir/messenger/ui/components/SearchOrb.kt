package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Кнопка поиска — объёмный шарик с бликом.
 *
 * Плоская иконка терялась среди прочих значков шапки, а поиск здесь главное
 * действие. Объём делается тремя слоями: тень под шариком, радиальная заливка
 * со смещённым к левому верхнему углу светом и медленно ползущий блик. Всё
 * рисуется на `drawBehind`, без картинок: так значок не зависит от прошивки и
 * остаётся резким на любом экране.
 */
@Composable
fun SearchOrb(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String = "Поиск",
) {
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Нажатие проседает — шарик ощущается как настоящая кнопка.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(),
        label = "search-orb-press",
    )
    // Блик ползёт по кругу: медленно, чтобы не отвлекать от переписки.
    val shine = rememberInfiniteTransition(label = "search-orb-shine")
    val shinePhase by shine.animateFloat(
        initialValue = -0.35f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "search-orb-phase",
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .drawBehind {
                val radius = this.size.minDimension / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)

                // Тень: сдвинута вниз, поэтому шарик выглядит приподнятым.
                drawCircle(
                    color = Color.Black.copy(alpha = 0.18f),
                    radius = radius * 0.96f,
                    center = center.copy(y = center.y + radius * 0.10f),
                )
                // Тело: свет падает слева сверху, низ уходит в тень.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.96f),
                            primary.copy(alpha = 0.30f),
                            primary.copy(alpha = 0.62f),
                        ),
                        center = center.copy(
                            x = center.x - radius * 0.32f,
                            y = center.y - radius * 0.36f,
                        ),
                        radius = radius * 1.7f,
                    ),
                    radius = radius,
                    center = center,
                )
                // Ободок: без него шарик сливается с обоями APU.
                drawCircle(
                    color = primary.copy(alpha = 0.55f),
                    radius = radius,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()),
                )
                // Живой блик поверх стекла.
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f),
                    radius = radius * 0.22f,
                    center = Offset(
                        x = center.x + radius * shinePhase,
                        y = center.y - radius * 0.45f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = contentDescription,
            tint = primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
