package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// =============================================================================
// HINTBUBBLE.KT — белый пузырь для поясняющих надписей (раунд 48)
// =============================================================================
// Подсказки вида «Каналов пока нет...» печатались голым Text: цвет брался из
// темы, и на тёмной теме поверх обоев текст не читался вовсе. Пузырь делает
// надпись читаемой на любой подложке — и в дне, и в ночи, и на своих обоях.
//
// Формула стиля — та же, что зафиксирована в раунде 45 для белых пузырей
// нижней панели чата: скругление 14dp, белая подложка 0xFFF5F7FA c alpha 0.92,
// тонкая золотая рамка primary 0.35. Цвета намеренно фиксированные, а не из
// темы: подсказка обязана читаться при любых обоях.
// =============================================================================

/** Цвет текста внутри пузыря: фиксированный тёмный, не зависит от темы. */
val HintBubbleTextColor: Color = Color(0xFF1E2430)

/** Вторичный текст внутри пузыря (подсказки, пояснения). */
val HintBubbleMutedColor: Color = Color(0xFF5A6472)

@Composable
fun HintBubble(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Владелец 2026-10-07: «Теперь в таком стиле нужно переделать всё приложение».
    // Подсказка — та же премиальная поверхность, что шапки и диалоги: подъём,
    // единая подложка, золотая нить по кромке и блеск ПОД текстом.
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .apuPremiumLift(4.dp, shape)
            .apuBubbleSurface(color = Color(0xFFF5F7FA).copy(alpha = 0.92f), shape = shape)
            .apuPremiumThread(shape = shape, inset = 18.dp)
            .apuPremiumGloss(shape, intensity = 0.4f, topFraction = 0.6f)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
