package com.vladimir.messenger.ui.components

// =============================================================================
// APUBUBBLE.KT — светлый пузырь для содержимого экранов поверх обоев
// =============================================================================
// Тот же рецепт, что у HintBubble и карточек списков (раунд 42/48): скругление
// 18dp, подложка 0xFFF5F7FA с alpha 0.92, тонкая золотая рамка primary 0.35.
// Отличие от HintBubble: содержимое выравнивается по левому краю и пузырь
// растягивается на всю ширину — он нужен для блоков настроек, а не для
// короткой подсказки по центру.
//
// Зачем: экраны групп рисуются поверх ChatWallpaper. Голый Text брал цвет из
// темы и на тёмных обоях пропадал. Внутри пузыря цвета фиксированные, поэтому
// текст читается при любой подложке и в любой теме.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Цвет обычного текста в пузыре настроек. */
val ApuBubbleTextColor: Color = HintBubbleTextColor

/** Цвет пояснений и подписей в пузыре настроек. */
val ApuBubbleMutedColor: Color = HintBubbleMutedColor

/**
 * Светлый пузырь во всю ширину: блок настроек, карточка участника, ссылка.
 *
 * Внутри подменяется LocalContentColor, поэтому вложенные Text и Icon без
 * явного цвета сразу читаемы на светлой подложке.
 */
@Composable
fun ApuBubble(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = verticalArrangement,
    ) {
        CompositionLocalProvider(LocalContentColor provides ApuBubbleTextColor) {
            content()
        }
    }
}
