package com.vladimir.messenger.data.story

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Рецепт кадрирования фото истории (владелец 2026-10-15: «обрезать её как хочешь,
 * увеличить и другие функции как в топовых мессенджерах»).
 *
 * Вся геометрия собрана в одном месте и используется ДВАЖДЫ: предпросмотр рисует
 * снимок этой же матрицей, а публикация рендерит ею же кадр в отдельный битмап.
 * Поэтому «что видно в рамке — то и уехало друзьям» выполняется не на словах, а
 * по построению: второй путь отрисовки не завести, не сломав гейт
 * `scripts/ci/check-story-crop.py` и двойник `tools/sandbox/story_crop_reference.py`.
 *
 * Конвенции (двойник повторяет их до последнего знака):
 *  * единица длины — высота кадра истории ([STORY_ASPECT], тот же 360x640, что у
 *    `StoryContentCanvas`), ось Y вниз;
 *  * [Frame] — рамка обрезки в долях кадра истории, свободная рамка задаётся
 *    уголками и не обязана совпадать ни с одной пропорцией;
 *  * [State.quarter] — четверти оборота по часовой стрелке, [State.straighten] —
 *    доворот ±45° вокруг центра рамки;
 *  * [State.zoom] ≥ 1 — увеличение сверх минимального, при котором в рамке нет
 *    пустых углов (при повороте это увеличение считается само);
 *  * [State.panX]/[State.panY] — сдвиг снимка в долях размера рамки.
 */
object StoryCrop {

    /** Пропорция кадра истории: 360x640 у `StoryContentCanvas`. */
    const val STORY_ASPECT = 360f / 640f

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 8f

    /** Выравнивание ползунком: ±45°, дальше — уже поворот на четверть. */
    const val STRAIGHTEN_LIMIT = 45f

    /** Минимальная сторона рамки: мельче палец «схлопывает» кадр в точку. */
    const val MIN_FRAME = 0.12f

    /** Потолок кадра для публикации: дальше сжимает существующая лестница. */
    const val MAX_SIDE = 1280
    private const val MIN_SIDE = 64

    const val ASPECT_ORIGINAL = "original"
    const val ASPECT_FREE = "free"
    const val ASPECT_SQUARE = "1:1"
    const val ASPECT_PORTRAIT = "4:5"
    const val ASPECT_STORY = "9:16"
    const val ASPECT_WIDE = "16:9"

    /** Порядок чипов в редакторе: сперва «как сняли», потом готовые пропорции. */
    val aspectPresets = listOf(
        ASPECT_ORIGINAL,
        ASPECT_SQUARE,
        ASPECT_PORTRAIT,
        ASPECT_STORY,
        ASPECT_WIDE,
    )

    /** Пропорция готового чипа; у «Свободного» её нет. */
    fun presetAspect(aspect: String): Float? = when (aspect) {
        ASPECT_SQUARE -> 1f
        ASPECT_PORTRAIT -> 4f / 5f
        ASPECT_STORY -> STORY_ASPECT
        ASPECT_WIDE -> 16f / 9f
        else -> null
    }

    /** Рамка обрезки в долях кадра истории. */
    data class Frame(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** Ширина рамки в единицах «высота кадра = 1». */
        val widthUnits: Float get() = width * STORY_ASPECT
        val heightUnits: Float get() = height

        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        /** Пропорция кадра: ширина к высоте. */
        val aspect: Float get() = if (heightUnits > 0f) widthUnits / heightUnits else 1f
    }

    val FULL = Frame(0f, 0f, 1f, 1f)

    private val MIN_FRAME_WIDTH = MIN_FRAME / STORY_ASPECT

    /**
     * Правка целиком. Ни одна операция ниже не меняет переданное состояние —
     * только возвращает новое: так предпросмотр и публикация видят один рецепт.
     */
    data class State(
        val quarter: Int = 0,
        val straighten: Float = 0f,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        val aspect: String = ASPECT_ORIGINAL,
        val frame: Frame = FULL,
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
    ) {
        /** Полный угол поворота: четверти плюс выравнивание. */
        val rotationDeg: Float get() = quarter * 90f + straighten
    }

    /** Размер кадра для публикации. */
    data class Size(val width: Int, val height: Int)

    // ── Кадр ────────────────────────────────────────────────────────────────

    /** Пропорция снимка с учётом поворота: после четверти она переворачивается. */
    fun originalAspect(imgW: Int, imgH: Int, quarter: Int): Float {
        if (imgW <= 0 || imgH <= 0) return 1f
        return if (quarter % 2 == 0) imgW.toFloat() / imgH else imgH.toFloat() / imgW
    }

    /** Самая крупная рамка нужной пропорции внутри кадра истории. */
    fun frameFor(aspect: Float, centerX: Float = 0.5f, centerY: Float = 0.5f): Frame {
        val a = minOf(maxOf(aspect, 0.05f), 20f)
        val unitsW = minOf(STORY_ASPECT, a)
        val unitsH = unitsW / a
        val width = unitsW / STORY_ASPECT
        val height = unitsH
        val cx = minOf(maxOf(centerX, width / 2f), 1f - width / 2f)
        val cy = minOf(maxOf(centerY, height / 2f), 1f - height / 2f)
        return Frame(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    /** Открывается весь снимок целиком: рамка в его пропорцию, без обрезки. */
    fun initialState(imgW: Int, imgH: Int): State =
        State(aspect = ASPECT_ORIGINAL, frame = frameFor(originalAspect(imgW, imgH, 0)))

    fun reset(imgW: Int, imgH: Int): State = initialState(imgW, imgH)

    /** Есть ли что сбрасывать: сравниваем с тем, что человек видел при открытии. */
    fun isEdited(state: State, imgW: Int, imgH: Int): Boolean {
        val start = initialState(imgW, imgH)
        return state.quarter != start.quarter ||
            abs(state.straighten - start.straighten) > 0.01f ||
            state.flipH != start.flipH ||
            state.flipV != start.flipV ||
            state.aspect != start.aspect ||
            abs(state.zoom - start.zoom) > 0.001f ||
            abs(state.panX - start.panX) > 0.001f ||
            abs(state.panY - start.panY) > 0.001f ||
            abs(state.frame.left - start.frame.left) > 0.001f ||
            abs(state.frame.top - start.frame.top) > 0.001f ||
            abs(state.frame.right - start.frame.right) > 0.001f ||
            abs(state.frame.bottom - start.frame.bottom) > 0.001f
    }

    /** Пропорция активного чипа; null — рамка задана уголками вручную. */
    fun targetAspect(state: State, imgW: Int, imgH: Int): Float? =
        if (state.aspect == ASPECT_ORIGINAL) originalAspect(imgW, imgH, state.quarter)
        else presetAspect(state.aspect)

    /** Рамку подвинули: чип остаётся выбранным, только если пропорция всё ещё его. */
    private fun keepAspect(aspect: String, frame: Frame, imgW: Int, imgH: Int, quarter: Int): String {
        val target = if (aspect == ASPECT_ORIGINAL) originalAspect(imgW, imgH, quarter)
        else presetAspect(aspect)
        if (target == null || target <= 0f) return ASPECT_FREE
        return if (abs(frame.aspect - target) <= target * 0.02f) aspect else ASPECT_FREE
    }

    fun withAspect(state: State, aspect: String, imgW: Int, imgH: Int): State {
        val target = if (aspect == ASPECT_ORIGINAL) originalAspect(imgW, imgH, state.quarter)
        else presetAspect(aspect)
        if (target == null || target <= 0f) return state
        return bind(
            state.copy(
                aspect = aspect,
                frame = frameFor(target, state.frame.centerX, state.frame.centerY),
            ),
            imgW,
            imgH,
        )
    }

    /**
     * Поворот рамки вместе со снимком: у широкого фото после поворота на четверть
     * видно всё фото, а не его половину.
     *
     * Пропорция переворачивается, а размер берётся самый крупный для новой
     * пропорции. Такой размер на состав кадра не влияет (он задаёт только
     * разрешение готового снимка), а ужимать рамку при повороте незачем: после
     * двух четвертей она обязана вернуться ровно к прежней.
     */
    fun rotateFrameQuarter(frame: Frame): Frame {
        val flipped = if (frame.aspect > 0.001f) 1f / frame.aspect else 1f
        return frameFor(flipped, frame.centerX, frame.centerY)
    }

    /** «Повернуть»: четверть по часовой стрелке, рамка едет вместе со снимком. */
    fun withQuarter(state: State, imgW: Int, imgH: Int): State {
        val quarter = (state.quarter + 1) % 4
        val frame = rotateFrameQuarter(state.frame)
        return bind(
            state.copy(
                quarter = quarter,
                frame = frame,
                aspect = keepAspect(state.aspect, frame, imgW, imgH, quarter),
            ),
            imgW,
            imgH,
        )
    }

    /** Ползунок «Ровно»: ±45° вокруг центра рамки, четверти не трогаем. */
    fun withStraighten(state: State, deg: Float, imgW: Int, imgH: Int): State =
        bind(
            state.copy(straighten = minOf(maxOf(deg, -STRAIGHTEN_LIMIT), STRAIGHTEN_LIMIT)),
            imgW,
            imgH,
        )

    /**
     * Доворот двумя пальцами: полный угол раскладывается на четверти и остаток
     * ±45. Так палец доворачивает снимок до конца, а не упирается в ползунок.
     */
    fun withRotation(state: State, totalDeg: Float, imgW: Int, imgH: Int): State {
        val norm = ((totalDeg % 360f) + 360f) % 360f
        val steps = floor((norm + STRAIGHTEN_LIMIT) / 90f).toInt()
        val straighten = norm - steps * 90f
        return bind(
            state.copy(quarter = steps % 4, straighten = straighten),
            imgW,
            imgH,
        )
    }

    fun toggleFlipH(state: State, imgW: Int, imgH: Int): State =
        bind(state.copy(flipH = !state.flipH), imgW, imgH)

    fun toggleFlipV(state: State, imgW: Int, imgH: Int): State =
        bind(state.copy(flipV = !state.flipV), imgW, imgH)

    fun withZoom(state: State, zoom: Float, imgW: Int, imgH: Int): State =
        bind(state.copy(zoom = zoom), imgW, imgH)

    fun zoomBy(state: State, factor: Float, imgW: Int, imgH: Int): State =
        withZoom(state, state.zoom * factor, imgW, imgH)

    /**
     * Сдвиг одним пальцем: dx/dy в долях размера рамки. Как только край снимка
     * доходит до рамки, сдвиг останавливается — пустых углов не бывает.
     */
    fun panBy(state: State, dx: Float, dy: Float, imgW: Int, imgH: Int): State =
        bind(state.copy(panX = state.panX + dx, panY = state.panY + dy), imgW, imgH)

    /**
     * Свободная обрезка уголком: corner 0 — левый верх, 1 — правый верх,
     * 2 — правый низ, 3 — левый низ; dx/dy в долях кадра истории.
     */
    fun withCorner(state: State, corner: Int, dx: Float, dy: Float, imgW: Int, imgH: Int): State {
        val f = state.frame
        var left = f.left
        var top = f.top
        var right = f.right
        var bottom = f.bottom
        when (corner) {
            0 -> {
                left += dx
                top += dy
            }
            1 -> {
                right += dx
                top += dy
            }
            2 -> {
                right += dx
                bottom += dy
            }
            else -> {
                left += dx
                bottom += dy
            }
        }
        // Перекрестие не переворачиваем: палец упирается в противоположную сторону.
        if (right - left < MIN_FRAME_WIDTH) {
            if (corner == 0 || corner == 3) left = right - MIN_FRAME_WIDTH else right = left + MIN_FRAME_WIDTH
        }
        if (bottom - top < MIN_FRAME) {
            if (corner == 0 || corner == 1) top = bottom - MIN_FRAME else bottom = top + MIN_FRAME
        }
        left = minOf(maxOf(left, 0f), 1f)
        right = minOf(maxOf(right, 0f), 1f)
        top = minOf(maxOf(top, 0f), 1f)
        bottom = minOf(maxOf(bottom, 0f), 1f)
        val moved = Frame(left, top, right, bottom)
        if (moved == f) return state
        return bind(
            state.copy(frame = moved, aspect = keepAspect(state.aspect, moved, imgW, imgH, state.quarter)),
            imgW,
            imgH,
        )
    }

    // ── Покрытие и границы ──────────────────────────────────────────────────

    /**
     * Минимальное увеличение «пиксель снимка → единица рамки», при котором рамка
     * не пустует. Формула точная: повёрнутая рамка целиком внутри снимка тогда и
     * только тогда, когда её размахи по осям не больше снимка.
     */
    fun coverScale(state: State, imgW: Int, imgH: Int): Float {
        if (imgW <= 0 || imgH <= 0) return 1f
        val rad = Math.toRadians(state.rotationDeg.toDouble())
        val kW = abs(cos(rad)).toFloat()
        val kH = abs(sin(rad)).toFloat()
        val f = state.frame
        val needW = f.widthUnits * kW + f.heightUnits * kH
        val needH = f.widthUnits * kH + f.heightUnits * kW
        return maxOf(needW / imgW, needH / imgH)
    }

    /** Нет ли в рамке пустых углов при текущих сдвиге и увеличении. */
    fun covers(state: State, imgW: Int, imgH: Int): Boolean {
        if (imgW <= 0 || imgH <= 0) return true
        val scale = coverScale(state, imgW, imgH) * minOf(maxOf(state.zoom, MIN_ZOOM), MAX_ZOOM)
        if (scale <= 0f) return false
        val f = state.frame
        val ex = f.widthUnits / 2f
        val ey = f.heightUnits / 2f
        val rad = Math.toRadians(state.rotationDeg.toDouble())
        val co = cos(rad).toFloat()
        val si = sin(rad).toFloat()
        val px = state.panX * f.widthUnits
        val py = state.panY * f.heightUnits
        // Все длины приводим к пикселям снимка: scale - это «единиц рамки на пиксель».
        val offX = abs(px * co + py * si) / scale
        val offY = abs(-px * si + py * co) / scale
        val extentX = (ex * abs(co) + ey * abs(si)) / scale
        val extentY = (ex * abs(si) + ey * abs(co)) / scale
        // Запас в полпикселя: на точном равенстве округление float давало бы «нет».
        return offX + extentX <= imgW / 2f + 0.75f && offY + extentY <= imgH / 2f + 0.75f
    }

    /**
     * Привести правку в допустимый вид: увеличение в границах, сдвиг не выходит
     * за край снимка. Возвращается новое состояние, вход не меняется.
     */
    fun bind(state: State, imgW: Int, imgH: Int): State {
        val zoomed = state.copy(
            frame = saneFrame(state.frame),
            zoom = minOf(maxOf(state.zoom, MIN_ZOOM), MAX_ZOOM),
        )
        if (covers(zoomed, imgW, imgH)) return zoomed
        // Сдвиг ограничиваем по осям ПО ОЧЕРЕДИ: у кадра шире снимка по ширине
        // двигать нечего, а по высоте запас есть - терять его из-за первой оси
        // нельзя. Запрошенный сдвиг по высоте возвращаем после ограничения по X.
        val alongX = clampAxis(zoomed, imgW, imgH, horizontal = true)
        return clampAxis(alongX, imgW, imgH, horizontal = false)
    }

    /** Рамка в допустимом виде: внутри кадра истории, не мельче минимума, не вывернута. */
    private fun saneFrame(frame: Frame): Frame {
        var left = minOf(maxOf(frame.left, 0f), 1f)
        var top = minOf(maxOf(frame.top, 0f), 1f)
        var right = minOf(maxOf(frame.right, 0f), 1f)
        var bottom = minOf(maxOf(frame.bottom, 0f), 1f)
        if (right - left < MIN_FRAME_WIDTH) {
            right = minOf(1f, left + MIN_FRAME_WIDTH)
            left = maxOf(0f, right - MIN_FRAME_WIDTH)
        }
        if (bottom - top < MIN_FRAME) {
            bottom = minOf(1f, top + MIN_FRAME)
            top = maxOf(0f, bottom - MIN_FRAME)
        }
        return Frame(left, top, right, bottom)
    }

    /** Ограничение сдвига по одной оси: бисекция по лучу от нуля до запрошенного. */
    private fun clampAxis(state: State, imgW: Int, imgH: Int, horizontal: Boolean): State {
        if (covers(state, imgW, imgH)) return state
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            val probe = if (horizontal) {
                state.copy(panX = state.panX * mid, panY = 0f)
            } else {
                state.copy(panY = state.panY * mid)
            }
            if (covers(probe, imgW, imgH)) lo = mid else hi = mid
        }
        return if (horizontal) state.copy(panX = state.panX * lo) else state.copy(panY = state.panY * lo)
    }

    // ── Рендер ──────────────────────────────────────────────────────────────

    /**
     * Карта «пиксель снимка → пиксель кадра». Ею пользуется и предпросмотр на
     * экране, и публикация — поэтому превью и результат совпадают.
     */
    fun matrix(imgW: Int, imgH: Int, state: State, outW: Float, outH: Float): Matrix {
        val f = state.frame
        val scale = coverScale(state, imgW, imgH) * minOf(maxOf(state.zoom, MIN_ZOOM), MAX_ZOOM)
        val perUnit = if (f.widthUnits > 0f) outW / f.widthUnits else outW
        val m = Matrix()
        m.postTranslate(-imgW / 2f, -imgH / 2f)
        if (state.flipH) m.postScale(-1f, 1f)
        if (state.flipV) m.postScale(1f, -1f)
        if (state.rotationDeg != 0f) m.postRotate(state.rotationDeg)
        m.postScale(scale * perUnit, scale * perUnit)
        m.postTranslate(outW / 2f + state.panX * outW, outH / 2f + state.panY * outH)
        return m
    }

    /** Кисть кадра: своя на каждый вызов — кадр рисуют и с экрана, и из фоновой задачи. */
    fun bitmapPaint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Рисует кадр в готовое полотно: сюда приходят и предпросмотр, и публикация. */
    fun draw(canvas: Canvas, bitmap: Bitmap, state: State, outW: Float, outH: Float, paint: Paint) {
        if (outW <= 0f || outH <= 0f || bitmap.isRecycled) return
        canvas.drawBitmap(bitmap, matrix(bitmap.width, bitmap.height, state, outW, outH), paint)
    }

    /**
     * Размер кадра для публикации: почти «пиксель в пиксель» к видимому куску
     * снимка (крупнее исходника смысла нет, мельче — теряли бы деталь), но не
     * больше [MAX_SIDE] — дальше размер решает существующая лестница сжатия.
     */
    fun renderSize(state: State, imgW: Int, imgH: Int): Size {
        val f = state.frame
        val scale = maxOf(coverScale(state, imgW, imgH) * minOf(maxOf(state.zoom, MIN_ZOOM), MAX_ZOOM), 1e-6f)
        val visibleLong = maxOf(f.widthUnits, f.heightUnits) / scale
        val outLong = floor(minOf(maxOf(visibleLong, MIN_SIDE.toFloat()), MAX_SIDE.toFloat()) + 0.5f).toInt()
        val aspect = f.aspect
        return if (aspect >= 1f) {
            Size(outLong, maxOf(1, floor(outLong / aspect + 0.5f).toInt()))
        } else {
            Size(maxOf(1, floor(outLong * aspect + 0.5f).toInt()), outLong)
        }
    }

    /** Кадрированный снимок: ровно то, что человек видел в рамке. */
    fun render(bitmap: Bitmap, state: State, size: Size, paint: Paint): Bitmap {
        val out = Bitmap.createBitmap(maxOf(1, size.width), maxOf(1, size.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        draw(canvas, bitmap, state, out.width.toFloat(), out.height.toFloat(), paint)
        return out
    }
}
