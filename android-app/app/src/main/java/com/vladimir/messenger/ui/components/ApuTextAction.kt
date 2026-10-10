package com.vladimir.messenger.ui.components

// =============================================================================
// APUTEXTACTION.KT — фирменная кнопка-действие вместо стокового TextButton
// =============================================================================
// Владелец 2026-10-07: «Теперь в таком стиле нужно переделать всё приложение,
// все разделы — чтобы был стиль как в логах».
//
// До этого в разделы были переведены поверхности (шапки, карточки, строки,
// диалоги), а кнопки внутри карточек и диалогов оставались стоковым Material
// `TextButton`: плоский текст с серо-сиреневой «рябью» при нажатии. На светлой
// подложке APU он выглядел чужеродно.
//
// Здесь тот же приём, что у кнопок окна «Логи» (ApuDiagnosticsActionButton):
// мягкая тень, светлая градиентная заливка, золотая кромка, блеск по верхней
// кромке и сжатие при нажатии. Опасные действия («Покинуть», «Удалить») — тот
// же объём, но в красной гамме: их видно сразу, и они не «кричат» яркой
// заливкой на весь экран.
// =============================================================================

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Краска опасного действия: тот же тон, что у ошибок в отчёте. */
private val ApuActionDangerInk = Color(0xFFA12D3A)

/**
 * Кнопка-действие в стиле APU: нейтральная (стекло с золотой кромкой) или
 * `danger` (красная). Размер и отступы подобраны под строки карточек, чтобы
 * кнопка стояла в них как «своя», а не как вставленный чужой элемент.
 */
@Composable
fun ApuTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    // Внутри окна (кнопки подтверждения/отмены) — та же объёмная кнопка APU,
    // что и везде: подтверждение золотое, опасное красное, отмена тихая.
    val role = LocalApuDialogButtonRole.current
    if (role != null) {
        ApuPremiumButton(
            label = label,
            onClick = onClick,
            modifier = modifier,
            icon = icon,
            style = when {
                danger -> DiagnosticsActionStyle.DANGER
                role == DialogButtonRole.CONFIRM -> DiagnosticsActionStyle.PRIMARY
                else -> DiagnosticsActionStyle.QUIET
            },
            enabled = enabled,
        )
    } else {
        ApuTextActionFlat(label, onClick, modifier, enabled, danger, icon)
    }
}

/** Роль кнопки внутри окна: подтверждение или отмена. Задаёт само окно. */
enum class DialogButtonRole { CONFIRM, DISMISS }

val LocalApuDialogButtonRole = compositionLocalOf<DialogButtonRole?> { null }

@Composable
private fun ApuTextActionFlat(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    danger: Boolean,
    icon: ImageVector?,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "apu-text-action-press",
    )
    val lift by animateDpAsState(
        targetValue = if (pressed && enabled) 1.dp else 5.dp,
        animationSpec = tween(durationMillis = 110),
        label = "apu-text-action-lift",
    )
    val shape = RoundedCornerShape(12.dp)
    val fill = if (danger) {
        Brush.linearGradient(listOf(Color(0xFFFDF1F2), Color(0xFFF8E4E7)))
    } else {
        Brush.linearGradient(listOf(Color.White, Color(0xFFF1F3F7)))
    }
    val ink = if (danger) ApuActionDangerInk else ApuBubbleAccentColor
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .apuPremiumLift(lift, shape, if (danger) ApuActionDangerInk.copy(alpha = 0.35f) else ApuPremiumShadowColor)
            .clip(shape)
            .background(fill)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        if (danger) ApuActionDangerInk.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.85f),
                        ApuGoldDeep.copy(alpha = 0.35f),
                    ),
                ),
                shape = shape,
            )
            .apuPremiumGloss(shape, intensity = 0.7f, topFraction = 0.6f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ink.copy(alpha = if (enabled) 1f else 0.45f),
            maxLines = 1,
        )
    }
}
