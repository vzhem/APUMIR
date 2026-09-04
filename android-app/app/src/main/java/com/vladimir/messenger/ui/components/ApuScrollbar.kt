package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Бегунок прокрутки справа от списка.
 *
 * Родного бегунка у `LazyColumn` нет, поэтому он рисуется сам. Положение и
 * длина считаются по СРЕДНЕЙ высоте видимых строк: точной высоты всего списка
 * ленивый список не знает, а карточки чатов почти одинаковые, так что оценка
 * получается ровной и не прыгает.
 *
 * Бегунок видно только во время прокрутки и он гаснет, когда список стоит,
 * чтобы не закрывать содержимое.
 *
 * Ставить ВНУТРЬ того же `Box`, что и список, после него.
 */
@Composable
fun BoxScope.ApuScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    // Список короче экрана - бегунку нечего показывать.
    if (visible.isEmpty() || total <= visible.size) return

    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewport <= 0f) return

    val averageItem = visible.sumOf { it.size }.toFloat() / visible.size
    val contentHeight = averageItem * total
    if (contentHeight <= viewport) return

    // Сколько уже пролистано: целые строки плюс сдвиг текущей.
    val scrolled = state.firstVisibleItemIndex * averageItem +
        state.firstVisibleItemScrollOffset
    val maxScroll = (contentHeight - viewport).coerceAtLeast(1f)
    val progress = (scrolled / maxScroll).coerceIn(0f, 1f)
    // Не даём бегунку выродиться в точку на очень длинных списках.
    val thumbFraction = (viewport / contentHeight).coerceIn(0.08f, 1f)

    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) 0.55f else 0f,
        animationSpec = tween(durationMillis = if (state.isScrollInProgress) 120 else 600),
        label = "apu-scrollbar-alpha",
    )
    if (alpha <= 0.01f) return

    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .width(4.dp)
            // Полоса занимает всю высоту, а внутри неё дорожка нужной длины
            // сдвигается вниз: так не нужно знать высоту в dp заранее.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight(thumbFraction)
                .width(4.dp)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val free = (constraints.maxHeight - placeable.height).coerceAtLeast(0)
                    layout(placeable.width, constraints.maxHeight) {
                        placeable.placeRelative(0, (free * progress).roundToInt())
                    }
                }
                .clip(RoundedCornerShape(2.dp))
                .alpha(alpha)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
