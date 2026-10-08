package com.vladimir.messenger.ui.components

// =============================================================================
// APUTABBAR.KT — полоска вкладок APU: один общий пузырь и едущая метка
// =============================================================================
// Используется на главном экране (разделы «Все / Не прочитано / Чаты / Группы / Каналы») и в
// админ-кабинете группы и канала. Смысл один и тот же, поэтому и код один.
//
// Метка не перещёлкивается: её положение задаётся непрерывным числом
// `position = страница + смещение листалки`. Значит метка едет ровно с той же
// скоростью, что и страницы, а при остановленном посередине пальце стоит
// посередине между двумя вкладками.
//
// Надписи разной ширины, поэтому X и ширина метки смешиваются между замерами
// соседних надписей. Замеры снимаются через onGloballyPositioned, а вместо
// LazyRow используется horizontalScroll: у ленивого списка соседней надписи
// может ещё не существовать, и метке не из чего было бы считать середину.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Полоска вкладок в общем пузыре.
 *
 * @param titles подписи вкладок слева направо.
 * @param selectedIndex текущая страница листалки.
 * @param offsetFraction смещение листалки от текущей страницы (-1..1).
 * @param onSelect тап по вкладке.
 * @param badges счётчики для вкладок (0 = не показывать). Размер = titles.size.
 */
@Composable
fun ApuTabBar(
    titles: List<String>,
    selectedIndex: Int,
    offsetFraction: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<Int> = emptyList(),
) {
    if (titles.isEmpty()) return

    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    // Замеры надписей: X и ширина в пикселях. Пересобираются, если состав
    // вкладок поменялся (например, телефон перестал быть администратором).
    var bounds by remember(titles, badges) { mutableStateOf(List(titles.size) { 0 to 0 }) }

    val position = (selectedIndex + offsetFraction)
        .coerceIn(0f, (titles.size - 1).toFloat())
    val left = position.toInt().coerceAtMost(titles.size - 1)
    val right = (left + 1).coerceAtMost(titles.size - 1)
    val blend = position - left

    val leftBounds = bounds.getOrElse(left) { 0 to 0 }
    val rightBounds = bounds.getOrElse(right) { 0 to 0 }
    val markerX = leftBounds.first + (rightBounds.first - leftBounds.first) * blend
    val markerWidth = leftBounds.second + (rightBounds.second - leftBounds.second) * blend

    // Полоска сама едет за меткой: вкладок может быть больше, чем влезает.
    LaunchedEffect(markerX, markerWidth, scrollState.viewportSize, scrollState.maxValue) {
        val viewport = scrollState.viewportSize
        if (viewport > 0 && markerWidth > 0f) {
            val target = (markerX + markerWidth / 2f - viewport / 2f)
                .toInt()
                .coerceIn(0, scrollState.maxValue)
            runCatching { scrollState.scrollTo(target) }
        }
    }

    // Владелец 2026-10-07: «Теперь в таком стиле нужно переделать всё приложение».
    // Полоска вкладок — та же премиальная поверхность, что верхняя и нижняя
    // панели: подъём, единая подложка, золотая нить по кромке и блеск, который
    // ложится ПОД надписями, поэтому подписи вкладок остаются чёткими.
    val barShape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .apuPremiumLift(6.dp, barShape)
            .apuBubbleSurface(shape = barShape)
            .apuPremiumThread(shape = barShape, inset = 18.dp)
            .apuPremiumGloss(barShape, intensity = 0.45f, topFraction = 0.6f),
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            if (markerWidth > 0f) {
                // Метка — золотая плитка с блеском: видно, где ты, и это ровно
                // тот же «свой» вид, что у выбранного раздела в нижней панели
                // и у кнопок окна «Логи».
                val markerShape = RoundedCornerShape(17.dp)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(markerX.toInt(), 0) }
                        .width(with(density) { markerWidth.toDp() })
                        .height(34.dp)
                        .clip(markerShape)
                        .background(apuGoldBrush())
                        .border(1.dp, Color.White.copy(alpha = 0.45f), markerShape)
                        .apuPremiumGloss(markerShape, intensity = 0.8f, topFraction = 0.7f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                titles.forEachIndexed { index, title ->
                    // Чем ближе метка, тем светлее текст: в середине жеста обе
                    // надписи выглядят наполовину выбранными.
                    val nearness = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                    val badge = badges.getOrNull(index) ?: 0
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                val x = coords.positionInParent().x.toInt()
                                val w = coords.size.width
                                val current = bounds.getOrNull(index)
                                if (current == null || current.first != x || current.second != w) {
                                    bounds = bounds.toMutableList().also { list ->
                                        while (list.size <= index) list.add(0 to 0)
                                        list[index] = x to w
                                    }
                                }
                            }
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .clickable { onSelect(index) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge,
                                // Обе краски — тёмные чернила: на золотой метке
                                // и на светлой подложке текст читается одинаково
                                // (светлый onPrimary на золоте «выгорал»).
                                color = lerp(
                                    ApuBubbleTextColor,
                                    ApuGoldInk,
                                    nearness,
                                ),
                            )
                            if (badge > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                ApuNotificationBadge(badge)
                            }
                        }
                    }
                }
            }
        }
    }
}
