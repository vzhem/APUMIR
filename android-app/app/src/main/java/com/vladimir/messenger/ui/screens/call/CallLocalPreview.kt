package com.vladimir.messenger.ui.screens.call

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vladimir.messenger.data.call.CallVideo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Локальная камера видеозвонка: превью + JPEG в транспорт, когда [send] = true.
 */
@Composable
fun CallLocalPreview(
    send: Boolean,
    front: Boolean,
    onJpeg: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    background: com.vladimir.messenger.data.background.VideoBackground =
        com.vladimir.messenger.data.background.VideoBackground.NONE,
) {
    if (background.isActive) {
        CallBackgroundPreview(send = send, front = front, onJpeg = onJpeg, background = background, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val sendFlag = remember { AtomicBoolean(false) }
    sendFlag.set(send)
    val lastSent = remember { AtomicLong(0L) }
    val jpegSink = rememberUpdatedState(onJpeg)

    DisposableEffect(front, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val main = ContextCompat.getMainExecutor(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(CallVideo.WIDTH, CallVideo.HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    if (!sendFlag.get()) return@setAnalyzer
                    val now = System.currentTimeMillis()
                    if (now - lastSent.get() < 120L) return@setAnalyzer
                    val packed = image.packJpeg() ?: return@setAnalyzer
                    lastSent.set(now)
                    jpegSink.value(packed)
                } finally {
                    image.close()
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, analysis)
            }
        }, main)
        onDispose {
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
            } else {
                future.addListener({ runCatching { future.get().unbindAll() } }, main)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }
    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}

@Composable
fun CallRemoteFrame(
    packed: ByteArray?,
    modifier: Modifier = Modifier,
) {
    val bmp = remember(packed) { packed?.let(::decodeRemoteJpeg) }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

private fun decodeRemoteJpeg(packed: ByteArray): Bitmap? {
    val (rot, jpeg) = CallVideo.unpack(packed) ?: return null
    val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    if (rot == 0) return raw
    return Bitmap.createBitmap(
        raw, 0, 0, raw.width, raw.height,
        Matrix().apply { postRotate(rot.toFloat()) },
        true,
    )
}

private fun ImageProxy.packJpeg(): ByteArray? {
    val rotation = imageInfo.rotationDegrees
    var quality = 42
    var jpeg = toJpeg(quality) ?: return null
    while (jpeg.size > CallVideo.MAX_JPEG_BYTES && quality > 22) {
        quality -= 10
        jpeg = toJpeg(quality) ?: return null
    }
    if (jpeg.size > CallVideo.MAX_JPEG_BYTES) return null
    return CallVideo.pack(rotation, jpeg)
}

private fun ImageProxy.toJpeg(quality: Int): ByteArray? {
    val yuv = toNv21() ?: return null
    val img = YuvImage(yuv, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    if (!img.compressToJpeg(Rect(0, 0, width, height), quality, out)) return null
    return out.toByteArray()
}

private fun ImageProxy.toNv21(): ByteArray? {
    if (planes.size < 3) return null
    val ySize = width * height
    val out = ByteArray(ySize + ySize / 2)
    val y = planes[0].buffer.duplicate()
    y.get(out, 0, y.remaining().coerceAtMost(ySize))
    val vBuf = planes[2].buffer.duplicate()
    val uBuf = planes[1].buffer.duplicate()
    val vRow = planes[2].rowStride
    val vPix = planes[2].pixelStride
    val uRow = planes[1].rowStride
    val uPix = planes[1].pixelStride
    var offset = ySize
    val chromaH = height / 2
    val chromaW = width / 2
    for (row in 0 until chromaH) {
        for (col in 0 until chromaW) {
            val vi = row * vRow + col * vPix
            val ui = row * uRow + col * uPix
            if (vi >= vBuf.limit() || ui >= uBuf.limit() || offset + 1 >= out.size) {
                return out
            }
            out[offset++] = vBuf.get(vi)
            out[offset++] = uBuf.get(ui)
        }
    }
    return out
}


/**
 * Превью с заменённым фоном: камера без поверхности предпросмотра, каждый кадр
 * вырезается сегментацией и кладётся на фон; тот же кадр уходит собеседнику.
 * Телефон показывает то же, что видит собеседник.
 */
@Composable
private fun CallBackgroundPreview(
    send: Boolean,
    front: Boolean,
    onJpeg: (ByteArray) -> Unit,
    background: com.vladimir.messenger.data.background.VideoBackground,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val sendFlag = remember { AtomicBoolean(false) }
    sendFlag.set(send)
    val lastSent = remember { AtomicLong(0L) }
    val jpegSink = rememberUpdatedState(onJpeg)
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var backgroundPending by remember { mutableStateOf(true) }
    var backgroundStatus by remember { mutableStateOf<String?>(null) }
    var renderedBackground by remember { mutableStateOf<Bitmap?>(null) }
    var renderedKey by remember { mutableStateOf("") }

    DisposableEffect(front, owner, background) {
        val future = ProcessCameraProvider.getInstance(context)
        val main = ContextCompat.getMainExecutor(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(CallVideo.WIDTH, CallVideo.HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastSent.get() < 150L) return@setAnalyzer
                    val rawSource = runCatching { image.toBitmap() }.getOrNull() ?: return@setAnalyzer
                    // toBitmap() кадр не поворачивает: приводим в обычное положение
                    // сами, иначе на части телефонов фон ложился на боковой кадр.
                    val frameRotation = image.imageInfo.rotationDegrees
                    val source = if (frameRotation % 360 == 0) rawSource else Bitmap.createBitmap(
                        rawSource, 0, 0, rawSource.width, rawSource.height,
                        Matrix().apply { postRotate(frameRotation.toFloat()) }, true,
                    )
                    if (source !== rawSource) rawSource.recycle()
                    lastSent.set(now)
                    val key = "${background.name}:${source.width}x${source.height}"
                    if (renderedKey != key) {
                        renderedKey = key
                        renderedBackground = if (background ==
                            com.vladimir.messenger.data.background.VideoBackground.BLUR
                        ) null else background.render(source.width, source.height)
                    }
                    val segmented = com.vladimir.messenger.data.background.PersonSegmenter
                        .compositeOrNull(source, renderedBackground)
                    backgroundPending = segmented == null
                    backgroundStatus = if (segmented == null) {
                        com.vladimir.messenger.data.background.PersonSegmenter.lastFailure
                    } else null
                    val composited = segmented
                        ?: source.copy(Bitmap.Config.ARGB_8888, false)
                    frame = composited
                    if (sendFlag.get()) {
                        // Кадр уже повёрнут выше, второй поворот не нужен.
                        val rotation = 0
                        var quality = 42
                        var jpeg: ByteArray? = null
                        while (quality >= 18) {
                            val out = ByteArrayOutputStream()
                            composited.compress(Bitmap.CompressFormat.JPEG, quality, out)
                            jpeg = out.toByteArray()
                            if (jpeg.size <= CallVideo.MAX_JPEG_BYTES) break
                            quality -= 12
                        }
                        val bytes = jpeg
                        if (bytes != null && bytes.size <= CallVideo.MAX_JPEG_BYTES) {
                            jpegSink.value(CallVideo.pack(rotation, bytes))
                        }
                    }
                    if (composited !== source) source.recycle()
                } catch (_: Exception) {
                    // Один битый кадр не должен ронять звонок.
                } finally {
                    image.close()
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, analysis)
            }
        }, main)
        onDispose {
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
            } else {
                future.addListener({ runCatching { future.get().unbindAll() } }, main)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }
    val current = frame
    if (current != null) {
        Box(modifier = modifier.fillMaxSize()) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                // Обычное превью фронтальной камеры зеркалит картинку (так человек
                // привык себя видеть); кадр сегментации приходит незеркальным,
                // поэтому без этого выбор фона выглядел как «зеркалирование».
                // Собеседнику уходит незеркальный кадр — как и без фона.
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (front) Modifier.graphicsLayer { scaleX = -1f } else Modifier),
                contentScale = ContentScale.Crop,
            )
            if (backgroundPending) {
                Text(
                    text = backgroundStatus?.let { "Фон не применился: $it" }
                        ?: "Фон подготавливается…",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                )
            }
        }
    }
}
