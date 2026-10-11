package com.vladimir.messenger.ui.components

// =============================================================================
// APUPREMIUMPARTS.KT — недостающие премиальные детали
// =============================================================================
// Спиннер, чип, полоска прогресса и одноразовый «переллив» при нажатии.
// Всё вместе заменяет стандартные Material-элементы на всех экранах.
//
// Правило батареи: единственная бесконечная анимация здесь — спиннер загрузки.
// Он крутится ТОЛЬКО пока виден (загрузка). Переливы в кнопках одноразовые:
// на нажатие, около 0,6 с, после чего кадры не тратятся.
// =============================================================================

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Спиннер загрузки в золоте: тёмная дорожка и светлая дуга, которая бежит
 * по кругу. Размер задаёт вызывающий через modifier (по умолчанию 40 dp).
 */
@Composable
fun ApuPremiumSpinner(
    modifier: Modifier = Modifier.size(40.dp),
    color: Color = ApuGold,
    strokeWidth: Dp = 3.dp,
) {
    val transition = rememberInfiniteTransition(label = "apu-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "apu-spinner-angle",
    )
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = angle - 90f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * Полоска прогресса: золотое заполнение с глянцем на тёмной дорожке.
 * Высоту задаёт [thickness]; modifier не должен задавать высоту.
 */
@Composable
fun ApuPremiumLinearProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    thickness: Dp = 6.dp,
) {
    val shape = RoundedCornerShape(thickness / 2)
    val value = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(shape)
            .background(ApuGoldDeep.copy(alpha = 0.18f)),
    ) {
        if (value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(apuGoldBrush())
                    .apuPremiumGloss(shape, intensity = 0.9f, topFraction = 1f),
            )
        }
    }
}

/**
 * Одноразовый переллив: светлая полоса проходит по поверхности один раз,
 * когда [progress] идёт от 0 до 1. Вне этого интервала ничего не рисуется.
 */
fun Modifier.apuShineSweep(progress: Float): Modifier = drawWithContent {
    drawContent()
    if (progress > 0f && progress < 1f) {
        val w = size.width
        val x = -w * 0.5f + w * 2f * progress
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.42f),
                    Color.Transparent,
                ),
                start = Offset(x, 0f),
                end = Offset(x + w * 0.45f, size.height),
            ),
        )
    }
}

/**
 * Премиальный чип: выбранный — золотая плитка с глянцем, остальные — белое
 * стекло с золотой кромкой. Нажатие сжимает чип и отпускает с подъёмом.
 * Заменяет стандартный FilterChip на всех экранах.
 */
@Composable
fun ApuPremiumChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(50)
    val ink = if (selected) ApuGoldInk else ApuBubbleAccentColor
    val fill: Brush = if (selected) apuGoldBrush() else Brush.linearGradient(
        colors = listOf(Color.White, Color.White.copy(alpha = 0.78f)),
    )
    val pressScale = if (pressed && enabled) 0.95f else 1f
    Row(
        modifier = modifier
            .scale(pressScale)
            .apuPremiumLift(if (selected) 6.dp else 3.dp, shape, ApuGold.copy(alpha = 0.35f))
            .clip(shape)
            .background(fill)
            .apuPremiumGloss(shape, intensity = if (selected) 0.9f else 0.5f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ink.copy(alpha = if (enabled) 1f else 0.48f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
