package com.vladimir.messenger.ui.components

import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vladimir.messenger.R
import com.vladimir.messenger.data.story.StoryPhoto
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val cameraGold = Color(0xFFE6BE70)

@Composable
internal fun StoryCameraDialog(
    onDismiss: () -> Unit,
    onCaptured: (StoryPhoto.CaptureTarget) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var lensFacing by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(StoryCameraControls.Flash.OFF) }
    var isTakingPhoto by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<Int?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomMin by remember { mutableFloatStateOf(1f) }
    var zoomMax by remember { mutableFloatStateOf(1f) }
    var showZoom by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(cameraProviderFuture, lifecycleOwner, lensFacing, previewView) {
        var active = true
        var providerForCleanup: ProcessCameraProvider? = null
        imageCapture = null
        camera = null
        cameraReady = false
        canSwitchCamera = false
        hasFlash = false
        cameraError = null
        zoomRatio = 1f
        zoomMin = 1f
        zoomMax = 1f

        val listener = Runnable {
            if (active) {
                try {
                    val provider = cameraProviderFuture.get()
                    providerForCleanup = provider
                    if (active) {
                        val selector = CameraSelector.Builder()
                            .requireLensFacing(lensFacing)
                            .build()
                        if (!provider.hasCamera(selector)) {
                            val fallbackFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                            val fallbackSelector = CameraSelector.Builder()
                                .requireLensFacing(fallbackFacing)
                                .build()
                            if (provider.hasCamera(fallbackSelector)) {
                                lensFacing = fallbackFacing
                            } else {
                                cameraError = R.string.story_camera_unavailable
                            }
                        } else {
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                                .build()
                            capture.flashMode = flashMode(flash)
                            provider.unbindAll()
                            if (active) {
                                val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                                imageCapture = capture
                                camera = bound
                                cameraReady = true
                                hasFlash = bound.cameraInfo.hasFlashUnit()
                                if (!hasFlash) flash = StoryCameraControls.Flash.OFF
                                val zoom = bound.cameraInfo.zoomState.value
                                zoomMin = zoom?.minZoomRatio ?: 1f
                                zoomMax = zoom?.maxZoomRatio ?: 1f
                                zoomRatio = zoom?.zoomRatio ?: 1f
                                val otherFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                    CameraSelector.LENS_FACING_FRONT
                                } else {
                                    CameraSelector.LENS_FACING_BACK
                                }
                                canSwitchCamera = runCatching {
                                    provider.hasCamera(
                                        CameraSelector.Builder().requireLensFacing(otherFacing).build(),
                                    )
                                }.getOrDefault(false)
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (active) {
                        imageCapture = null
                        camera = null
                        cameraReady = false
                        canSwitchCamera = false
                        hasFlash = false
                        cameraError = R.string.story_camera_unavailable
                    }
                }
            }
        }
        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            active = false
            imageCapture = null
            camera = null
            cameraReady = false
            canSwitchCamera = false
            hasFlash = false
            providerForCleanup?.unbindAll()
        }
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint == null) return@LaunchedEffect
        delay(1_200)
        focusPoint = null
    }
    LaunchedEffect(showZoom, zoomRatio) {
        if (!showZoom) return@LaunchedEffect
        delay(1_200)
        showZoom = false
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (isTakingPhoto) return
        isTakingPhoto = true
        cameraError = null
        val target = try {
            StoryPhoto.createCaptureTarget(context)
        } catch (_: Exception) {
            isTakingPhoto = false
            cameraError = R.string.story_camera_unavailable
            return
        }
        capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        capture.flashMode = flashMode(flash)
        val options = ImageCapture.OutputFileOptions.Builder(target.file).build()
        try {
            capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isTakingPhoto = false
                    onCaptured(target)
                }

                override fun onError(exception: ImageCaptureException) {
                    target.delete()
                    isTakingPhoto = false
                    cameraError = R.string.story_camera_capture_failed
                }
            })
        } catch (_: Exception) {
            target.delete()
            isTakingPhoto = false
            cameraError = R.string.story_camera_capture_failed
        }
    }

    fun switchCamera() {
        if (!cameraReady || !canSwitchCamera || isTakingPhoto) return
        imageCapture = null
        cameraReady = false
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    fun applyPinch(pinch: Float) {
        val bound = camera ?: return
        if (!cameraReady || isTakingPhoto) return
        val next = StoryCameraControls.zoomAfterPinch(zoomRatio, pinch, zoomMin, zoomMax)
        if (next == zoomRatio) return
        zoomRatio = next
        showZoom = true
        runCatching { bound.cameraControl.setZoomRatio(next) }
    }

    fun focusAt(position: Offset, size: IntSize) {
        val bound = camera ?: return
        if (!cameraReady || isTakingPhoto || size.width <= 0 || size.height <= 0) return
        val x = position.x.coerceIn(0f, size.width.toFloat())
        val y = position.y.coerceIn(0f, size.height.toFloat())
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        runCatching { bound.cameraControl.startFocusAndMetering(action) }
        focusPoint = Offset(x, y)
    }

    Dialog(
        onDismissRequest = { if (!isTakingPhoto) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogView = LocalView.current
        SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        val navBottomPx = with(density) {
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
        }
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomPad = with(density) {
            StoryCameraControls.shutterBottomPaddingPx(navBottomPx, 36.dp.toPx()).toDp() + 12.dp
        }
        val topPad = maxOf(statusTop, 16.dp)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { previewSize = it }
                    .pointerInput(cameraReady, camera, zoomMin, zoomMax) {
                        detectTransformGestures { _, _, zoom, _ -> applyPinch(zoom) }
                    }
                    .pointerInput(cameraReady, camera, canSwitchCamera) {
                        detectTapGestures(
                            onTap = { focusAt(it, previewSize) },
                            onDoubleTap = { switchCamera() },
                        )
                    },
            )
            focusPoint?.let { point ->
                val ring = 72.dp
                Box(
                    Modifier
                        .offset {
                            val half = ring.toPx() / 2f
                            IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt())
                        }
                        .size(ring)
                        .border(2.dp, cameraGold, CircleShape),
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = topPad)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, enabled = !isTakingPhoto) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) R.string.story_camera_back
                        else R.string.story_camera_front,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (hasFlash) {
                    val flashLabel = stringResource(
                        when (flash) {
                            StoryCameraControls.Flash.OFF -> R.string.story_camera_flash_off
                            StoryCameraControls.Flash.ON -> R.string.story_camera_flash_on
                            StoryCameraControls.Flash.AUTO -> R.string.story_camera_flash_auto
                        },
                    )
                    IconButton(
                        onClick = {
                            flash = StoryCameraControls.cycleFlash(flash, true)
                            imageCapture?.flashMode = flashMode(flash)
                        },
                        enabled = cameraReady && !isTakingPhoto,
                    ) {
                        Icon(
                            when (flash) {
                                StoryCameraControls.Flash.OFF -> Icons.Default.FlashOff
                                StoryCameraControls.Flash.ON -> Icons.Default.FlashOn
                                StoryCameraControls.Flash.AUTO -> Icons.Default.FlashAuto
                            },
                            flashLabel,
                            tint = Color.White,
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottomPad)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                cameraError?.let { errorId ->
                    Text(
                        stringResource(errorId),
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (cameraError == null && !cameraReady) {
                    Text(stringResource(R.string.story_camera_starting), color = Color.White)
                    Spacer(Modifier.height(12.dp))
                }
                if (showZoom && zoomMax > zoomMin + 0.05f) {
                    Text(
                        StoryCameraControls.zoomLabel(zoomRatio),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics {
                                contentDescription = StoryCameraControls.zoomLabel(zoomRatio)
                            },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    val shutterEnabled = cameraReady && !isTakingPhoto
                    val shutterLabel = stringResource(
                        if (isTakingPhoto) R.string.story_camera_capturing else R.string.story_camera_capture,
                    )
                    Box(
                        Modifier
                            .size(80.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(7.dp)
                            .clip(CircleShape)
                            .background(if (shutterEnabled) cameraGold else Color.White.copy(alpha = .35f))
                            .clickable(enabled = shutterEnabled) { takePhoto() }
                            .apuTapHaptics()
                            .semantics { contentDescription = shutterLabel },
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = { switchCamera() },
                            enabled = cameraReady && canSwitchCamera && !isTakingPhoto,
                        ) {
                            Icon(
                                Icons.Default.Cameraswitch,
                                stringResource(R.string.story_camera_switch),
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun flashMode(flash: StoryCameraControls.Flash): Int = when (flash) {
    StoryCameraControls.Flash.OFF -> ImageCapture.FLASH_MODE_OFF
    StoryCameraControls.Flash.ON -> ImageCapture.FLASH_MODE_ON
    StoryCameraControls.Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
}
