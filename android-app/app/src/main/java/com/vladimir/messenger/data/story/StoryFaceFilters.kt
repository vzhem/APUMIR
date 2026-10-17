package com.vladimir.messenger.data.story

import kotlin.math.sqrt

/**
 * AR-фильтры лица для видеоистории: уши, нос и глаза от разных животных.
 *
 * Здесь только типы и геометрия, без Android и ML Kit, чтобы логику можно
 * было проверить JVM-тестами. Рисование — в ui/components/StoryFaceOverlay.kt,
 * распознавание лица — в StoryFaceTracker.kt.
 */

enum class StoryEars(val label: String) { NONE("Без"), CAT("Кот"), DOG("Собака"), BUNNY("Заяц"), BEAR("Медведь"), FOX("Лиса") }

enum class StoryNose(val label: String) { NONE("Без"), CAT("Кот"), DOG("Собака"), PIG("Свинка"), BEAR("Медведь"), RABBIT("Заяц") }

enum class StoryEyes(val label: String) { NONE("Без"), CAT("Кошачьи"), OWL("Совиные"), PANDA("Панда"), FROG("Лягушка") }

/** Набор фильтра: каждая часть выбирается отдельно, «Без» отключает часть. */
data class StoryFaceFilter(
    val ears: StoryEars = StoryEars.NONE,
    val nose: StoryNose = StoryNose.NONE,
    val eyes: StoryEyes = StoryEyes.NONE,
) {
    val isActive: Boolean get() = ears != StoryEars.NONE || nose != StoryNose.NONE || eyes != StoryEyes.NONE

    companion object {
        val NONE = StoryFaceFilter()
    }
}

/** Точка в пикселях повернутого кадра анализа (тот же кадр, что вернул распознаватель). */
data class StoryFacePoint(val x: Float, val y: Float)

/** Прямоугольник лица в пикселях кадра. */
data class StoryFaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Одно распознанное лицо. Ориентационный поворот (roll) в градусах, как его отдаёт
 * распознаватель: положительный — по часовой стрелке на экране камеры.
 * Любой ориентир может отсутствовать (лицо частично в кадре).
 */
data class StoryFace(
    val box: StoryFaceBox,
    val leftEye: StoryFacePoint?,
    val rightEye: StoryFacePoint?,
    val nose: StoryFacePoint?,
    val rollDegrees: Float,
)

/** Кадр анализа: размер в пикселях и найденные лица. */
data class StoryFaceFrame(val width: Int, val height: Int, val faces: List<StoryFace>)

/** Место и размер одного элемента фильтра в пикселях кадра (до поворота лица). */
data class StoryFaceAnchor(val x: Float, val y: Float, val size: Float)

/** Раскладка фильтра для одного лица. Поворот [rollDegrees] применяется отдельно, вокруг центра. */
data class StoryFaceLayout(
    val centerX: Float,
    val centerY: Float,
    val rollDegrees: Float,
    val leftEar: StoryFaceAnchor,
    val rightEar: StoryFaceAnchor,
    val leftEye: StoryFaceAnchor,
    val rightEye: StoryFaceAnchor,
    val nose: StoryFaceAnchor,
)

object StoryFaceGeometry {
    /** Меньше этого размера лицо слишком мелкое, фильтр не рисуем: он прыгает на шуме. */
    const val MIN_FACE_PX = 40f

    /**
     * Раскладка уха/глаз/носа по лицу. Если ориентиры не распознаны, ставим их по
     * пропорциям коробки лица, поэтому фильтр всё равно держится на лице.
     * Возвращает null для вырожденной или слишком мелкой коробки.
     */
    fun layout(face: StoryFace): StoryFaceLayout? {
        val box = face.box
        val w = box.width
        val h = box.height
        if (!w.isFinite() || !h.isFinite() || w < MIN_FACE_PX || h < MIN_FACE_PX) return null
        if (!face.rollDegrees.isFinite()) return null

        val cx = box.centerX
        val cy = box.centerY

        val fallbackEyeY = box.top + h * 0.42f
        val leftEye = face.leftEye?.takeIf { it.x.isFinite() && it.y.isFinite() }
            ?: StoryFacePoint(cx - w * 0.2f, fallbackEyeY)
        val rightEye = face.rightEye?.takeIf { it.x.isFinite() && it.y.isFinite() }
            ?: StoryFacePoint(cx + w * 0.2f, fallbackEyeY)
        val nose = face.nose?.takeIf { it.x.isFinite() && it.y.isFinite() }
            ?: StoryFacePoint(cx, box.top + h * 0.62f)

        // Глаза рисуем по расстоянию между зрачками: так размер не зависит от того,
        // насколько близко лицо к камере.
        val eyeDistance = distance(leftEye, rightEye).takeIf { it > 1f } ?: w * 0.4f
        val eyeSize = eyeDistance * 0.5f

        val earY = box.top + h * 0.06f
        val earOffsetX = w * 0.36f
        val earSize = w * 0.3f

        return StoryFaceLayout(
            centerX = cx,
            centerY = cy,
            rollDegrees = face.rollDegrees,
            leftEar = StoryFaceAnchor(cx - earOffsetX, earY, earSize),
            rightEar = StoryFaceAnchor(cx + earOffsetX, earY, earSize),
            leftEye = StoryFaceAnchor(leftEye.x, leftEye.y, eyeSize),
            rightEye = StoryFaceAnchor(rightEye.x, rightEye.y, eyeSize),
            nose = StoryFaceAnchor(nose.x, nose.y, w * 0.16f),
        )
    }

    /**
     * Поворот лица в экранных координатах. Если кадр зеркальный (фронталка), угол
     * меняет знак, иначе уши повернутся в другую сторону, чем лицо.
     */
    fun screenRoll(degrees: Float, mirrored: Boolean): Float = if (mirrored) -degrees else degrees

    private fun distance(a: StoryFacePoint, b: StoryFacePoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
