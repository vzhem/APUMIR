package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Объёмная медаль ранга рядом с названием на главном экране.
 *
 * Рисуется вручную, а не значком-эмодзи: у эмодзи вид зависит от прошивки
 * телефона, и объём с бликом ему не добавить. Здесь золото задаётся градиентом
 * (светлый верх - тёмный низ), поверх лежит блик, а по краю идёт тёмный
 * ободок - из этого и складывается ощущение выпуклости.
 *
 * Анимации две и обе спокойные: медаль слегка покачивается на ленте, как
 * настоящая, и по ней раз в несколько секунд пробегает световой отблеск.
 * Постоянного вращения нет намеренно - значок висит в шапке всё время, и
 * мельтешение там утомляет.
 */
@Composable
fun RankMedal(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val transition = rememberInfiniteTransition(label = "rank-medal")

    // Покачивание на ленте: маятник, а не равномерное вращение.
    val swing by transition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rank-medal-swing",
    )

    // Отблеск: 0..1 - положение светлой полосы, идущей по диску.
    val shine by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rank-medal-shine",
    )

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // Лента занимает верхнюю треть, диск - остальное.
        val diskRadius = w * 0.34f
        val diskCenter = Offset(w / 2f, h - diskRadius - h * 0.04f)

        rotate(degrees = swing, pivot = Offset(w / 2f, 0f)) {
            drawRibbon(w, h, diskCenter, diskRadius)
            drawDisk(diskCenter, diskRadius, shine)
        }
    }
}

/** Двухцветная лента-галочка над диском. */
private fun DrawScope.drawRibbon(
    w: Float,
    h: Float,
    diskCenter: Offset,
    diskRadius: Float,
) {
    val topY = h * 0.02f
    val bottomY = diskCenter.y - diskRadius * 0.55f
    val halfWidth = w * 0.17f

    // Левая половина - синяя, правая - красная: узнаваемый наградной вид.
    val left = Path().apply {
        moveTo(w / 2f - halfWidth, topY)
        lineTo(w / 2f, topY)
        lineTo(w / 2f, bottomY)
        lineTo(w / 2f - halfWidth * 0.55f, bottomY)
        close()
    }
    val right = Path().apply {
        moveTo(w / 2f, topY)
        lineTo(w / 2f + halfWidth, topY)
        lineTo(w / 2f + halfWidth * 0.55f, bottomY)
        lineTo(w / 2f, bottomY)
        close()
    }
    drawPath(left, Brush.verticalGradient(listOf(Color(0xFF3E64C8), Color(0xFF27407F))))
    drawPath(right, Brush.verticalGradient(listOf(Color(0xFFD3453F), Color(0xFF8E2B27))))
}

/** Золотой диск: объём даёт градиент, ободок и бегущий отблеск. */
private fun DrawScope.drawDisk(center: Offset, radius: Float, shine: Float) {
    // Тень под медалью - она и «отрывает» значок от подложки.
    drawCircle(
        color = Color(0x33000000),
        radius = radius * 1.02f,
        center = center.copy(y = center.y + radius * 0.10f),
    )

    // Основное золото: свет сверху слева, тень снизу справа.
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFFFE9A8),
                Color(0xFFE7B93F),
                Color(0xFF9A6F16),
            ),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
        radius = radius,
        center = center,
        style = Fill,
    )

    // Тёмный ободок по краю.
    drawCircle(
        color = Color(0xFF7A5510),
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.13f),
    )

    // Утопленная середина: ещё один градиент, но развёрнутый наоборот -
    // получается «ступенька» внутрь.
    val innerRadius = radius * 0.62f
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFC9982B), Color(0xFFFFE49B)),
            start = Offset(center.x - innerRadius, center.y - innerRadius),
            end = Offset(center.x + innerRadius, center.y + innerRadius),
        ),
        radius = innerRadius,
        center = center,
    )

    // Пятиконечная звезда в центре.
    drawPath(
        path = starPath(center, innerRadius * 0.86f, innerRadius * 0.38f),
        color = Color(0xFF6B4A0C),
    )

    // Бегущий отблеск: светлое пятно проходит по диску слева направо.
    val shinePos = -1.2f + shine * 2.4f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x88FFFFFF), Color(0x00FFFFFF)),
            center = Offset(center.x + radius * shinePos, center.y - radius * 0.3f),
            radius = radius * 0.9f,
        ),
        radius = radius,
        center = center,
    )

    // Постоянный блик в левом верхнем углу - «стеклянность» металла.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x99FFFFFF), Color(0x00FFFFFF)),
            center = Offset(center.x - radius * 0.38f, center.y - radius * 0.45f),
            radius = radius * 0.55f,
        ),
        radius = radius,
        center = center,
    )
}

/** Пятиконечная звезда: чередуем дальние и ближние точки. */
private fun starPath(center: Offset, outer: Float, inner: Float): Path {
    val path = Path()
    val points = 5
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outer else inner
        // Начинаем сверху: -90 градусов в радианах.
        val angle = (-Math.PI / 2 + i * Math.PI / points).toFloat()
        val x = center.x + radius * cos(angle)
        val y = center.y + radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
