package com.vladimir.messenger.data.story

import android.graphics.Rect
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
    private val onFrame: (StoryFaceFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile
    var enabled: Boolean = false

    private val detector: FaceDetector = newStoryFaceDetector()

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
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
    )
}
