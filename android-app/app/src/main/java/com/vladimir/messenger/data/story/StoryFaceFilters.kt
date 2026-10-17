package com.vladimir.messenger.data.story

import kotlin.math.sqrt

/**
 * AR-фильтры лица для видеоистории: уши, нос, глаза, губы, брови, усы и щёки.
 *
 * Здесь только типы и геометрия, без Android и ML Kit, чтобы логику можно
 * было проверить JVM-тестами. Рисование — в data/story/StoryFaceArt.kt,
 * движение — в StoryFaceMotion.kt, распознавание — в StoryFaceTracker.kt.
 */

enum class StoryEars(val label: String) {
    NONE("Без"), CAT("Кот"), DOG("Собака"), BUNNY("Заяц"), BEAR("Медведь"), FOX("Лиса"),
    PANDA("Панда"), TIGER("Тигр"), MOUSE("Мышь"), DEER("Олень"),
    LYNX("Рысь"),
}

enum class StoryNose(val label: String) {
    NONE("Без"), CAT("Кот"), DOG("Собака"), PIG("Свинка"), BEAR("Медведь"), RABBIT("Заяц"),
    PANDA("Панда"), TIGER("Тигр"), MOUSE("Мышь"),
    LION("Лев"), HORSE("Лошадь"),
}

enum class StoryEyes(val label: String) {
    NONE("Без"), CAT("Кошачьи"), OWL("Совиные"), PANDA("Панда"), FROG("Лягушка"),
    TIGER("Тигриные"), FOX("Лисьи"), DOG("Собачьи"),
    WOLF("Волчьи"), LYNX("Рысьи"),
}

enum class StoryLips(val label: String) { NONE("Без"), RED("Красные"), PINK("Розовые") }

enum class StoryBrows(val label: String) { NONE("Без"), BUSHY("Густые"), THIN("Тонкие") }

enum class StoryWhiskers(val label: String) { NONE("Без"), CAT("Кошачьи") }

enum class StoryCheeks(val label: String) { NONE("Без"), BLUSH("Румянец"), FRECKLES("Веснушки") }

enum class StoryHair(val label: String) { NONE("Без"), CURLY("Кудри"), WAVY("Волны"), BOB("Каре"), BUN("Пучок"), PONYTAIL("Хвост") }

/** Набор фильтра: каждая часть выбирается отдельно, «Без» отключает часть. */
data class StoryFaceFilter(
    val ears: StoryEars = StoryEars.NONE,
    val nose: StoryNose = StoryNose.NONE,
    val eyes: StoryEyes = StoryEyes.NONE,
    val lips: StoryLips = StoryLips.NONE,
    val brows: StoryBrows = StoryBrows.NONE,
    val whiskers: StoryWhiskers = StoryWhiskers.NONE,
    val cheeks: StoryCheeks = StoryCheeks.NONE,
    val hair: StoryHair = StoryHair.NONE,
) {
    val isActive: Boolean
        get() = ears != StoryEars.NONE || nose != StoryNose.NONE || eyes != StoryEyes.NONE ||
            lips != StoryLips.NONE || brows != StoryBrows.NONE || whiskers != StoryWhiskers.NONE ||
            cheeks != StoryCheeks.NONE || hair != StoryHair.NONE

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
    val mouthLeft: StoryFacePoint? = null,
    val mouthRight: StoryFacePoint? = null,
    val leftCheek: StoryFacePoint? = null,
    val rightCheek: StoryFacePoint? = null,
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
    /** Центр рта; size — ширина линии губ. */
    val mouth: StoryFaceAnchor,
    val leftCheek: StoryFaceAnchor,
    val rightCheek: StoryFaceAnchor,
    val leftBrow: StoryFaceAnchor,
    val rightBrow: StoryFaceAnchor,
    /** Верх картинки прически у верхнего края головы; size — ширина. */
    val hair: StoryFaceAnchor,
)

object StoryFaceGeometry {
    /** Меньше этого размера лицо слишком мелкое, фильтр не рисуем: он прыгает на шуме. */
    const val MIN_FACE_PX = 40f

    /**
     * Раскладка элементов по лицу. Если ориентиры не распознаны, ставим их по
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

        // Рот: середина линии между уголками губ; если уголков нет — ниже центра лица.
        val mouthLeft = face.mouthLeft?.takeIf { it.x.isFinite() && it.y.isFinite() }
        val mouthRight = face.mouthRight?.takeIf { it.x.isFinite() && it.y.isFinite() }
        val mouth = if (mouthLeft != null && mouthRight != null) {
            StoryFaceAnchor(
                (mouthLeft.x + mouthRight.x) / 2f,
                (mouthLeft.y + mouthRight.y) / 2f,
                distance(mouthLeft, mouthRight).takeIf { it > 1f } ?: w * 0.4f,
            )
        } else {
            StoryFaceAnchor(cx, box.top + h * 0.78f, w * 0.4f)
        }

        val cheekSize = w * 0.12f
        val leftCheek = face.leftCheek?.takeIf { it.x.isFinite() && it.y.isFinite() }
            ?: StoryFacePoint(cx - w * 0.27f, box.top + h * 0.62f)
        val rightCheek = face.rightCheek?.takeIf { it.x.isFinite() && it.y.isFinite() }
            ?: StoryFacePoint(cx + w * 0.27f, box.top + h * 0.62f)

        // Брови — над глазами, на расстоянии в долю глаза.
        val browWidth = eyeSize * 1.9f
        val browLift = eyeSize * 0.95f

        return StoryFaceLayout(
            centerX = cx,
            centerY = cy,
            rollDegrees = face.rollDegrees,
            leftEar = StoryFaceAnchor(cx - earOffsetX, earY, earSize),
            rightEar = StoryFaceAnchor(cx + earOffsetX, earY, earSize),
            leftEye = StoryFaceAnchor(leftEye.x, leftEye.y, eyeSize),
            rightEye = StoryFaceAnchor(rightEye.x, rightEye.y, eyeSize),
            nose = StoryFaceAnchor(nose.x, nose.y, w * 0.16f),
            mouth = mouth,
            leftCheek = StoryFaceAnchor(leftCheek.x, leftCheek.y, cheekSize),
            rightCheek = StoryFaceAnchor(rightCheek.x, rightCheek.y, cheekSize),
            leftBrow = StoryFaceAnchor(leftEye.x, leftEye.y - browLift, browWidth),
            rightBrow = StoryFaceAnchor(rightEye.x, rightEye.y - browLift, browWidth),
            hair = StoryFaceAnchor(cx, box.top, w * 1.5f),
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
