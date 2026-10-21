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

    private val detector: FaceDetector = newStoryFaceDetector()

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        makeBackgroundFrameIfNeeded(image)
        val media = image.image
        if (!enabled || media == null) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        // Результат приходит на главном потоке (addOnSuccessListener без executor),
        // поэтому состояние Compose можно обновлять прямо из него.
        detector.process(input)
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
        detector.close()
    }

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
