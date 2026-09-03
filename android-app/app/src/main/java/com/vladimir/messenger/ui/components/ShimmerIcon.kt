package com.vladimir.messenger.ui.components

// =============================================================================
// SHIMMERICON.KT — значок с живым переливом
// =============================================================================
// Значки в меню были плоские, одного цвета. Здесь значок залит бегущим
// градиентом: цвета медленно перетекают друг в друга, а поверх раз в несколько
// секунд проходит светлый блик — как отблеск на металле.
//
// Как это сделано: значок рисуется как обычно, а поверх него кладётся
// градиентный прямоугольник в режиме SrcIn — он закрашивает ТОЛЬКО непрозрачные
// точки значка, то есть сам силуэт. Значок остаётся самим собой, меняется
// только заливка, поэтому подставить можно любой значок Material без переделки.
//
// Режим SrcIn требует, чтобы значок рисовался в отдельный слой, иначе заливка
// легла бы на весь экран. Слой включается через compositingStrategy = Offscreen.
//
// Стоимость: одна бесконечная анимация на значок и один слой размером 24dp.
// Разметка не перерисовывается — двигается только заливка.
// =============================================================================

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Цвета перелива. Золото APU ведёт, остальные подсвечивают.
 *
 * Первый и последний цвета совпадают: иначе на стыке круга перелив дёргался бы
 * заметным скачком.
 */
private val ShimmerColors = listOf(
    Color(0xFFD4AF37), // золото APU
    Color(0xFFFFE9A8), // светлое золото
    Color(0xFF7BD8C0), // бирюза
    Color(0xFF6FA8FF), // голубой
    Color(0xFFC792EA), // сирень
    Color(0xFFFFB07C), // персик
    Color(0xFFD4AF37), // замыкаем круг тем же золотом
)

/**
 * Значок, залитый бегущим градиентом со скользящим бликом.
 *
 * @param imageVector любой значок Material.
 * @param size сторона значка.
 * @param durationMs полный оборот перелива; блик идёт вдвое медленнее.
 */
@Composable
fun ShimmerIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    durationMs: Int = 4200,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")

    // Сдвиг градиента: цвета перетекают друг в друга без остановки.
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    )

    // Блик идёт медленнее перелива, поэтому вспышка читается отдельным
    // событием, а не частью смены цвета.
    val glare by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs * 2, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glare",
    )

    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        // Цвет силуэта не важен: поверх ляжет градиент в режиме SrcIn. Но он
        // обязан быть непрозрачным, иначе закрашивать будет нечего.
        tint = Color.White,
        modifier = modifier
            .size(size)
            // Отдельный слой: без него SrcIn закрасил бы всё вокруг значка.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()

                val side = this.size.minDimension
                // Полоса вдвое шире значка и ездит на свою ширину: значок всё
                // время накрыт её серединой, поэтому краёв градиента не видно.
                val span = side * 2f
                val start = -span + shift * span

                drawRect(
                    brush = Brush.linearGradient(
                        colors = ShimmerColors,
                        start = Offset(start, 0f),
                        end = Offset(start + span, side),
                    ),
                    blendMode = BlendMode.SrcIn,
                )

                val glarePos = glare * side
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.75f),
                            Color.Transparent,
                        ),
                        start = Offset(glarePos - side * 0.35f, 0f),
                        end = Offset(glarePos + side * 0.35f, side),
                    ),
                    // SrcAtop, а НЕ SrcIn: второй SrcIn заменил бы уже
                    // положенный градиент прозрачностью и значок мигал бы.
                    // SrcAtop подмешивает блик поверх, не трогая силуэт.
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}
