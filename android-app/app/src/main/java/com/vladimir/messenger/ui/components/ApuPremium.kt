package com.vladimir.messenger.ui.components

// =============================================================================
// APUPREMIUM.KT — единый премиальный слой стиля APU
// =============================================================================
// Владелец 2026-10-07: «Теперь в таком стиле нужно переделать всё приложение,
// все разделы — чтобы был стиль как в логах. Начни с вкладки настройки.»
//
// Здесь живёт ОДНА палитра и ОДНИ эффекты для всего приложения — те же, что в
// окне «Логи» (ui/components/ApuDiagnosticsUi.kt). Держать их в одном месте
// важно: иначе «премиальный» вид разъедется по экранам на оттенки, и владелец
// снова увидит «по-пенсионерски».
//
// Правила, которые соблюдаются везде, где это применяется:
//   * ничего stock Material: заливки, рамки, блики и тени — свои;
//   * яркость живёт в ЗАЛИВКАХ (золото, полосы, градиенты), а текст остаётся
//     тёмными чернилами [ApuGoldInk] — читаемость не приносится в жертву
//     (контраст проверяет scripts/ci/check-settings-style.py);
//   * никаких вечно бегущих анимаций в списках: в строках настроек стоит
//     фиксированный глянец (правило PROFILE_SETTINGS_STYLE.md — без непрерывного
//     shimmer; телефон не тратит батарею на перерисовку списка).
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Палитра (те же значения, что в окне «Логи»)
// ─────────────────────────────────────────────────────────────────────────────

/** Основное золото APU: заливки, кромки, акценты. */
val ApuGold: Color = Color(0xFFF2B836)

/** Светлое золото: верх градиента, блики, «металл». */
val ApuGoldLight: Color = Color(0xFFFFE9A8)

/** Тёмное золото: низ градиента, тени золота. */
val ApuGoldDeep: Color = Color(0xFFB87E08)

/**
 * Чернила для текста и значков НА золоте. Тёмно-коричневый, а не чёрный:
 * на всех трёх стопах золота даёт контраст 5.0–14.6 (см. контрактный тест).
 */
val ApuGoldInk: Color = Color(0xFF241703)

/** Ночная пара окон-«баннеров»: шапки разделов в премиальном стиле. */
val ApuPremiumNightTop: Color = Color(0xFF16233A)
val ApuPremiumNightBottom: Color = Color(0xFF0A1018)

/** Мягкая тень под «премиальными» элементами. */
val ApuPremiumShadowColor: Color = Color(0xFF0A1018).copy(alpha = 0.35f)

/** Ровная золотая кисть: одна и та же на всех размерах — стиль не «плывёт». */
fun apuGoldBrush(): Brush = Brush.linearGradient(
    colors = listOf(ApuGoldLight, ApuGold, ApuGoldDeep),
    start = Offset(0f, 0f),
    end = Offset(140f, 140f),
)

/** Ночная кисть для тёмных «баннерных» поверхностей. */
fun apuNightBrush(): Brush = Brush.linearGradient(
    colors = listOf(ApuPremiumNightTop, Color(0xFF24344E), ApuPremiumNightBottom),
    start = Offset(0f, 0f),
    end = Offset(140f, 200f),
)

// ─────────────────────────────────────────────────────────────────────────────
// Эффекты: объём и блеск
// ─────────────────────────────────────────────────────────────────────────────

/** Тень: элемент «стоит» над подложкой, а не нарисован краской. */
fun Modifier.apuPremiumLift(
    elevation: Dp,
    shape: Shape = ApuBubbleShape,
    color: Color = ApuPremiumShadowColor,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = color,
    spotColor = color,
)

/**
 * Глянец: светлое свечение у верхней кромки плюс узкая световая полоса.
 * Именно это читается как «полированный пластик», и стоит ноль перерисовок —
 * один раз считается в drawWithCache.
 *
 * Блик рисуется ПОД содержимым (сначала блик, потом drawContent): значок и
 * текст остаются чёткими, а свет ложится на саму поверхность — так выглядит
 * настоящее стекло, а не запылённая картинка.
 */
fun Modifier.apuPremiumGloss(
    shape: Shape = ApuBubbleShape,
    intensity: Float = 1f,
    /** Какая доля высоты получает верхнее свечение (текст под ним не глушим). */
    topFraction: Float = 0.55f,
): Modifier = clip(shape).drawWithCache {
    val topSheen = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.30f * intensity),
            Color.White.copy(alpha = 0.08f * intensity),
            Color.Transparent,
        ),
        start = Offset(0f, 0f),
        end = Offset(0f, size.height * topFraction),
    )
    val lightBand = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.38f * intensity),
            Color.White.copy(alpha = 0.05f * intensity),
            Color.Transparent,
        ),
        start = Offset(0f, 0f),
        end = Offset(size.width * 0.85f, 0f),
    )
    onDrawWithContent {
        drawRect(topSheen)
        drawRect(
            brush = lightBand,
            topLeft = Offset(size.width * 0.05f, size.height * 0.06f),
            size = Size(size.width * 0.90f, size.height * 0.08f),
        )
        drawContent()
    }
}

/**
 * Золотая нить по верхней кромке карточки: тонкая деталь, по которой раздел
 * узнаётся с одного взгляда. Нить идёт ПОД содержимым и растворяется к краям.
 */
fun Modifier.apuPremiumThread(
    shape: Shape = ApuBubbleShape,
    inset: Dp = 14.dp,
): Modifier = clip(shape).drawWithCache {
    val y = 1.5.dp.toPx()
    val start = inset.toPx()
    val end = size.width - inset.toPx()
    onDrawWithContent {
        if (end > start) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        ApuGold.copy(alpha = 0.75f),
                        ApuGoldLight.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                    startX = start,
                    endX = end,
                ),
                start = Offset(start, y),
                end = Offset(end, y),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        drawContent()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Готовые детали
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Золотая плитка со значком — общий вид иконок в строках настроек, быстрых
 * действиях профиля и строках возможностей. Один размер, один блеск, одна
 * тень: список выглядит собранным, а не «каждая строка сама по себе».
 */
@Composable
fun ApuPremiumIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    /** Описание для чтения экрана: «Открыто», «Ещё закрыто» и подобные. */
    contentDescription: String? = null,
    size: Dp = 40.dp,
    corner: Dp = 12.dp,
    background: Brush = apuGoldBrush(),
    tint: Color = ApuGoldInk,
    lift: Dp = 6.dp,
    gloss: Float = 0.9f,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .size(size)
            .apuPremiumLift(lift, shape, ApuGold.copy(alpha = 0.45f))
            .clip(shape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.45f), shape)
            .apuPremiumGloss(shape, intensity = gloss, topFraction = 0.6f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * Кнопка премиального стиля для всего приложения — та же самая, что стоит в
 * окне «Логи» (золотая, стеклянная, тихая или красная опасная). Держим один
 * реализацию, чтобы кнопки в разных разделах не разъезжались по виду.
 */
@Composable
fun ApuPremiumButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: DiagnosticsActionStyle = DiagnosticsActionStyle.GLASS,
    enabled: Boolean = true,
    compact: Boolean = false,
    progress: Boolean = false,
) = ApuDiagnosticsActionButton(
    label = label,
    onClick = onClick,
    modifier = modifier,
    icon = icon,
    style = style,
    enabled = enabled,
    compact = compact,
    progress = progress,
)

/**
 * Вариант той же объёмной кнопки для существующих действий с нестандартным
 * содержимым (например, индикатором загрузки). Контейнер, контраст и нажатие
 * всё равно задаёт общая кнопка APU.
 */
@Composable
fun ApuPremiumContentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DiagnosticsActionStyle = DiagnosticsActionStyle.GLASS,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = ApuDiagnosticsActionButton(
    label = "",
    onClick = onClick,
    modifier = modifier,
    style = style,
    enabled = enabled,
    content = content,
)
