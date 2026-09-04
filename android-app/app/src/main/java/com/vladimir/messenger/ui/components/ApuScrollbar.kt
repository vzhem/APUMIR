package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp

/**
 * Бегунок прокрутки справа от списка.
 *
 * Родного бегунка у `LazyColumn` нет, поэтому он рисуется сам. Длина и
 * положение считаются по СРЕДНЕЙ высоте видимых строк: точной высоты всего
 * списка ленивый список не знает, а карточки почти одинаковые, так что оценка
 * получается ровной.
 *
 * Высота дорожки берётся из `BoxWithConstraints`. Раньше сдвиг считался внутри
 * модификатора, стоявшего ПОСЛЕ `fillMaxHeight`, — там уже приходили
 * constraints самого бегунка, свободного места оставалось ноль, и он стоял на
 * месте.
 *
 * Ставить ВНУТРЬ того же `Box`, что и список, сразу после него.
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
    if (averageItem <= 0f) return
    val contentHeight = averageItem * total
    if (contentHeight <= viewport) return

    // Сколько уже пролистано: целые строки плюс сдвиг текущей.
    val scrolled = state.firstVisibleItemIndex * averageItem +
        state.firstVisibleItemScrollOffset
    val maxScroll = (contentHeight - viewport).coerceAtLeast(1f)
    val progress = (scrolled / maxScroll).coerceIn(0f, 1f)
    // Не даём бегунку выродиться в точку на очень длинных списках.
    val thumbFraction = (viewport / contentHeight).coerceIn(0.10f, 1f)

    // Виден во время прокрутки и ещё немного после, чтобы палец успел
    // заметить, куда он приехал. В покое гаснет и не закрывает содержимое.
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) 0.6f else 0f,
        animationSpec = tween(durationMillis = if (state.isScrollInProgress) 120 else 900),
        label = "apu-scrollbar-alpha",
    )
    if (alpha <= 0.01f) return

    BoxWithConstraints(
        modifier = modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .width(5.dp),
    ) {
        val trackHeight = maxHeight
        val thumbHeight = trackHeight * thumbFraction
        // Свободный ход = дорожка минус сам бегунок.
        val travel = trackHeight - thumbHeight

        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .offset(y = travel * progress)
                .height(thumbHeight)
                .width(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .alpha(alpha)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
