package com.vladimir.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.vladimir.messenger.data.story.StoryEars
import com.vladimir.messenger.data.story.StoryEyes
import com.vladimir.messenger.data.story.StoryFaceAnchor
import com.vladimir.messenger.data.story.StoryFaceFilter
import com.vladimir.messenger.data.story.StoryFaceFrame
import com.vladimir.messenger.data.story.StoryFaceGeometry
import com.vladimir.messenger.data.story.StoryFaceLayout
import com.vladimir.messenger.data.story.StoryNose
import kotlin.math.max

/**
 * Рисует AR-фильтр поверх предпросмотра. Координаты лица приходят в пикселях кадра
 * анализа; здесь они переводятся в координаты экрана так же, как PreviewView
 * с FILL_CENTER обрезает картинку, и при фронтальной камере зеркалятся.
 */
@Composable
internal fun StoryFaceOverlay(
    frame: StoryFaceFrame?,
    filter: StoryFaceFilter,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    if (frame == null || !filter.isActive) return
    Canvas(modifier.fillMaxSize()) {
        val map = FrameMap(frame.width, frame.height, size.width, size.height, mirrored)
        for (face in frame.faces) {
            val layout = StoryFaceGeometry.layout(face) ?: continue
            val center = Offset(map.x(layout.centerX), map.y(layout.centerY))
            rotate(StoryFaceGeometry.screenRoll(layout.rollDegrees, mirrored), center) {
                drawFilter(filter, layout, map)
            }
        }
    }
}

/** Перевод пикселей кадра анализа в координаты вида (FILL_CENTER, зеркало для фронталки). */
private class FrameMap(
    frameWidth: Int,
    frameHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float,
    private val mirrored: Boolean,
) {
    private val frameW = frameWidth.toFloat().coerceAtLeast(1f)
    private val frameH = frameHeight.toFloat().coerceAtLeast(1f)
    private val scale = max(viewWidth / frameW, viewHeight / frameH)
    private val dx = (viewWidth - frameW * scale) / 2f
    private val dy = (viewHeight - frameH * scale) / 2f

    fun x(px: Float): Float = (if (mirrored) frameW - px else px) * scale + dx
    fun y(py: Float): Float = py * scale + dy
    fun len(v: Float): Float = v * scale
}

private fun DrawScope.drawFilter(filter: StoryFaceFilter, layout: StoryFaceLayout, map: FrameMap) {
    // Уши и нос рисуем в «голове», глаза поверх — чтобы зрачки не уезжали под ухо.
    drawEars(filter.ears, layout.leftEar, map, left = true)
    drawEars(filter.ears, layout.rightEar, map, left = false)
    drawNose(filter.nose, layout.nose, map)
    drawEyes(filter.eyes, layout.leftEye, map)
    drawEyes(filter.eyes, layout.rightEye, map)
}

// ─────────────────────────── уши ───────────────────────────

private fun DrawScope.drawEars(style: StoryEars, anchor: StoryFaceAnchor, map: FrameMap, left: Boolean) {
    if (style == StoryEars.NONE) return
    val x = map.x(anchor.x)
    val y = map.y(anchor.y)
    val s = map.len(anchor.size)
    // Левое и правое ухо зеркальны по горизонтали, чтобы кончики смотрели наружу.
    val side = if (left) -1f else 1f
    when (style) {
        StoryEars.CAT -> {
            triangle(x, y, s * 0.95f, s * 0.95f, side * s * 0.05f, Color(0xFF3B3335), up = true)
            triangle(x, y + s * 0.12f, s * 0.55f, s * 0.6f, side * s * 0.05f, Color(0xFFFFB3C1), up = true)
        }
        StoryEars.FOX -> {
            triangle(x, y, s * 1.0f, s * 1.2f, side * s * 0.08f, Color(0xFFE8833A), up = true)
            triangle(x, y + s * 0.2f, s * 0.42f, s * 0.5f, side * s * 0.08f, Color(0xFFFFF4E6), up = true)
        }
        StoryEars.DOG -> {
            // Висячее ухо: овал ниже и наружу от головы.
            oval(x + side * s * 0.12f, y + s * 0.42f, s * 0.7f, s * 1.1f, Color(0xFF8B5A2B))
            oval(x + side * s * 0.12f, y + s * 0.5f, s * 0.36f, s * 0.7f, Color(0xFFB07D4A))
        }
        StoryEars.BUNNY -> {
            // Длинное заячье ухо торчит вверх над головой.
            oval(x, y - s * 0.35f, s * 0.46f, s * 1.45f, Color(0xFFF5F0EA))
            oval(x, y - s * 0.35f, s * 0.24f, s * 1.2f, Color(0xFFFFB3C1))
        }
        StoryEars.BEAR -> {
            circle(x, y, s * 0.36f, Color(0xFF7A4A24))
            circle(x, y, s * 0.18f, Color(0xFFC9955E))
        }
        StoryEars.NONE -> Unit
    }
}

// ─────────────────────────── нос ───────────────────────────

private fun DrawScope.drawNose(style: StoryNose, anchor: StoryFaceAnchor, map: FrameMap) {
    if (style == StoryNose.NONE) return
    val x = map.x(anchor.x)
    val y = map.y(anchor.y)
    val s = map.len(anchor.size)
    when (style) {
        StoryNose.CAT -> triangle(x, y, s * 1.0f, s * 0.68f, 0f, Color(0xFFFF8FA3), up = false)
        StoryNose.DOG -> {
            oval(x, y, s * 1.0f, s * 0.72f, Color(0xFF1E1A1C))
            circle(x - s * 0.18f, y - s * 0.12f, s * 0.07f, Color.White.copy(alpha = 0.7f))
        }
        StoryNose.PIG -> {
            oval(x, y, s * 1.0f, s * 0.72f, Color(0xFFFFA9B8))
            circle(x - s * 0.16f, y, s * 0.08f, Color(0xFF8A3A4A))
            circle(x + s * 0.16f, y, s * 0.08f, Color(0xFF8A3A4A))
        }
        StoryNose.BEAR -> oval(x, y, s * 0.92f, s * 0.66f, Color(0xFF3A2416))
        StoryNose.RABBIT -> {
            oval(x, y, s * 0.62f, s * 0.46f, Color(0xFFFF9DB0))
        }
        StoryNose.NONE -> Unit
    }
}

// ─────────────────────────── глаза ───────────────────────────

/** [anchor.size] — диаметр глаза в пикселях кадра. */
private fun DrawScope.drawEyes(style: StoryEyes, anchor: StoryFaceAnchor, map: FrameMap) {
    if (style == StoryEyes.NONE) return
    val x = map.x(anchor.x)
    val y = map.y(anchor.y)
    val d = map.len(anchor.size)
    when (style) {
        StoryEyes.CAT -> {
            circle(x, y, d * 0.55f, Color.White)
            circle(x, y, d * 0.42f, Color(0xFF9ACD4B))
            oval(x, y, d * 0.1f, d * 0.7f, Color(0xFF101010))
        }
        StoryEyes.OWL -> {
            circle(x, y, d * 0.62f, Color.White)
            circle(x, y, d * 0.46f, Color(0xFFFFC53D))
            circle(x, y, d * 0.24f, Color(0xFF101010))
        }
        StoryEyes.PANDA -> {
            oval(x, y, d * 1.05f, d * 0.9f, Color(0xFF141414))
            circle(x, y, d * 0.32f, Color.White)
            circle(x, y, d * 0.16f, Color(0xFF101010))
        }
        StoryEyes.FROG -> {
            circle(x, y, d * 0.62f, Color(0xFF5DBB63))
            circle(x, y, d * 0.3f, Color.White)
            circle(x, y, d * 0.16f, Color(0xFF101010))
        }
        StoryEyes.NONE -> Unit
    }
}

// ─────────────────────────── примитивы ───────────────────────────

private fun DrawScope.circle(cx: Float, cy: Float, r: Float, color: Color) {
    drawCircle(color = color, radius = r, center = Offset(cx, cy))
}

private fun DrawScope.oval(cx: Float, cy: Float, w: Float, h: Float, color: Color) {
    drawOval(color = color, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h))
}

/** Треугольник с основанием по центру (x, y). up=true — вершина сверху, иначе снизу. */
private fun DrawScope.triangle(cx: Float, cy: Float, w: Float, h: Float, tipShift: Float, color: Color, up: Boolean) {
    val path = Path().apply {
        if (up) {
            moveTo(cx - w / 2f, cy)
            lineTo(cx + tipShift, cy - h)
            lineTo(cx + w / 2f, cy)
        } else {
            moveTo(cx - w / 2f, cy - h / 2f)
            lineTo(cx + w / 2f, cy - h / 2f)
            lineTo(cx + tipShift, cy + h / 2f)
        }
        close()
    }
    drawPath(path, color)
}
