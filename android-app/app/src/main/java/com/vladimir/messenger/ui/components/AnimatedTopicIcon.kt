package com.vladimir.messenger.ui.components

// =============================================================================
// ANIMATEDTOPICICON.KT — живые красочные значки тем, нарисованные кодом
// =============================================================================
// Владелец попросил ~100 значков, красочнее: с оттенками, бликами,
// переливами. Набор параметрический: 10 форм x 10 палитр = 100 значков плюс
// 5 особых (пузырёк чата, паровозик, рыбка, шестерёнка, солнце) = 105.
// Каждый значок - Canvas-анимация: градиентное тело, белый блик, бегущий
// перелив и одно из пяти движений (скачет, кружится, пульсирует,
// покачивается, плывёт по кругу). Код значка - "форма-оттенок" ("ball-3"),
// он хранится в базе и ходит по проводу; старые коды ("ball", "train") и
// эмодзи дорисовываются совместимо.

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object TopicIconCatalog {
    val shapes = listOf(
        "ball", "heart", "star", "drop", "flower",
        "gem", "moon", "note", "balloon", "flame",
    )
    val specials = listOf("chat", "train", "fish", "gear", "sun")

    val shapeNames = mapOf(
        "ball" to "Мячик",
        "heart" to "Сердце",
        "star" to "Звезда",
        "drop" to "Капля",
        "flower" to "Цветок",
        "gem" to "Кристалл",
        "moon" to "Месяц",
        "note" to "Нота",
        "balloon" to "Шарик",
        "flame" to "Огонёк",
        "chat" to "Пузырёк",
        "train" to "Паровозик",
        "fish" to "Рыбка",
        "gear" to "Шестерёнка",
        "sun" to "Солнце",
    )
    val colorNames = listOf(
        "алый", "янтарный", "золотой", "изумрудный", "бирюзовый",
        "небесный", "синий", "фиолетовый", "малиновый", "серебряный",
    )

    /** Все 105 кодов для сетки выбора. */
    val kinds: List<String> = shapes.flatMap { s -> (0 until 10).map { "$s-$it" } } + specials

    const val DEFAULT = "chat"

    private val legacy = setOf(
        "ball", "heart", "star", "drop", "flower",
        "gem", "moon", "note", "balloon", "flame",
    )
    private val all = kinds.toSet() + legacy + specials

    fun isKnown(kind: String): Boolean = kind in all

    /** Форма и оттенок; старые коды без оттенка получают золотой. */
    fun resolve(kind: String): Pair<String, Int>? {
        if (kind in specials) return kind to 2
        if (kind in legacy) return kind to 2
        val dash = kind.lastIndexOf('-')
        if (dash <= 0) return null
        val shape = kind.substring(0, dash)
        val idx = kind.substring(dash + 1).toIntOrNull() ?: return null
        if (shape !in shapes || idx !in 0..9) return null
        return shape to idx
    }

    fun describe(kind: String): String {
        val r = resolve(kind) ?: return kind
        return if (r.first in specials) {
            shapeNames[r.first].orEmpty()
        } else {
            (shapeNames[r.first].orEmpty()) + " · " + colorNames[r.second]
        }
    }
}

/**
 * Значок темы: код из каталога рисуется анимацией, эмодзи старых тем -
 * текстом, как прежде.
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
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topic-icon-t",
    )
    Canvas(modifier = modifier) {
        val r = TopicIconCatalog.resolve(kind)
        if (r == null) {
            drawCircle(GOLD, size.minDimension * 0.3f)
            return@Canvas
        }
        when (r.first) {
            "chat" -> drawChat(t)
            "train" -> drawTrain(t)
            "fish" -> drawFish(t)
            "gear" -> drawGear(t)
            "sun" -> drawSun(t)
            else -> drawParametric(r.first, r.second, t)
        }
    }
}

// ── вспомогательные ──────────────────────────────────────────────────────────

private val PI2 = (2.0 * Math.PI).toFloat()
private fun sinF(x: Float): Float = sin(x.toDouble()).toFloat()
private fun cosF(x: Float): Float = cos(x.toDouble()).toFloat()

private fun hsv(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL)

private data class Pal(val main: Color, val light: Color, val dark: Color, val accent: Color)

private fun palette(idx: Int): Pal {
    val hues = floatArrayOf(0f, 25f, 45f, 140f, 170f, 200f, 225f, 275f, 320f, 0f)
    val sats = floatArrayOf(.85f, .9f, .9f, .8f, .75f, .8f, .85f, .75f, .8f, .12f)
    val h = hues[idx]
    val s = sats[idx]
    return Pal(
        main = hsv(h, s, .95f),
        light = hsv(h, s * 0.4f, 1f),
        dark = hsv(h, min(1f, s * 1.2f), .55f),
        accent = hsv((h + 50f) % 360f, .8f, 1f),
    )
}

private val GOLD = Color(0xFFE0A32E)
private val DARK = Color(0xFF1E2430)
private val RED = Color(0xFFE04B3A)
private val ORANGE = Color(0xFFF08A24)
private val BLUE = Color(0xFF3E77C9)
private val YELLOW = Color(0xFFF6C445)
private val GRAY = Color(0xFF8B94A3)

// Фон кружка значка - этим цветом вырезается серп месяца.
private val HOLE = Color(0xFFE8EEF5)

/** drawOval в координатах лево/верх/право/низ. */
private fun DrawScope.oval(
    color: Color,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    drawOval(color, Offset(left, top), Size(right - left, bottom - top))
}

private fun DrawScope.oval(
    brush: Brush,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    drawOval(brush, Offset(left, top), Size(right - left, bottom - top))
}

/** Белый блик сверху-слева - стеклянный отблеск. */
private fun DrawScope.gloss(cx: Float, cy: Float, r: Float) {
    oval(
        Color.White.copy(alpha = 0.55f),
        cx - r * 0.62f, cy - r * 0.78f,
        cx + r * 0.02f, cy - r * 0.28f,
    )
}

/** Бегущий по диагонали перелив. */
private fun DrawScope.shimmer(t: Float, cx: Float, cy: Float, r: Float) {
    val a = (sinF(PI2 * t) + 1f) / 2f
    val x = cx - r * 1.1f + a * r * 1.6f
    rotate(-20f, Offset(cx, cy)) {
        oval(
            Color.White.copy(alpha = 0.10f + 0.22f * a),
            x, cy - r, x + r * 0.45f, cy + r,
        )
    }
}

private fun DrawScope.vBrush(p: Pal, cx: Float, cy: Float, r: Float): Brush =
    Brush.linearGradient(
        listOf(p.light, p.main, p.dark),
        start = Offset(cx, cy - r),
        end = Offset(cx, cy + r),
    )

private fun DrawScope.radBrush(p: Pal, cx: Float, cy: Float, r: Float): Brush =
    Brush.radialGradient(
        listOf(p.light, p.main, p.dark),
        center = Offset(cx - r * 0.45f, cy - r * 0.45f),
        radius = r * 2.1f,
    )

// ── параметрические формы ────────────────────────────────────────────────────

private fun DrawScope.drawParametric(shape: String, idx: Int, t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    val p = palette(idx)
    val motion = idx % 5

    // тень для скачка рисуем до движения
    if (motion == 0) {
        val bounce = kotlin.math.abs(sinF(PI2 * t / 2f))
        drawCircle(
            DARK.copy(alpha = 0.12f + 0.14f * (1f - bounce)),
            w * (0.16f + 0.10f * (1f - bounce)),
            Offset(cx, h * 0.86f),
        )
    }
    val bounce = kotlin.math.abs(sinF(PI2 * t / 2f))
    when (motion) {
        0 -> translate(0f, -bounce * h * 0.16f) { drawShape(shape, p, t, cx, cy, w) }
        1 -> rotate(t * 360f, Offset(cx, cy)) { drawShape(shape, p, t, cx, cy, w) }
        2 -> scale(1f + 0.08f * sinF(PI2 * t), Offset(cx, cy)) { drawShape(shape, p, t, cx, cy, w) }
        3 -> rotate(sinF(PI2 * t) * 14f, Offset(cx, cy)) { drawShape(shape, p, t, cx, cy, w) }
        else -> translate(
            sinF(PI2 * t) * w * 0.07f,
            cosF(PI2 * t) * h * 0.05f,
        ) { drawShape(shape, p, t, cx, cy, w) }
    }
}

private fun DrawScope.drawShape(
    shape: String,
    p: Pal,
    t: Float,
    cx: Float,
    cy: Float,
    w: Float,
) {
    val r = w * 0.30f
    when (shape) {
        "ball" -> {
            drawCircle(radBrush(p, cx, cy, r), r, Offset(cx, cy))
            drawArc(
                Color.White.copy(alpha = 0.35f),
                t * 360f,
                100f,
                false,
                Offset(cx - r * 0.65f, cy - r * 0.65f),
                Offset(cx + r * 0.65f, cy + r * 0.65f),
                style = Stroke(width = w * 0.04f),
            )
            gloss(cx, cy, r)
        }
        "heart" -> {
            val rr = r * 0.62f
            val bottom = Path().apply {
                moveTo(cx - rr * 1.85f, cy - rr * 0.15f)
                lineTo(cx + rr * 1.85f, cy - rr * 0.15f)
                lineTo(cx, cy + rr * 2.1f)
                close()
            }
            drawPath(bottom, vBrush(p, cx, cy, r * 1.4f))
            drawCircle(vBrush(p, cx, cy, r * 1.4f), rr, Offset(cx - rr * 0.95f, cy - rr * 0.6f))
            drawCircle(vBrush(p, cx, cy, r * 1.4f), rr, Offset(cx + rr * 0.95f, cy - rr * 0.6f))
            gloss(cx - rr * 0.7f, cy - rr * 0.6f, rr * 0.9f)
            shimmer(t, cx, cy, r)
        }
        "star" -> {
            val path = Path().apply {
                for (i in 0 until 10) {
                    val angle = i * 36f - 90f
                    val rad = if (i % 2 == 0) r * 1.15f else r * 0.5f
                    val px = cx + cosF(angle * PI2 / 360f) * rad
                    val py = cy + sinF(angle * PI2 / 360f) * rad
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(path, vBrush(p, cx, cy, r))
            val tw = (sinF(PI2 * t) + 1f) / 2f
            drawCircle(
                Color.White.copy(alpha = 0.4f + 0.6f * tw),
                w * 0.05f,
                Offset(cx - r * 0.25f, cy - r * 0.3f),
            )
        }
        "drop" -> {
            val path = Path().apply {
                moveTo(cx, cy - r * 1.15f)
                quadraticBezierTo(cx + r * 0.95f, cy + r * 0.15f, cx + r * 0.66f, cy + r * 0.62f)
                quadraticBezierTo(cx + r * 0.3f, cy + r * 1.05f, cx, cy + r * 1.05f)
                quadraticBezierTo(cx - r * 0.3f, cy + r * 1.05f, cx - r * 0.66f, cy + r * 0.62f)
                quadraticBezierTo(cx - r * 0.95f, cy + r * 0.15f, cx, cy - r * 1.15f)
                close()
            }
            drawPath(path, radBrush(p, cx, cy, r))
            gloss(cx - r * 0.1f, cy, r * 0.8f)
            shimmer(t, cx, cy, r)
        }
        "flower" -> {
            for (i in 0 until 6) {
                rotate(i * 60f + t * 60f, Offset(cx, cy)) {
                    oval(
                        Brush.linearGradient(
                            listOf(p.light, p.accent),
                            start = Offset(cx, cy - r * 1.15f),
                            end = Offset(cx, cy),
                        ),
                        cx - r * 0.34f, cy - r * 1.15f,
                        cx + r * 0.34f, cy - r * 0.15f,
                    )
                }
            }
            drawCircle(radBrush(Pal(YELLOW, Color.White, ORANGE, YELLOW), cx, cy, r * 0.4f), r * 0.36f, Offset(cx, cy))
            gloss(cx, cy, r * 0.5f)
        }
        "gem" -> {
            val top = cy - r * 0.75f
            val mid = cy - r * 0.15f
            val bot = cy + r * 1.0f
            val outline = Path().apply {
                moveTo(cx - r * 0.55f, top)
                lineTo(cx + r * 0.55f, top)
                lineTo(cx + r * 0.95f, mid)
                lineTo(cx, bot)
                lineTo(cx - r * 0.95f, mid)
                close()
            }
            drawPath(outline, vBrush(p, cx, cy, r))
            // грани с разной светлотой - хрусталь
            drawPath(
                Path().apply {
                    moveTo(cx - r * 0.55f, top); lineTo(cx - r * 0.25f, mid); lineTo(cx - r * 0.95f, mid); close()
                },
                Color.White.copy(alpha = 0.30f),
            )
            drawPath(
                Path().apply {
                    moveTo(cx + r * 0.55f, top); lineTo(cx + r * 0.25f, mid); lineTo(cx + r * 0.95f, mid); close()
                },
                p.dark.copy(alpha = 0.45f),
            )
            drawPath(
                Path().apply {
                    moveTo(cx - r * 0.25f, mid); lineTo(cx + r * 0.25f, mid); lineTo(cx, bot); close()
                },
                p.light.copy(alpha = 0.35f),
            )
            val tw = (sinF(PI2 * t) + 1f) / 2f
            drawLine(
                Color.White.copy(alpha = 0.5f + 0.5f * tw),
                Offset(cx - r * 0.3f, top + r * 0.18f),
                Offset(cx + r * 0.05f, top + r * 0.18f),
                strokeWidth = w * 0.03f,
            )
        }
        "moon" -> {
            rotate(sinF(PI2 * t) * 12f, Offset(cx, cy)) {
                drawCircle(vBrush(p, cx, cy, r), r * 0.95f, Offset(cx, cy))
                drawCircle(HOLE, r * 0.82f, Offset(cx + r * 0.45f, cy - r * 0.3f))
            }
            val tw = (sinF(PI2 * t) + 1f) / 2f
            val sx = cx - r * 0.85f
            val sy = cy - r * 0.75f
            drawLine(
                YELLOW.copy(alpha = 0.4f + 0.6f * tw),
                Offset(sx - w * 0.07f, sy),
                Offset(sx + w * 0.07f, sy),
                strokeWidth = w * 0.03f,
            )
            drawLine(
                YELLOW.copy(alpha = 0.4f + 0.6f * tw),
                Offset(sx, sy - w * 0.07f),
                Offset(sx, sy + w * 0.07f),
                strokeWidth = w * 0.03f,
            )
        }
        "note" -> {
            val bob = (sinF(PI2 * t) * 0.5f + 0.5f) * 0.14f
            val y = (0.66f - bob) * size.height
            val nx = cx - w * 0.06f
            drawCircle(radBrush(p, nx, y, r * 0.4f), w * 0.12f, Offset(nx, y))
            drawLine(
                p.main,
                Offset(nx + w * 0.11f, y),
                Offset(nx + w * 0.11f, y - size.height * 0.38f),
                strokeWidth = w * 0.045f,
            )
            val flag = Path().apply {
                moveTo(nx + w * 0.11f, y - size.height * 0.38f)
                quadraticBezierTo(nx + w * 0.32f, y - size.height * 0.30f, nx + w * 0.27f, y - size.height * 0.15f)
                lineTo(nx + w * 0.11f, y - size.height * 0.24f)
                close()
            }
            drawPath(flag, Brush.linearGradient(listOf(p.light, p.accent), start = Offset(nx, y - size.height * 0.4f), end = Offset(nx, y)))
            gloss(nx, y, w * 0.14f)
        }
        "balloon" -> {
            val sway = sinF(PI2 * t) * w * 0.07f
            val bx = cx + sway
            drawOval(
                radBrush(p, bx, size.height * 0.3f, r),
                Offset(bx - w * 0.16f, size.height * 0.10f),
                Size(w * 0.32f, size.height * 0.40f),
            )
            val knot = Path().apply {
                moveTo(bx - w * 0.04f, size.height * 0.50f)
                lineTo(bx + w * 0.04f, size.height * 0.50f)
                lineTo(bx, size.height * 0.57f)
                close()
            }
            drawPath(knot, p.dark)
            val string = Path().apply {
                moveTo(bx, size.height * 0.56f)
                quadraticBezierTo(cx - sway, size.height * 0.74f, cx, size.height * 0.90f)
            }
            drawPath(string, GRAY, style = Stroke(width = w * 0.02f))
            gloss(bx, size.height * 0.28f, r * 0.7f)
        }
        "flame" -> {
            val wob = sinF(PI2 * t) * w * 0.05f
            val outer = Path().apply {
                moveTo(cx - w * 0.20f, cy + w * 0.20f)
                quadraticBezierTo(cx - w * 0.22f + wob, cy - w * 0.08f, cx + wob, cy - w * 0.34f)
                quadraticBezierTo(cx + w * 0.22f + wob, cy - w * 0.08f, cx + w * 0.20f, cy + w * 0.20f)
                close()
            }
            drawPath(
                outer,
                Brush.linearGradient(
                    listOf(p.accent, p.main, p.dark),
                    start = Offset(cx, cy - r),
                    end = Offset(cx, cy + r),
                ),
            )
            val inner = Path().apply {
                moveTo(cx - w * 0.10f, cy + w * 0.18f)
                quadraticBezierTo(cx - w * 0.11f - wob * 0.6f, cy + w * 0.02f, cx - wob * 0.6f, cy - w * 0.12f)
                quadraticBezierTo(cx + w * 0.11f - wob * 0.6f, cy + w * 0.02f, cx + w * 0.10f, cy + w * 0.18f)
                close()
            }
            drawPath(inner, YELLOW)
            shimmer(t, cx, cy, r * 0.8f)
        }
    }
}

// ── особые значки ────────────────────────────────────────────────────────────

/** Пузырёк сообщения с тремя точками: дышит, градиентное золото. */
private fun DrawScope.drawChat(t: Float) {
    val r = size.minDimension * (0.30f + 0.02f * sinF(PI2 * t))
    val cx = size.width / 2
    val cy = size.height / 2 - size.height * 0.06f
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFFD87A), GOLD, Color(0xFF9C6B12)),
            center = Offset(cx - r * 0.4f, cy - r * 0.4f),
            radius = r * 2.1f,
        ),
        r,
        Offset(cx, cy),
    )
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
    gloss(cx, cy, r)
}

/** Паровозик крутит колёсами и пускает клубы дыма. */
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
    drawRect(DARK, Offset(left + bodyW * 0.13f, top - h * 0.10f), Size(w * 0.07f, h * 0.12f))
    drawRect(
        Brush.linearGradient(listOf(Color(0xFF7FB2E5), BLUE, Color(0xFF24476F)), start = Offset(left, top), end = Offset(left, top + h * 0.22f)),
        Offset(left, top),
        Size(bodyW * 0.62f, h * 0.22f),
    )
    drawRect(
        Brush.linearGradient(listOf(Color(0xFFFF8A7A), RED, Color(0xFF8E2418)), start = Offset(left, top - h * 0.10f), end = Offset(left, top + h * 0.22f)),
        Offset(left + bodyW * 0.62f, top - h * 0.10f),
        Size(bodyW * 0.38f, h * 0.32f),
    )
    val wy = top + h * 0.22f + h * 0.08f
    for (fx in listOf(0.18f, 0.5f, 0.84f)) {
        val wx = left + bodyW * fx
        drawCircle(DARK, w * 0.08f, Offset(wx, wy))
        rotate(t * 360f, Offset(wx, wy)) {
            drawLine(Color.White, Offset(wx - w * 0.055f, wy), Offset(wx + w * 0.055f, wy), strokeWidth = w * 0.02f)
            drawLine(Color.White, Offset(wx, wy - w * 0.055f), Offset(wx, wy + w * 0.055f), strokeWidth = w * 0.02f)
        }
        drawCircle(Color.White.copy(alpha = 0.7f), w * 0.02f, Offset(wx - w * 0.02f, wy - w * 0.02f))
    }
}

/** Рыбка машет хвостиком и пускает пузырёк. */
private fun DrawScope.drawFish(t: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2 + w * 0.04f
    val cy = h / 2
    val swing = sinF(PI2 * t) * 20f
    rotate(swing, Offset(cx - w * 0.18f, cy)) {
        val tail = Path().apply {
            moveTo(cx - w * 0.16f, cy)
            lineTo(cx - w * 0.36f, cy - h * 0.14f)
            lineTo(cx - w * 0.36f, cy + h * 0.14f)
            close()
        }
        drawPath(tail, ORANGE)
    }
    drawOval(
        Brush.radialGradient(
            listOf(Color(0xFFFFC46B), ORANGE, Color(0xFFA34E08)),
            center = Offset(cx - w * 0.05f, cy - h * 0.08f),
            radius = w * 0.5f,
        ),
        Offset(cx - w * 0.20f, cy - h * 0.15f),
        Size(w * 0.46f, h * 0.30f),
    )
    val fin = Path().apply {
        moveTo(cx - w * 0.02f, cy - h * 0.13f)
        lineTo(cx + w * 0.08f, cy - h * 0.26f)
        lineTo(cx + w * 0.10f, cy - h * 0.10f)
        close()
    }
    drawPath(fin, RED)
    drawCircle(DARK, w * 0.03f, Offset(cx + w * 0.16f, cy - h * 0.04f))
    gloss(cx + w * 0.02f, cy - h * 0.02f, w * 0.16f)
    val p = t % 1f
    drawCircle(
        BLUE.copy(alpha = 0.6f * (1f - p)),
        w * 0.035f,
        Offset(cx + w * 0.30f, cy - h * 0.10f - p * h * 0.25f),
    )
}

/** Шестерёнка крутится, металл с бликом. */
private fun DrawScope.drawGear(t: Float) {
    val w = size.width
    val cx = w / 2
    val cy = size.height / 2
    rotate(t * 180f, Offset(cx, cy)) {
        for (i in 0 until 8) {
            rotate(i * 45f, Offset(cx, cy)) {
                drawRect(
                    Brush.linearGradient(listOf(Color(0xFFC7CEDA), GRAY, Color(0xFF525A68)), start = Offset(cx, cy - w * 0.38f), end = Offset(cx, cy)),
                    Offset(cx - w * 0.055f, cy - w * 0.38f),
                    Size(w * 0.11f, w * 0.14f),
                )
            }
        }
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFC7CEDA), GRAY, Color(0xFF525A68)),
                center = Offset(cx - w * 0.1f, cy - w * 0.1f),
                radius = w * 0.5f,
            ),
            w * 0.27f,
            Offset(cx, cy),
        )
        drawCircle(HOLE, w * 0.12f, Offset(cx, cy))
        gloss(cx, cy, w * 0.3f)
    }
}

/** Солнце с вращающимися лучами и градиентным ядром. */
private fun DrawScope.drawSun(t: Float) {
    val w = size.width
    val cx = w / 2
    val cy = size.height / 2
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
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFFF3B0), YELLOW, ORANGE),
            center = Offset(cx - w * 0.06f, cy - w * 0.06f),
            radius = w * 0.4f,
        ),
        w * 0.18f,
        Offset(cx, cy),
    )
    gloss(cx, cy, w * 0.2f)
}
