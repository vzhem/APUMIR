package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Объёмная круглая кнопка для главного действия экрана. Заменяет Material FAB,
 * сохраняя привычную 56dp цель касания и семантику доступности.
 */
@Composable
fun ApuPremiumFloatingActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "apu-fab-press",
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed && enabled) 4.dp else 12.dp,
        animationSpec = tween(durationMillis = 110),
        label = "apu-fab-elevation",
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .apuPremiumLift(elevation, CircleShape, ApuGold.copy(alpha = 0.55f))
            .clip(CircleShape)
            .background(apuGoldBrush())
            .border(1.dp, Color.White.copy(alpha = 0.72f), CircleShape)
            .apuPremiumGloss(CircleShape, intensity = 1f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = ApuGoldInk.copy(alpha = if (enabled) 1f else 0.48f),
            modifier = Modifier.size(size * 0.43f),
        )
    }
}

/** APU-переключатель: тёплое стекло, золотая дорожка и движущийся thumb. */
@Composable
fun ApuPremiumSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        animationSpec = tween(durationMillis = 180),
        label = "apu-switch-thumb",
    )
    Box(
        modifier = modifier
            .size(width = 56.dp, height = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.46f }
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val track = RoundedCornerShape(50)
        Box(
            modifier = Modifier
                .width(50.dp)
                .height(28.dp)
                .clip(track)
                .background(
                    if (checked) apuGoldBrush() else Brush.linearGradient(
                        listOf(Color(0xFFF4F5F7), Color(0xFFE4E8EE)),
                    ),
                )
                .border(
                    1.dp,
                    if (checked) ApuGoldDeep.copy(alpha = 0.72f)
                    else ApuBubbleAccentColor.copy(alpha = 0.30f),
                    track,
                )
                .apuPremiumGloss(track, intensity = if (checked) 0.8f else 0.42f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(22.dp)
                    .shadow(3.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color.White, Color(0xFFF2F3F5))))
                    .border(1.dp, ApuGoldDeep.copy(alpha = 0.36f), CircleShape)
                    .apuPremiumGloss(CircleShape, intensity = 0.72f),
            )
        }
    }
}

/** APU-флажок: золотая плитка с тёмной галочкой, серое стекло в покое. */
@Composable
fun ApuPremiumCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.46f }
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .shadow(if (checked) 5.dp else 1.dp, shape, clip = false)
                .clip(shape)
                .background(
                    if (checked) apuGoldBrush() else Brush.linearGradient(
                        listOf(Color.White, Color(0xFFF1F3F7)),
                    ),
                )
                .border(
                    1.2.dp,
                    if (checked) ApuGoldDeep.copy(alpha = 0.72f)
                    else ApuBubbleAccentColor.copy(alpha = 0.40f),
                    shape,
                )
                .apuPremiumGloss(shape, intensity = if (checked) 0.9f else 0.48f),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = ApuGoldInk,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** APU radio option with a gold outer ring and a clear selected core. */
@Composable
fun ApuPremiumRadioButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.46f }
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .shadow(if (selected) 4.dp else 1.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(if (selected) Color(0xFFFFF9E9) else Color.White)
                .border(2.dp, if (selected) ApuGoldDeep else ApuBubbleAccentColor.copy(alpha = 0.56f), CircleShape)
                .apuPremiumGloss(CircleShape, intensity = if (selected) 0.72f else 0.36f),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(apuGoldBrush()),
                )
            }
        }
    }
}

/** Familiar slider geometry with the shared APU gold palette. */
@Composable
fun ApuPremiumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = ApuGold,
            activeTrackColor = ApuGoldDeep,
            activeTickColor = ApuGoldInk,
            inactiveTrackColor = ApuGoldDeep.copy(alpha = 0.22f),
            inactiveTickColor = ApuGoldDeep.copy(alpha = 0.32f),
            disabledThumbColor = ApuGold.copy(alpha = 0.44f),
            disabledActiveTrackColor = ApuGoldDeep.copy(alpha = 0.34f),
            disabledInactiveTrackColor = ApuGoldDeep.copy(alpha = 0.12f),
        ),
    )
}
