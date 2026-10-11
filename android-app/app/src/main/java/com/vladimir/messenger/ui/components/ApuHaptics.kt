package com.vladimir.messenger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Лёгкая тактильная отдача на нажатие — как в премиальных мессенджерах.
 * Возвращает функцию, которую можно вызвать из обработчика нажатия.
 */
@Composable
fun rememberApuTap(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}
