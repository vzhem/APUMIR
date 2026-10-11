package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * «Стеклянная» полоса для шапок поверх обоев: плотнее у верхнего края и мягко
 * растворяется вниз, а тонкая светлая линия отделяет её от ленты. Настоящее
 * размытие фона тут не нужно — полупрозрачный градиент даёт тот же эффект
 * чтения и без лишней нагрузки на кадр.
 */
@Composable
fun apuGlassBarModifier(): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val line = Color.White.copy(alpha = 0.55f)
    return Modifier
        .fillMaxWidth()
        .background(
            Brush.verticalGradient(
                colors = listOf(surface.copy(alpha = 0.92f), surface.copy(alpha = 0.6f)),
            ),
        )
        .drawBehind {
            drawLine(
                color = line,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
}
