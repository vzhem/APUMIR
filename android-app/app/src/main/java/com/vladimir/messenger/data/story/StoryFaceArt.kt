package com.vladimir.messenger.data.story

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Рисование AR-фильтров: одна и та же «картинка» уходит и на экран (Compose),
 * и в сохранённый файл (android.graphics.Canvas), поэтому они совпадают.
 * Цвета — ARGB в Long, как у Compose Color. Картинки — PNG из res/drawable-nodpi,
 * если картинки нет, рисуем простые фигуры, чтобы фильтр не пропадал.
 */
internal interface StoryFaceCanvas {
    /** Овал по центру (cx, cy) шириной [w] и высотой [h]. */
    fun oval(cx: Float, cy: Float, w: Float, h: Float, argb: Long)
    fun circle(cx: Float, cy: Float, r: Float, argb: Long)
    fun triangle(cx: Float, cy: Float, w: Float, h: Float, tipShift: Float, argb: Long, up: Boolean)

    /** Рисует [block] в системе координат, повернутой на [degrees] вокруг (cx, cy). */
    fun rotated(degrees: Float, cx: Float, cy: Float, block: () -> Unit)

    /** Пропорция высота/ширина картинки по имени или null, если картинки нет. */
    fun spriteAspect(name: String): Float?

    /** Картинка по центру (cx, cy) шириной [width], с зеркалом и поворотом вокруг своего центра. */
    fun sprite(name: String, cx: Float, cy: Float, width: Float, flipX: Boolean, rotationDegrees: Float)
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

/**
 * Рисует все лица кадра выбранным фильтром. Пустой фильтр ничего не рисует.
 * [timeMs] — время для движения (шерсть, дыхание носа, блик). В превью это время экрана,
 * при записи — время кадра, поэтому анимация одинаково идёт и там и там.
 */
internal fun drawStoryFaces(
    canvas: StoryFaceCanvas,
    frame: StoryFaceFrame,
    filter: StoryFaceFilter,
    transform: StoryFaceTransform,
    timeMs: Long,
) {
    if (!filter.isActive) return
    for (face in frame.faces) {
        val layout = StoryFaceGeometry.layout(face) ?: continue
        val roll = StoryFaceGeometry.screenRoll(layout.rollDegrees, transform.mirrored)
        canvas.rotated(roll, transform.x(layout.centerX), transform.y(layout.centerY)) {
            // Порядок снизу вверх: румянец под губами, брови над глазами, глаза сверху.
            drawHair(canvas, filter.hair, layout.hair, transform, timeMs)
            drawBeard(canvas, filter.beard, layout.beard, transform)
            drawCheeks(canvas, filter.cheeks, layout.leftCheek, layout.rightCheek, transform)
            drawLips(canvas, filter.lips, layout.mouth, transform)
            drawMustache(canvas, filter.mustache, layout.mustache, transform)
            drawWhiskers(canvas, filter.whiskers, layout.nose, transform, timeMs)
            drawNose(canvas, filter.nose, layout.nose, transform, timeMs)
            drawEars(canvas, filter.ears, layout.leftEar, transform, left = true, timeMs = timeMs)
            drawEars(canvas, filter.ears, layout.rightEar, transform, left = false, timeMs = timeMs)
            drawBrows(canvas, filter.brows, layout.leftBrow, layout.rightBrow, transform)
            drawEyes(canvas, filter.eyes, layout.leftEye, transform, timeMs)
            drawEyes(canvas, filter.eyes, layout.rightEye, transform, timeMs)
        }
    }
}

/** Человеческие усы: над губами, поверх губ. Центр картинки на линии над ртом. */
private fun drawMustache(
    canvas: StoryFaceCanvas,
    style: StoryMustache,
    anchor: StoryFaceAnchor,
    t: StoryFaceTransform,
) {
    val name = when (style) {
        StoryMustache.NONE -> return
        StoryMustache.PENCIL -> "mustache_pencil"
        StoryMustache.HANDLEBAR -> "mustache_handlebar"
        StoryMustache.WALRUS -> "mustache_walrus"
        StoryMustache.CHEVRON -> "mustache_chevron"
        StoryMustache.HORSESHOE -> "mustache_horseshoe"
        StoryMustache.FU -> "mustache_fu"
        StoryMustache.IMPERIAL -> "mustache_imperial"
    }
    if (canvas.spriteAspect(name) == null) return
    canvas.sprite(name, t.x(anchor.x), t.y(anchor.y), t.len(anchor.size), flipX = false, rotationDegrees = 0f)
}

/** Борода: картинка под носом, верх чуть выше линии рта, чтобы губы легли поверх. */
private fun drawBeard(
    canvas: StoryFaceCanvas,
    style: StoryBeard,
    anchor: StoryFaceAnchor,
    t: StoryFaceTransform,
) {
    val name = when (style) {
        StoryBeard.NONE -> return
        StoryBeard.STUBBLE -> "beard_stubble"
        StoryBeard.FULL -> "beard_full"
        StoryBeard.GOATEE -> "beard_goatee"
        StoryBeard.VANDYKE -> "beard_vandyke"
        StoryBeard.SIDEBURNS -> "beard_sideburns"
        StoryBeard.LONG -> "beard_long"
        StoryBeard.NEAT -> "beard_neat"
        StoryBeard.CHINSTRAP -> "beard_chinstrap"
    }
    val aspect = canvas.spriteAspect(name) ?: return
    val width = t.len(anchor.size)
    val height = width * aspect
    // Отступ сверху 35% высоты: рот картинки попадает на линию губ.
    val top = t.y(anchor.y) - height * 0.35f
    canvas.sprite(name, t.x(anchor.x), top + height / 2f, width, flipX = false, rotationDegrees = 0f)
}

/** Прическа: верх картинки у верхнего края головы, пряди свисают. Лёгкое покачивание. */
private fun drawHair(
    canvas: StoryFaceCanvas,
    style: StoryHair,
    anchor: StoryFaceAnchor,
    t: StoryFaceTransform,
    timeMs: Long,
) {
    val name = when (style) {
        StoryHair.NONE -> return
        StoryHair.CURLY -> "hair_curly"
        StoryHair.WAVY -> "hair_wavy"
        StoryHair.BOB -> "hair_bob"
        StoryHair.BUN -> "hair_bun"
        StoryHair.PONYTAIL -> "hair_ponytail"
        StoryHair.BRAIDS -> "hair_braids"
        StoryHair.CROP -> "hair_crop"
        StoryHair.AFRO -> "hair_afro"
        StoryHair.LONG -> "hair_long"
    }
    val aspect = canvas.spriteAspect(name) ?: return
    val width = t.len(anchor.size)
    val height = width * aspect
    val sway = StoryFaceMotion.whiskerSwayDegrees(timeMs) * 0.5f
    canvas.sprite(name, t.x(anchor.x), t.y(anchor.y) + height / 2f, width, flipX = false, rotationDegrees = sway)
}

private fun drawCheeks(
    canvas: StoryFaceCanvas,
    style: StoryCheeks,
    left: StoryFaceAnchor,
    right: StoryFaceAnchor,
    t: StoryFaceTransform,
) {
    if (style == StoryCheeks.NONE) return
    val name = when (style) {
        StoryCheeks.NONE -> return
        StoryCheeks.BLUSH -> "cheek"
        StoryCheeks.FRECKLES -> "cheek_freckles"
        StoryCheeks.CONTOUR -> "cheek_contour"
        StoryCheeks.GLOW -> "cheek_glow"
    }
    if (canvas.spriteAspect(name) != null) {
        for (cheek in listOf(left, right)) {
            canvas.sprite(name, t.x(cheek.x), t.y(cheek.y), t.len(cheek.size) * 3.2f, flipX = false, rotationDegrees = 0f)
        }
        return
    }
    if (style != StoryCheeks.BLUSH) return
    // Запасной вариант без картинки: мягкие круги с спадом.
    for (cheek in listOf(left, right)) {
        val x = t.x(cheek.x)
        val y = t.y(cheek.y)
        val r = t.len(cheek.size)
        canvas.circle(x, y, r, 0x26FF8FA3L)
        canvas.circle(x, y, r * 0.7f, 0x33FF8FA3L)
        canvas.circle(x, y, r * 0.4f, 0x40FF8FA3L)
    }
}

private fun drawLips(canvas: StoryFaceCanvas, style: StoryLips, mouth: StoryFaceAnchor, t: StoryFaceTransform) {
    val name = when (style) {
        StoryLips.NONE -> return
        StoryLips.RED -> "lips_red"
        StoryLips.PINK -> "lips_pink"
        StoryLips.NUDE -> "lips_nude"
        StoryLips.BERRY -> "lips_berry"
        StoryLips.CORAL -> "lips_coral"
        StoryLips.ROSE -> "lips_rose"
        StoryLips.MAUVE -> "lips_mauve"
    }
    val x = t.x(mouth.x)
    val y = t.y(mouth.y)
    val width = t.len(mouth.size) * 1.25f
    if (canvas.spriteAspect(name) != null) {
        canvas.sprite(name, x, y, width, flipX = false, rotationDegrees = 0f)
    } else {
        val color = if (style == StoryLips.RED) 0xCCD32F4FL else 0xCCF28BB0L
        canvas.oval(x, y, width, width * 0.24f, color)
    }
}

private fun drawWhiskers(
    canvas: StoryFaceCanvas,
    style: StoryWhiskers,
    nose: StoryFaceAnchor,
    t: StoryFaceTransform,
    timeMs: Long,
) {
    val name = when (style) {
        StoryWhiskers.NONE -> return
        StoryWhiskers.CAT -> "whiskers"
        StoryWhiskers.DOG -> "whiskers_dog"
        StoryWhiskers.RABBIT -> "whiskers_rabbit"
        StoryWhiskers.FOX -> "whiskers_fox"
        StoryWhiskers.SEAL -> "whiskers_seal"
        StoryWhiskers.LYNX -> "whiskers_lynx"
    }
    val size = t.len(nose.size)
    val x = t.x(nose.x)
    val y = t.y(nose.y) + size * 0.1f
    val visualWidth = size * 1.8f
    val gap = size * 0.55f
    val sway = StoryFaceMotion.whiskerSwayDegrees(timeMs)
    val aspect = canvas.spriteAspect(name)
    if (aspect != null) {
        // Картинка — веер с основанием внизу. Поворачиваем на 90°, чтобы веер смотрел в стороны
        // от носа. Левые усы: против часовой, правые: по часовой. Колышутся в противофазе.
        val spriteWidth = visualWidth / aspect
        canvas.sprite(name, x - gap - visualWidth / 2f, y, spriteWidth, flipX = false, rotationDegrees = -90f + sway)
        canvas.sprite(name, x + gap + visualWidth / 2f, y, spriteWidth, flipX = false, rotationDegrees = 90f - sway)
    } else {
        for (side in listOf(-1f, 1f)) {
            for (k in -1..1) {
                canvas.oval(x + side * (gap + visualWidth / 2f), y + k * size * 0.08f, visualWidth, size * 0.02f, 0xE6FFFFFFL)
            }
        }
    }
}

private fun drawNose(
    canvas: StoryFaceCanvas,
    style: StoryNose,
    nose: StoryFaceAnchor,
    t: StoryFaceTransform,
    timeMs: Long,
) {
    val name = when (style) {
        StoryNose.NONE -> return
        StoryNose.CAT -> "nose_cat"
        StoryNose.DOG -> "nose_dog"
        StoryNose.PIG -> "nose_pig"
        StoryNose.BEAR -> "nose_bear"
        StoryNose.RABBIT -> "nose_rabbit"
        StoryNose.PANDA -> "nose_panda"
        StoryNose.TIGER -> "nose_tiger"
        StoryNose.MOUSE -> "nose_mouse"
        StoryNose.LION -> "nose_lion"
        StoryNose.HORSE -> "nose_horse"
        StoryNose.FOX -> "nose_fox"
        StoryNose.WOLF -> "nose_wolf"
    }
    val x = t.x(nose.x)
    val y = t.y(nose.y)
    // Нос дышит и шмыгает: масштаб меняется во времени.
    val size = t.len(nose.size) * StoryFaceMotion.noseScale(timeMs)
    if (canvas.spriteAspect(name) != null) {
        canvas.sprite(name, x, y, size * 1.1f, flipX = false, rotationDegrees = 0f)
    } else {
        canvas.oval(x, y, size, size * 0.72f, 0xFFF48FB1L)
    }
}

private fun drawEars(
    canvas: StoryFaceCanvas,
    style: StoryEars,
    anchor: StoryFaceAnchor,
    t: StoryFaceTransform,
    left: Boolean,
    timeMs: Long,
) {
    val name = when (style) {
        StoryEars.NONE -> return
        StoryEars.CAT -> "ear_cat"
        StoryEars.DOG -> "ear_dog"
        StoryEars.BUNNY -> "ear_bunny"
        StoryEars.BEAR -> "ear_bear"
        StoryEars.FOX -> "ear_fox"
        StoryEars.PANDA -> "ear_panda"
        StoryEars.TIGER -> "ear_tiger"
        StoryEars.MOUSE -> "ear_mouse"
        StoryEars.DEER -> "ear_deer"
        StoryEars.LYNX -> "ear_lynx"
        StoryEars.HORSE -> "ear_horse"
        StoryEars.LOP -> "ear_lop"
    }
    val x = t.x(anchor.x)
    val y = t.y(anchor.y)
    val size = t.len(anchor.size)
    // Шерсть качается: левое и правое ухо в разной фазе.
    val sway = StoryFaceMotion.furSwayDegrees(timeMs, phase = if (left) 0.0 else 1.3)
    if (canvas.spriteAspect(name) != null) {
        canvas.sprite(name, x, y, size, flipX = left, rotationDegrees = sway)
    } else {
        canvas.triangle(x, y, size * 0.6f, size * 0.7f, if (left) -size * 0.1f else size * 0.1f, 0xFFE0A070L, up = true)
    }
}

private fun drawBrows(
    canvas: StoryFaceCanvas,
    style: StoryBrows,
    left: StoryFaceAnchor,
    right: StoryFaceAnchor,
    t: StoryFaceTransform,
) {
    if (style == StoryBrows.NONE) return
    val name = when (style) {
        StoryBrows.NONE -> return
        StoryBrows.BUSHY -> "brow"
        StoryBrows.THIN -> "brow_thin"
        StoryBrows.ARCHED -> "brow_arched"
        StoryBrows.ANGLED -> "brow_angled"
        StoryBrows.STRAIGHT -> "brow_straight"
        StoryBrows.SOFT -> "brow_soft"
    }
    if (canvas.spriteAspect(name) != null) {
        canvas.sprite(name, t.x(left.x), t.y(left.y), t.len(left.size), flipX = true, rotationDegrees = 0f)
        canvas.sprite(name, t.x(right.x), t.y(right.y), t.len(right.size), flipX = false, rotationDegrees = 0f)
    } else {
        val thickness = if (style == StoryBrows.THIN) 0.06f else 0.14f
        for (anchor in listOf(left, right)) {
            canvas.oval(t.x(anchor.x), t.y(anchor.y), t.len(anchor.size), t.len(anchor.size) * thickness, 0xFF3E2A1AL)
        }
    }
}

private fun drawEyes(
    canvas: StoryFaceCanvas,
    style: StoryEyes,
    anchor: StoryFaceAnchor,
    t: StoryFaceTransform,
    timeMs: Long,
) {
    val name = when (style) {
        StoryEyes.NONE -> return
        StoryEyes.CAT -> "eye_cat"
        StoryEyes.OWL -> "eye_owl"
        StoryEyes.PANDA -> "eye_panda"
        StoryEyes.FROG -> "eye_frog"
        StoryEyes.TIGER -> "eye_tiger"
        StoryEyes.FOX -> "eye_fox"
        StoryEyes.DOG -> "eye_dog"
        StoryEyes.WOLF -> "eye_wolf"
        StoryEyes.LYNX -> "eye_lynx"
        StoryEyes.HORSE -> "eye_horse"
        StoryEyes.HUMAN_BROWN -> "eye_human_brown"
        StoryEyes.HUMAN_BLUE -> "eye_human_blue"
        StoryEyes.HUMAN_GREEN -> "eye_human_green"
        StoryEyes.HUMAN_HAZEL -> "eye_human_hazel"
        StoryEyes.HUMAN_GREY -> "eye_human_grey"
    }
    val x = t.x(anchor.x)
    val y = t.y(anchor.y)
    val d = t.len(anchor.size)
    if (canvas.spriteAspect(name) != null) {
        canvas.sprite(name, x, y, d * 1.15f, flipX = false, rotationDegrees = 0f)
    } else {
        canvas.oval(x, y, d, d * 0.8f, 0xFF2B2B2BL)
    }
    // Блик: белый круг в верхнем-левом углу и маленькая точка, блик чуть «плывёт».
    val glint = StoryFaceMotion.glintOffset(timeMs)
    val gx = x - d * 0.18f
    val gy = y - d * (0.2f - glint)
    canvas.circle(gx, gy, d * 0.1f, 0xE6FFFFFFL)
    canvas.circle(gx - d * 0.04f, gy - d * 0.06f, d * 0.04f, 0xFFFFFFFFL)
}

/** Реализация на android.graphics.Canvas — для записи файла. [bitmapOf] даёт картинку по имени. */
internal class AndroidStoryFaceCanvas(
    private val canvas: android.graphics.Canvas,
    private val bitmapOf: (String) -> android.graphics.Bitmap?,
) : StoryFaceCanvas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun spriteAspect(name: String): Float? {
        val bitmap = bitmapOf(name) ?: return null
        return bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1).toFloat()
    }

    override fun sprite(name: String, cx: Float, cy: Float, width: Float, flipX: Boolean, rotationDegrees: Float) {
        val bitmap = bitmapOf(name) ?: return
        val height = width * bitmap.height / bitmap.width.coerceAtLeast(1).toFloat()
        val dst = RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
        canvas.save()
        try {
            if (rotationDegrees != 0f) canvas.rotate(rotationDegrees, cx, cy)
            if (flipX) canvas.scale(-1f, 1f, cx, cy)
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        } finally {
            canvas.restore()
        }
    }

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
