package com.vladimir.messenger.data.story

import android.graphics.Bitmap
import android.graphics.Matrix
import android.content.Context
import android.graphics.Rect
import com.vladimir.messenger.data.background.PersonSegmenter
import com.vladimir.messenger.data.background.VideoBackground
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Распознаватель лица для предпросмотра видеоистории. Работает как анализатор
 * CameraX, поэтому не мешает записи: кадры для анализа отдельный поток.
 *
 * [enabled] выключен, когда фильтр не выбран: кадры тогда сразу закрываются,
 * детектор не запускается и батарею не тратит.
 */
internal class StoryFaceTracker(
    private val context: Context,
    private val onFrame: (StoryFaceFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile
    var enabled: Boolean = false

    /**
     * Живой фон предпросмотра: пока выбран, анализатор дополнительно (не чаще
     * раза в ~180 мс) собирает кадр с заменённым фоном и отдаёт его экрану —
     * фон виден сразу при выборе, как в KakaoTalk, а не после записи.
     */
    @Volatile
    var background: VideoBackground? = null

    /** Кадр с фоном (или исходный кадр + причина, если фон не лёг). */
    @Volatile
    var onBackgroundFrame: ((Bitmap, String?) -> Unit)? = null

    private var lastBackgroundAtMs = 0L
    private var cachedBackground: Bitmap? = null
    private var cachedBackgroundKey = ""

    /**
     * Детектор лица — лениво и с защитой. `FaceDetection.getClient` — внешняя
     * ML-библиотека, и её отказ обязан выключать фильтры лица, а не ронять
     * экран записи: именно инициализация ML Kit в конструкторе стояла первой
     * строкой на пути «Записать видео» и падала на всех телефонах (владелец
     * 2026-11-06: и видеоаватар, и видеоистории). Так же устроены
     * [com.vladimir.messenger.data.background.PersonSegmenter] и переводчик —
     * клиенты ML Kit создаются по первому обращению, а не при старте экрана.
     */
    @Volatile
    private var detector: FaceDetector? = null

    /** false — модуль лица не поднялся: фильтры лица выключаются, запись остаётся. */
    val detectorAvailable: Boolean
        get() = detectorOrLoad() != null

    private fun detectorOrLoad(): FaceDetector? = detector ?: synchronized(this) {
        detector ?: newStoryFaceDetectorOrNull(context).also { detector = it }
    }

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        makeBackgroundFrameIfNeeded(image)
        val media = image.image
        if (!enabled || media == null) {
            image.close()
            return
        }
        // Лёгкая база: без докачанного пакета лица нативный детектор не
        // трогаем — кадр просто проходит без эффектов, приложение не падает.
        if (!com.vladimir.messenger.data.featurepacks.FeaturePackRuntime.ensureLoaded(
                context,
                com.vladimir.messenger.data.featurepacks.FeaturePackFeature.VIDEO_FACE_EFFECTS,
            )
        ) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        val faceDetector = detectorOrLoad() ?: run {
            // Модуль лица не поднялся: кадр без фильтров уходит дальше,
            // экран уже показывает «фильтры лица недоступны».
            image.close()
            return
        }
        faceDetector.process(input)
            .addOnSuccessListener { faces ->
                onFrame(StoryFaceFrame(input.width, input.height, faces.map { it.toStoryFace() }))
            }
            .addOnCompleteListener { image.close() }
    }

    private fun makeBackgroundFrameIfNeeded(image: ImageProxy) {
        val active = background?.takeIf { it.isActive } ?: return
        val listener = onBackgroundFrame ?: return
        val now = System.currentTimeMillis()
        if (now - lastBackgroundAtMs < 180L) return
        lastBackgroundAtMs = now
        val raw = runCatching { image.toBitmap() }.getOrNull() ?: return
        // toBitmap() кадр не поворачивает: приводим в обычное положение сами.
        val rotation = image.imageInfo.rotationDegrees
        val upright = if (rotation % 360 == 0) raw else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true,
        )
        if (upright !== raw) raw.recycle()
        val key = "${active.name}:${upright.width}x${upright.height}"
        if (cachedBackgroundKey != key) {
            cachedBackgroundKey = key
            cachedBackground = if (active == VideoBackground.BLUR) null
            else active.render(context, upright.width, upright.height)
        }
        val segmented = PersonSegmenter.compositeOrNull(upright, cachedBackground)
        if (segmented != null) {
            listener(segmented, null)
            upright.recycle()
        } else {
            listener(upright, PersonSegmenter.lastFailure)
        }
    }

    fun close() {
        // Детектор мог не создаться — закрывать тогда нечего; создавать
        // его ради закрытия нельзя.
        synchronized(this) { detector.also { detector = null } }?.close()
    }

}

/**
 * Детектор лица с проверкой нативного модуля ДО первого обращения к ML Kit.
 *
 * Лёгкая база не содержит `libface_detector_v2_jni.so` (docs/FEATURE_PACKS.md):
 * библиотека приезжает пакетом «Эффекты лиц». ML Kit поднимает её САМ в
 * `<clinit>` на СВОЁМ пуле потоков (владелец 2026-11-06, CUBOT KINGKONG X:
 * `pool-7-thread-1`, `UnsatisfiedLinkError: libface_detector_v2_jni.so not
 * found`) — ошибку там никто не ловит, и процесс умирает через секунды после
 * открытия экрана записи. Поэтому путь к детектору один и охраняемый:
 * [FeaturePackRuntime.ensureLoaded] поднимает библиотеки пакета и пробует
 * `System.loadLibrary` тем же вызовом, что ML Kit; нет модуля — честный null,
 * фильтры лица выключаются, запись остаётся.
 */
internal fun newStoryFaceDetectorOrNull(context: Context): FaceDetector? {
    if (!com.vladimir.messenger.data.featurepacks.FeaturePackRuntime.ensureLoaded(
            context,
            com.vladimir.messenger.data.featurepacks.FeaturePackFeature.VIDEO_FACE_EFFECTS,
        )
    ) {
        return null
    }
    return runCatching { newStoryFaceDetector() }
        .onFailure { android.util.Log.w("StoryFaceTracker", "Face detector unavailable", it) }
        .getOrNull()
}

/** Один детектор на все проходы: и предпросмотр, и анализ записанного файла. */
internal fun newStoryFaceDetector(): FaceDetector = FaceDetection.getClient(
    FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.15f)
        .build(),
)

internal fun Face.toStoryFace(): StoryFace {
    val rect: Rect = boundingBox
    fun point(type: Int): StoryFacePoint? =
        getLandmark(type)?.position?.let { StoryFacePoint(it.x, it.y) }
    return StoryFace(
        box = StoryFaceBox(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
        leftEye = point(FaceLandmark.LEFT_EYE),
        rightEye = point(FaceLandmark.RIGHT_EYE),
        nose = point(FaceLandmark.NOSE_BASE),
        rollDegrees = headEulerAngleZ,
        mouthLeft = point(FaceLandmark.MOUTH_LEFT),
        mouthRight = point(FaceLandmark.MOUTH_RIGHT),
        leftCheek = point(FaceLandmark.LEFT_CHEEK),
        rightCheek = point(FaceLandmark.RIGHT_CHEEK),
    )
}
