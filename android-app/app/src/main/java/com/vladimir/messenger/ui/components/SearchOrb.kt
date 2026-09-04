package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

/**
 * Кнопка поиска — выпуклая клавиша с нарисованной лупой.
 *
 * Первый вариант был стеклянным шаром с бликом: на обоях APU он читался как
 * пузырь, а сама лупа в нём терялась. Поэтому здесь скруглённый квадрат —
 * форма кнопки, а не украшения, — со светлым верхом и тенью снизу, а лупа
 * нарисована толстой линией прямо на `Canvas`: обод, ручка и короткий блик на
 * стекле. Ничего не мигает и не крутится: это рабочая кнопка в шапке.
 */
@Composable
fun SearchOrb(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String = "Поиск",
) {
    // Отдельная переменная: внутри semantics имя contentDescription занято
    // свойством области видимости, и присваивание себе же не читалось бы.
    val label = contentDescription
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Нажатие проседает — клавиша ощущается настоящей.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(),
        label = "search-orb-press",
    )
    // Золото APU: тёплый металл, а не цвет темы, чтобы кнопка была своя.
    val gold = Color(0xFFD8A93A)
    val goldLight = Color(0xFFF6DC96)
    val goldDark = Color(0xFF8A6410)

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(RoundedCornerShape(percent = 32))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val corner = androidx.compose.ui.geometry.CornerRadius(w * 0.32f, w * 0.32f)

            // Тень под клавишей: сдвинута вниз, отсюда ощущение объёма.
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.20f),
                topLeft = Offset(w * 0.06f, h * 0.12f),
                size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.88f),
                cornerRadius = corner,
            )
            // Тело: свет сверху, тень снизу - выпуклость без всякого блика.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(goldLight, gold, goldDark),
                ),
                topLeft = Offset(w * 0.04f, h * 0.02f),
                size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.88f),
                cornerRadius = corner,
            )
            // Светлая кромка по верху - как фаска на настоящей клавише.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.45f),
                topLeft = Offset(w * 0.04f, h * 0.02f),
                size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.88f),
                cornerRadius = corner,
                style = Stroke(width = w * 0.035f),
            )

            // Сама лупа. Рисуем от центра клавиши, слегка наклонив ручку.
            val cx = w * 0.48f
            val cy = h * 0.44f
            val lens = w * 0.20f
            val line = w * 0.085f

            // Тёмная подложка обода - лупа не сливается с золотом.
            drawCircle(
                color = goldDark.copy(alpha = 0.55f),
                radius = lens + line * 0.35f,
                center = Offset(cx, cy + h * 0.012f),
                style = Stroke(width = line),
            )
            drawCircle(
                color = Color.White,
                radius = lens,
                center = Offset(cx, cy),
                style = Stroke(width = line),
            )
            // Ручка: от края стекла вниз-вправо.
            val start = Offset(cx + lens * 0.72f, cy + lens * 0.72f)
            val end = Offset(cx + lens * 1.75f, cy + lens * 1.75f)
            drawLine(
                color = goldDark.copy(alpha = 0.55f),
                start = start.copy(y = start.y + h * 0.012f),
                end = end.copy(y = end.y + h * 0.012f),
                strokeWidth = line * 1.15f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White,
                start = start,
                end = end,
                strokeWidth = line * 1.15f,
                cap = StrokeCap.Round,
            )
            // Короткий блик на стекле: одна дуга, без пузыря.
            drawArc(
                color = Color.White.copy(alpha = 0.75f),
                startAngle = 185f,
                sweepAngle = 55f,
                useCenter = false,
                topLeft = Offset(cx - lens * 0.55f, cy - lens * 0.55f),
                size = androidx.compose.ui.geometry.Size(lens * 1.1f, lens * 1.1f),
                style = Stroke(width = line * 0.5f, cap = StrokeCap.Round),
            )
        }
        // Значка Material здесь нет: лупа нарисована выше. Описание для
        // чтения с экрана вешаем на саму кнопку.
    }
}
