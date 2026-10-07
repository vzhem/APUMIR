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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Цвет обычного текста в пузыре настроек. */
val ApuBubbleTextColor: Color = HintBubbleTextColor

/** Цвет пояснений и подписей в пузыре настроек. */
val ApuBubbleMutedColor: Color = HintBubbleMutedColor

val ApuBubbleSurfaceColor: Color = Color(0xFFF5F7FA).copy(alpha = 0.92f)
val ApuBubbleShape = RoundedCornerShape(18.dp)

/** Читаемые на светлом пузыре акценты и ссылки, в том числе в ночной теме. */
val ApuBubbleAccentColor: Color = Color(0xFF7A5A10)
val ApuBubbleLinkColor: Color = Color(0xFF004782)

/** Единая подложка шапок, сообщений, вложений и карточек. */
@Composable
fun Modifier.apuBubbleSurface(
    color: Color = ApuBubbleSurfaceColor,
    shape: Shape = ApuBubbleShape,
): Modifier = clip(shape)
    .background(color)
    // Тонкий диагональный блик делает поверхности объёмнее, но остаётся
    // почти незаметным и не превращает интерфейс в «пластик».
    .drawWithCache {
        val gloss = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.16f),
                Color.Transparent,
                Color.White.copy(alpha = 0.04f),
            ),
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
        )
        onDrawWithContent {
            drawContent()
            drawRect(gloss)
        }
    }
    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), shape)

/** Компактный пузырь шапки. null onClick не добавляет ложного действия. */
@Composable
fun ApuHeaderBubble(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // Шапка — тот же премиальный стиль, что у окна «Логи»: поверхность чуть
    // приподнята (мягкая тень), по верхней кромке идёт золотая нить, а глянец
    // ложится ПОД содержимым, поэтому название раздела остаётся чётким.
    // Через этот общий компонент новый вид получают шапки ВСЕХ разделов:
    // чатов, групп, каналов, настроек и остальных экранов.
    Box(
        modifier = modifier
            .apuPremiumLift(8.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 12.dp)
            .apuPremiumGloss(intensity = 0.5f, topFraction = 0.6f)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides ApuBubbleTextColor) { content() }
    }
}

/**
 * Card без стандартной серо-сиреневой заливки и тени Material. Не меняет
 * размеры и внутренние отступы существующих карточек ленты/закрепов.
 * Для парящих стикеров transparent отключает и фон, и рамку.
 *
 * `premium` — высота подъёма для самостоятельных карточек (панель выбора,
 * закрепы, карточка темы, блок настроек). Тогда поверхность рисуется тем же
 * слоем, что у шапок и диалогов: подъём, единая подложка, золотая нить по
 * верхней кромке и блеск ПОД содержимым. Для строк ленты (облачко сообщения)
 * оставляем `null`: тень на каждой строке ленты превращается в шум.
 *
 * Важно: премиальная ветка рисует поверхность сама (Box + `apuBubbleSurface`),
 * а не через `Card`. Переданные в `modifier` слои легли бы ПОД фоном Card —
 * нить и блеск просто не были бы видны.
 */
@Composable
fun ApuBubbleCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = ApuBubbleSurfaceColor,
    contentColor: Color = ApuBubbleTextColor,
    shape: Shape = ApuBubbleShape,
    transparent: Boolean = false,
    premium: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (premium != null && !transparent) {
        Box(
            modifier = modifier
                .apuPremiumLift(premium, shape)
                .apuBubbleSurface(color = backgroundColor, shape = shape)
                .apuPremiumThread(shape = shape)
                .apuPremiumGloss(shape, intensity = 0.5f, topFraction = 0.55f),
        ) {
            Column {
                val scope = this
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    scope.content()
                }
            }
        }
        return
    }
    Card(
        modifier = modifier,
        shape = if (transparent) RectangleShape else shape,
        border = if (transparent) null else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        colors = CardDefaults.cardColors(
            containerColor = if (transparent) Color.Transparent else backgroundColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}

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
    /** Раунд 169: стикеры в «Избранном» - без рамки и подложки. */
    transparent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (transparent) Modifier else Modifier.apuBubbleSurface())
            .padding(
                if (transparent) {
                    PaddingValues(0.dp)
                } else {
                    PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                }
            ),
        verticalArrangement = verticalArrangement,
    ) {
        CompositionLocalProvider(LocalContentColor provides ApuBubbleTextColor) {
            content()
        }
    }
}
