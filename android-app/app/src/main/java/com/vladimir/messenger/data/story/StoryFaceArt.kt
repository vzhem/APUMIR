package com.vladimir.messenger.data.story

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Рисование AR-фильтров: одна и та же «картинка» уходит и на экран (Compose),
 * и в сохранённый файл (android.graphics.Canvas), поэтому они совпадают.
 * Цвета — ARGB в Long, как у Compose Color.
 */
internal interface StoryFaceCanvas {
    fun oval(cx: Float, cy: Float, w: Float, h: Float, argb: Long)
    fun circle(cx: Float, cy: Float, r: Float, argb: Long)
    fun triangle(cx: Float, cy: Float, w: Float, h: Float, tipShift: Float, argb: Long, up: Boolean)

    /** Рисует [block] повернутым на [degrees] вокруг точки (cx, cy). */
    fun rotated(degrees: Float, cx: Float, cy: Float, block: () -> Unit)
}

/**
 * Перевод координат кадра анализа в координаты цели рисования.
 * [fillCenter] повторяет обрезку PreviewView (FILL_CENTER), [identity] — запись 1:1.
 */
internal class StoryFaceTransform private constructor(
    private val frameWidth: Float,
    private val scale: Float,
    private val dx: Float,
    private val dy: Float,
    val mirrored: Boolean,
) {
    fun x(px: Float): Float = (if (mirrored) frameWidth - px else px) * scale + dx
    fun y(py: Float): Float = py * scale + dy
    fun len(value: Float): Float = value * scale

    companion object {
        fun fillCenter(frameWidth: Int, frameHeight: Int, viewWidth: Float, viewHeight: Float, mirrored: Boolean): StoryFaceTransform {
            val fw = frameWidth.toFloat().coerceAtLeast(1f)
            val fh = frameHeight.toFloat().coerceAtLeast(1f)
            val scale = maxOf(viewWidth / fw, viewHeight / fh)
            return StoryFaceTransform(
                frameWidth = fw,
                scale = scale,
                dx = (viewWidth - fw * scale) / 2f,
                dy = (viewHeight - fh * scale) / 2f,
                mirrored = mirrored,
            )
        }

        /** Кадр записи 1:1: зеркала нет, масштаб единичный. */
        fun identity(frameWidth: Int): StoryFaceTransform =
            StoryFaceTransform(frameWidth.toFloat().coerceAtLeast(1f), 1f, 0f, 0f, mirrored = false)
    }
}

/** Рисует все лица кадра выбранным фильтром. Пустой фильтр ничего не рисует. */
internal fun drawStoryFaces(canvas: StoryFaceCanvas, frame: StoryFaceFrame, filter: StoryFaceFilter, transform: StoryFaceTransform) {
    if (!filter.isActive) return
    for (face in frame.faces) {
        val layout = StoryFaceGeometry.layout(face) ?: continue
        val roll = StoryFaceGeometry.screenRoll(layout.rollDegrees, transform.mirrored)
        canvas.rotated(roll, transform.x(layout.centerX), transform.y(layout.centerY)) {
            drawEars(canvas, filter.ears, layout.leftEar, transform, left = true)
            drawEars(canvas, filter.ears, layout.rightEar, transform, left = false)
            drawNose(canvas, filter.nose, layout.nose, transform)
            // Глаза поверх ушей и носа, чтобы зрачки не уходили под другие детали.
            drawEyes(canvas, filter.eyes, layout.leftEye, transform)
            drawEyes(canvas, filter.eyes, layout.rightEye, transform)
        }
    }
}

// ─────────────────────────── уши ───────────────────────────

private fun drawEars(canvas: StoryFaceCanvas, style: StoryEars, anchor: StoryFaceAnchor, t: StoryFaceTransform, left: Boolean) {
    if (style == StoryEars.NONE) return
    val x = t.x(anchor.x)
    val y = t.y(anchor.y)
    val s = t.len(anchor.size)
    // Левое и правое ухо зеркальны по горизонтали, чтобы кончики смотрели наружу.
    val side = if (left) -1f else 1f
    when (style) {
        StoryEars.CAT -> {
            canvas.triangle(x, y, s * 0.95f, s * 0.95f, side * s * 0.05f, 0xFF3B3335, up = true)
            canvas.triangle(x, y + s * 0.12f, s * 0.55f, s * 0.6f, side * s * 0.05f, 0xFFFFB3C1, up = true)
        }
        StoryEars.FOX -> {
            canvas.triangle(x, y, s * 1.0f, s * 1.2f, side * s * 0.08f, 0xFFE8833A, up = true)
            canvas.triangle(x, y + s * 0.2f, s * 0.42f, s * 0.5f, side * s * 0.08f, 0xFFFFF4E6, up = true)
        }
        StoryEars.DOG -> {
            // Висячее ухо: овал ниже и наружу от головы.
            canvas.oval(x + side * s * 0.12f, y + s * 0.42f, s * 0.7f, s * 1.1f, 0xFF8B5A2B)
            canvas.oval(x + side * s * 0.12f, y + s * 0.5f, s * 0.36f, s * 0.7f, 0xFFB07D4A)
        }
        StoryEars.BUNNY -> {
            // Длинное заячье ухо торчит вверх над головой.
            canvas.oval(x, y - s * 0.35f, s * 0.46f, s * 1.45f, 0xFFF5F0EA)
            canvas.oval(x, y - s * 0.35f, s * 0.24f, s * 1.2f, 0xFFFFB3C1)
        }
        StoryEars.BEAR -> {
            canvas.circle(x, y, s * 0.36f, 0xFF7A4A24)
            canvas.circle(x, y, s * 0.18f, 0xFFC9955E)
        }
        StoryEars.NONE -> Unit
    }
}

// ─────────────────────────── нос ───────────────────────────

private fun drawNose(canvas: StoryFaceCanvas, style: StoryNose, anchor: StoryFaceAnchor, t: StoryFaceTransform) {
    if (style == StoryNose.NONE) return
    val x = t.x(anchor.x)
    val y = t.y(anchor.y)
    val s = t.len(anchor.size)
    when (style) {
        StoryNose.CAT -> canvas.triangle(x, y, s * 1.0f, s * 0.68f, 0f, 0xFFFF8FA3, up = false)
        StoryNose.DOG -> {
            canvas.oval(x, y, s * 1.0f, s * 0.72f, 0xFF1E1A1C)
            canvas.circle(x - s * 0.18f, y - s * 0.12f, s * 0.07f, 0xB3FFFFFF)
        }
        StoryNose.PIG -> {
            canvas.oval(x, y, s * 1.0f, s * 0.72f, 0xFFFFA9B8)
            canvas.circle(x - s * 0.16f, y, s * 0.08f, 0xFF8A3A4A)
            canvas.circle(x + s * 0.16f, y, s * 0.08f, 0xFF8A3A4A)
        }
        StoryNose.BEAR -> canvas.oval(x, y, s * 0.92f, s * 0.66f, 0xFF3A2416)
        StoryNose.RABBIT -> canvas.oval(x, y, s * 0.62f, s * 0.46f, 0xFFFF9DB0)
        StoryNose.NONE -> Unit
    }
}

// ─────────────────────────── глаза ───────────────────────────

/** [anchor.size] — диаметр глаза в пикселях кадра анализа. */
private fun drawEyes(canvas: StoryFaceCanvas, style: StoryEyes, anchor: StoryFaceAnchor, t: StoryFaceTransform) {
    if (style == StoryEyes.NONE) return
    val x = t.x(anchor.x)
    val y = t.y(anchor.y)
    val d = t.len(anchor.size)
    when (style) {
        StoryEyes.CAT -> {
            canvas.circle(x, y, d * 0.55f, 0xFFFFFFFF)
            canvas.circle(x, y, d * 0.42f, 0xFF9ACD4B)
            canvas.oval(x, y, d * 0.1f, d * 0.7f, 0xFF101010)
        }
        StoryEyes.OWL -> {
            canvas.circle(x, y, d * 0.62f, 0xFFFFFFFF)
            canvas.circle(x, y, d * 0.46f, 0xFFFFC53D)
            canvas.circle(x, y, d * 0.24f, 0xFF101010)
        }
        StoryEyes.PANDA -> {
            canvas.oval(x, y, d * 1.05f, d * 0.9f, 0xFF141414)
            canvas.circle(x, y, d * 0.32f, 0xFFFFFFFF)
            canvas.circle(x, y, d * 0.16f, 0xFF101010)
        }
        StoryEyes.FROG -> {
            canvas.circle(x, y, d * 0.62f, 0xFF5DBB63)
            canvas.circle(x, y, d * 0.3f, 0xFFFFFFFF)
            canvas.circle(x, y, d * 0.16f, 0xFF101010)
        }
        StoryEyes.NONE -> Unit
    }
}

/** Реализация на android.graphics.Canvas — для записи файла. */
internal class AndroidStoryFaceCanvas(private val canvas: android.graphics.Canvas) : StoryFaceCanvas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun oval(cx: Float, cy: Float, w: Float, h: Float, argb: Long) {
        paint.color = argb.toInt()
        canvas.drawOval(RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), paint)
    }

    override fun circle(cx: Float, cy: Float, r: Float, argb: Long) {
        paint.color = argb.toInt()
        canvas.drawCircle(cx, cy, r, paint)
    }

    override fun triangle(cx: Float, cy: Float, w: Float, h: Float, tipShift: Float, argb: Long, up: Boolean) {
        paint.color = argb.toInt()
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
        canvas.drawPath(path, paint)
    }

    override fun rotated(degrees: Float, cx: Float, cy: Float, block: () -> Unit) {
        canvas.save()
        try {
            canvas.rotate(degrees, cx, cy)
            block()
        } finally {
            canvas.restore()
        }
    }
}
