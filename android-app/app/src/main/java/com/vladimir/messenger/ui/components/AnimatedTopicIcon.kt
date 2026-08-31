package com.vladimir.messenger.ui.components

// =============================================================================
// ANIMATEDTOPICICON.KT — живые значки тем, нарисованные кодом на Canvas
// =============================================================================
// Владелец попросил анимированные значки: мячик скачет, паровозик едет и
// дымит, рыбка машет хвостом. Ассетов нет и не надо: каждый значок - это
// небольшая Canvas-анимация на бесконечном переходе, поэтому значок живёт в
// базе и в проводе как короткое кодовое слово ("ball", "train", ...), а
// рисуется здесь. Старые темы с эмодзи-строкой TopicIconView дорисовывает
// обычным текстом.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object TopicIconCatalog {
    val kinds = listOf(
        "chat", "ball", "train", "fish", "heart", "star",
        "flame", "balloon", "note", "sun", "moon", "gear",
    )
    const val DEFAULT = "chat"
    fun isKnown(kind: String): Boolean = kind in kinds
    val labels = mapOf(
        "chat" to "Пузырёк",
        "ball" to "Мячик",
        "train" to "Паровозик",
        "fish" to "Рыбка",
        "heart" to "Сердце",
        "star" to "Звезда",
        "flame" to "Огонёк",
        "balloon" to "Шарик",
        "note" to "Нота",
        "sun" to "Солнце",
        "moon" to "Месяц",
        "gear" to "Шестерёнка",
    )
}

/**
 * Значок темы: кодовое слово из каталога рисуется анимацией, а всё остальное
 * (эмодзи у тем, созданных раньше) показывается текстом, как прежде.
 */
@Composable
fun TopicIconView(icon: String, size: Dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (TopicIconCatalog.isKnown(icon)) {
            AnimatedTopicIcon(icon, Modifier.size(size))
        } else {
            Text(icon, fontSize = (size.value * 0.55f).sp)
        }
    }
}

@Composable
fun AnimatedTopicIcon(kind: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "topic-icon")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topic-icon-t",
    )
    Canvas(modifier = modifier) {
        when (kind) {
            "chat" -> drawChat(t)
            "ball" -> drawBall(t)
            "train" -> drawTrain(t)
            "fish" -> drawFish(t)
            "heart" -> drawHeart(t)
            "star" -> drawStar(t)
            "flame" -> drawFlame(t)
            "balloon" -> drawBalloon(t)
            "note" -> drawNote(t)
            "sun" -> drawSun(t)
            "moon" -> drawMoon(t)
            "gear" -> drawGear(t)
        }
    }
}

// ── вспомогательные ──────────────────────────────────────────────────────────

private val PI2 = (2.0 * Math.PI).toFloat()
private fun sinF(x: Float): Float = sin(x.toDouble()).toFloat()
private fun cosF(x: Float): Float = cos(x.toDouble()).toFloat()

/** drawOval в удобных координатах лево/верх/право/низ. */
private fun DrawScope.oval(
    color: Color,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    drawOval(color, Offset(left, top), Size(right - left, bottom - top))
}

private val GOLD = Color(0xFFE0A32E)
private val DARK = Color(0xFF1E2430)
private val RED = Color(0xFFE04B3A)
private val ORANGE = Color(0xFFF08A24)
private val BLUE = Color(0xFF3E77C9)
private val YELLOW = Color(0xFFF6C445)
private val GRAY = Color(0xFF8B94A3)
private val GREEN = Color(0xFF57A65A)
private val PINK = Color(0xFFE56A9A)

// Фон кружка значка - этим цветом вырезаем серп месяца. Кружки значков везде
// рисуются на этом фоне (список тем, колонка, диалог), поэтому вырез невидим.
private val HOLE = Color(0xFFE8EEF5)

// ── значки ───────────────────────────────────────────────────────────────────

/** Пузырёк сообщения с тремя точками: мягко дышит. */
private fun DrawScope.drawChat(t: Float) {
    val r = size.minDimension * (0.30f + 0.02f * sinF(PI2 * t))
    val cx = size.width / 2
    val cy = size.height / 2 - size.height * 0.06f
    drawCircle(GOLD, r, Offset(cx, cy))
    val tail = Path().apply {
        moveTo(cx - r * 0.4f, cy + r * 0.75f)
        lineTo(cx - r * 0.15f, cy + r * 1.45f)
        lineTo(cx + r * 0.35f, cy + r * 0.85f)
        close()
    }
    drawPath(tail, GOLD)
    drawCircle(Color.White, r * 0.15f, Offset(cx - r * 0.4f, cy))
    drawCircle(Color.White, r * 0.15f, Offset(cx, cy))
    drawCircle(Color.White, r * 0.15f, Offset(cx + r * 0.4f, cy))
}

/** Мячик скачет и сплющивается у земли, тень дышит. */
private fun DrawScope.drawBall(t: Float) {
    val w = size.width
    val h = size.height
    val bounce = kotlin.math.abs(sinF(PI2 * t / 2f))
    val r = w * 0.17f
    val y = h * 0.78f - bounce * h * 0.5f
    val squash = if (bounce < 0.15f) 0.8f else 1f
    drawCircle(
        DARK.copy(alpha = 0.15f + 0.15f * (1f - bounce)),
        r * (0.8f + 0.5f * (1f - bounce)),
        Offset(w / 2, h * 0.84f),
    )
    oval(
        ORANGE,
        w / 2 - r / squash,
        y - r * squash,
        w / 2 + r / squash,
        y + r * squash,
    )
}

/** Паровозик крутит колёсами со спицами и пускает три клуба дыма. */
private fun DrawScope.drawTrain(t: Float) {
    val w = size.width
    val h = size.height
    val bodyW = w * 0.66f
    val left = (w - bodyW) / 2
    val top = h * 0.44f
    for (i in 0..2) {
        val p = (t + i / 3f) % 1f
        val px = left + bodyW * 0.20f + sinF(p * 5f) * w * 0.03f
        val py = top - h * 0.08f - p * h * 0.26f
        drawCircle(
            GRAY.copy(alpha = 0.55f * (1f - p)),
            w * (0.05f + 0.06f * p),
            Offset(px, py),
        )
    }
    // труба, котёл и кабина
    drawRect(DARK, Offset(left + bodyW * 0.13f, top - h * 0.10f), Size(w * 0.07f, h * 0.12f))
    drawRect(BLUE, Offset(left, top), Size(bodyW * 0.62f, h * 0.22f))
    drawRect(RED, Offset(left + bodyW * 0.62f, top - h * 0.10f), Size(bodyW * 0.38f, h * 0.32f))
    // колёса со спицами, вращаются
    val wy = top + h * 0.22f + h * 0.08f
    for (fx in listOf(0.18f, 0.5f, 0.84f)) {
        val wx = left + bodyW * fx
        drawCircle(DARK, w * 0.08f, Offset(wx, wy))
        rotate(t * 360f, Offset(wx, wy)) {
            drawLine(Color.White, Offset(wx - w * 0.055f, wy), Offset(wx + w * 0.055f, wy), strokeWidth = w * 0.02f)
            drawLine(Color.White, Offset(wx, wy - w * 0.055f), Offset(wx, wy + w * 0.055f), strokeWidth = w * 0.02f)
        }
    }
}

/** Рыбка плывёт и машет хвостиком, пускает пузырёк. */
private fun DrawScope.drawFish(t: Float) {
    val w = size.width
    val h = size.height
    val cx = size.width / 2 + w * 0.04f
    val cy = size.height / 2
    val swing = sinF(PI2 * t) * 20f
    // хвост качается вокруг точки крепления
    rotate(swing, Offset(cx - w * 0.18f, cy)) {
        val tail = Path().apply {
            moveTo(cx - w * 0.16f, cy)
            lineTo(cx - w * 0.36f, cy - h * 0.14f)
            lineTo(cx - w * 0.36f, cy + h * 0.14f)
            close()
        }
        drawPath(tail, ORANGE)
    }
    oval(ORANGE, cx - w * 0.20f, cy - h * 0.15f, cx + w * 0.26f, cy + h * 0.15f)
    // плавник
    val fin = Path().apply {
        moveTo(cx - w * 0.02f, cy - h * 0.13f)
        lineTo(cx + w * 0.08f, cy - h * 0.26f)
        lineTo(cx + w * 0.10f, cy - h * 0.10f)
        close()
    }
    drawPath(fin, RED)
    drawCircle(DARK, w * 0.03f, Offset(cx + w * 0.16f, cy - h * 0.04f))
    // пузырёк поднимается
    val p = (t * 1f) % 1f
    drawCircle(
        BLUE.copy(alpha = 0.6f * (1f - p)),
        w * 0.035f,
        Offset(cx + w * 0.30f, cy - h * 0.10f - p * h * 0.25f),
    )
}

/** Сердце бьётся. */
private fun DrawScope.drawHeart(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    val s = 1f + 0.10f * max(0f, sinF(PI2 * t))
    scale(s, Offset(cx, cy)) {
        val r = w * 0.15f
        drawCircle(RED, r, Offset(cx - r * 0.95f, cy - r * 0.6f))
        drawCircle(RED, r, Offset(cx + r * 0.95f, cy - r * 0.6f))
        val bottom = Path().apply {
            moveTo(cx - r * 1.85f, cy - r * 0.15f)
            lineTo(cx + r * 1.85f, cy - r * 0.15f)
            lineTo(cx, cy + r * 2.1f)
            close()
        }
        drawPath(bottom, RED)
    }
}

/** Звезда медленно кружится и вспыхивает. */
private fun DrawScope.drawStar(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    val r = w * 0.34f * (1f + 0.06f * sinF(PI2 * t))
    rotate(t * 120f, Offset(cx, cy)) {
        val path = Path().apply {
            for (i in 0 until 10) {
                val angle = i * 36f - 90f
                val rad = if (i % 2 == 0) r else r * 0.45f
                val px = cx + cosF(angle * PI2 / 360f) * rad
                val py = cy + sinF(angle * PI2 / 360f) * rad
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        drawPath(path, YELLOW)
    }
}

/** Огонёк: языки пламени покачиваются. */
private fun DrawScope.drawFlame(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2 + h * 0.08f
    val wob = sinF(PI2 * t) * w * 0.05f
    val outer = Path().apply {
        moveTo(cx - w * 0.18f, cy + h * 0.16f)
        quadraticBezierTo(cx - w * 0.20f + wob, cy - h * 0.10f, cx + wob, cy - h * 0.34f)
        quadraticBezierTo(cx + w * 0.20f + wob, cy - h * 0.10f, cx + w * 0.18f, cy + h * 0.16f)
        close()
    }
    drawPath(outer, ORANGE)
    val inner = Path().apply {
        moveTo(cx - w * 0.09f, cy + h * 0.14f)
        quadraticBezierTo(cx - w * 0.10f - wob * 0.6f, cy, cx - wob * 0.6f, cy - h * 0.14f)
        quadraticBezierTo(cx + w * 0.10f - wob * 0.6f, cy, cx + w * 0.09f, cy + h * 0.14f)
        close()
    }
    drawPath(inner, YELLOW)
}

/** Шарик на ниточке покачивается. */
private fun DrawScope.drawBalloon(t: Float) {
    val w = size.width
    val h = size.height
    val sway = sinF(PI2 * t) * w * 0.08f
    val bx = w / 2 + sway
    oval(PINK, bx - w * 0.15f, h * 0.12f, bx + w * 0.15f, h * 0.48f)
    val knot = Path().apply {
        moveTo(bx - w * 0.04f, h * 0.48f)
        lineTo(bx + w * 0.04f, h * 0.48f)
        lineTo(bx, h * 0.56f)
        close()
    }
    drawPath(knot, PINK)
    val string = Path().apply {
        moveTo(bx, h * 0.54f)
        quadraticBezierTo(w / 2 - sway, h * 0.72f, w / 2, h * 0.88f)
    }
    drawPath(string, GRAY, style = Stroke(width = w * 0.02f))
}

/** Нота подпрыгивает. */
private fun DrawScope.drawNote(t: Float) {
    val w = size.width
    val h = size.height
    val bob = (sinF(PI2 * t) * 0.5f + 0.5f) * h * 0.14f
    val y = h * 0.68f - bob
    val nx = w / 2 - w * 0.06f
    drawCircle(BLUE, w * 0.11f, Offset(nx, y))
    drawLine(
        BLUE,
        Offset(nx + w * 0.10f, y),
        Offset(nx + w * 0.10f, y - h * 0.38f),
        strokeWidth = w * 0.045f,
    )
    val flag = Path().apply {
        moveTo(nx + w * 0.10f, y - h * 0.38f)
        quadraticBezierTo(nx + w * 0.30f, y - h * 0.30f, nx + w * 0.26f, y - h * 0.16f)
        lineTo(nx + w * 0.10f, y - h * 0.24f)
        close()
    }
    drawPath(flag, BLUE)
}

/** Солнце с вращающимися лучами. */
private fun DrawScope.drawSun(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    rotate(t * 90f, Offset(cx, cy)) {
        for (i in 0 until 8) {
            rotate(i * 45f, Offset(cx, cy)) {
                drawLine(
                    GOLD,
                    Offset(cx, cy - w * 0.24f),
                    Offset(cx, cy - w * 0.40f),
                    strokeWidth = w * 0.05f,
                )
            }
        }
    }
    drawCircle(YELLOW, w * 0.18f, Offset(cx, cy))
}

/** Месяц покачивается, рядом мигает звёздочка. */
private fun DrawScope.drawMoon(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    val rock = sinF(PI2 * t) * 12f
    rotate(rock, Offset(cx, cy)) {
        drawCircle(YELLOW, w * 0.24f, Offset(cx, cy))
        drawCircle(HOLE, w * 0.21f, Offset(cx + w * 0.13f, cy - w * 0.09f))
    }
    val tw = (sinF(PI2 * t) + 1f) / 2f
    val sx = cx - w * 0.28f
    val sy = cy - h * 0.26f
    drawLine(
        YELLOW.copy(alpha = 0.4f + 0.6f * tw),
        Offset(sx - w * 0.06f, sy),
        Offset(sx + w * 0.06f, sy),
        strokeWidth = w * 0.03f,
    )
    drawLine(
        YELLOW.copy(alpha = 0.4f + 0.6f * tw),
        Offset(sx, sy - w * 0.06f),
        Offset(sx, sy + w * 0.06f),
        strokeWidth = w * 0.03f,
    )
}

/** Шестерёнка крутится. */
private fun DrawScope.drawGear(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    rotate(t * 180f, Offset(cx, cy)) {
        for (i in 0 until 8) {
            rotate(i * 45f, Offset(cx, cy)) {
                drawRect(
                    GRAY,
                    Offset(cx - w * 0.055f, cy - w * 0.38f),
                    Size(w * 0.11f, w * 0.14f),
                )
            }
        }
        drawCircle(GRAY, w * 0.27f, Offset(cx, cy))
        drawCircle(HOLE, w * 0.12f, Offset(cx, cy))
    }
}
