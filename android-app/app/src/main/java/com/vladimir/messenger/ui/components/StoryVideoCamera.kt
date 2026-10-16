package com.vladimir.messenger.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vladimir.messenger.data.story.StoryVideoFiles
import com.vladimir.messenger.data.story.StoryVideoRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local capture screen; caller owns the returned private source and must delete it after editing. */
@Composable
internal fun StoryVideoCamera(circle: Boolean = false, onDismiss: () -> Unit, onRecorded: (StoryVideoFiles.Source) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val recorded by rememberUpdatedState(onRecorded)
    val session = remember(context, owner) { StoryVideoRecorder(context) }
    val preview = remember(context, owner) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    } }
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var audio by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAllowed = it
        if (!it) error = "Для записи нужен доступ к камере. Можно выбрать видео из галереи."
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audio = it
        if (!it) error = "Микрофон недоступен. Можно записать видео без звука."
    }
    DisposableEffect(session, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.close()
                dismiss()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            session.close()
        }
    }
    LaunchedEffect(session, cameraAllowed, front) {
        ready = false
        if (cameraAllowed) {
            try {
                session.bind(owner, preview, front)
                ready = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось открыть камеру"
            }
        }
    }
    Dialog(onDismissRequest = { session.close(); dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ApuSettingsCard(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Запись видеоистории · до 60 секунд")
                if (cameraAllowed) {
                    StoryVideoFrame(circle, Modifier.fillMaxWidth().height(300.dp)) {
                        AndroidView(factory = { preview }, update = {
                            applyStoryVideoShape(it, circle)
                            it.scaleType = if (circle) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
                        }, modifier = Modifier.fillMaxSize())
                    }
                } else {
                    ApuPremiumButton(label = "Разрешить камеру", onClick = {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    })
                }
                Text("${elapsed / 1000} / 60 с" + if (paused) " · пауза" else "")
                error?.let { Text(it) }
                ApuPremiumButton(label = if (front) "Задняя камера" else "Передняя камера",
                    enabled = cameraAllowed && !recording && !stopping,
                    onClick = { front = !front; error = null })
                Text("Запись со звуком")
                ApuPremiumSwitch(checked = audio, enabled = !recording && !stopping,
                    onCheckedChange = { enabled ->
                        if (!enabled) audio = false
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) audio = true
                        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    })
                if (!recording && !stopping) {
                    ApuPremiumButton(label = "Начать запись", enabled = ready, onClick = {
                        recording = true
                        paused = false
                        elapsed = 0L
                        error = null
                        try {
                            session.start(audio, onElapsed = { elapsed = it }, onFinished = { result ->
                                recording = false
                                stopping = true
                                val file = result.getOrNull()
                                if (file == null) {
                                    stopping = false
                                    error = result.exceptionOrNull()?.message ?: "Запись не удалась"
                                } else {
                                    // Completion handler also runs if the scope was already cancelled.
                                    var handedOff = false
                                    scope.launch {
                                        try {
                                            val source = withContext(Dispatchers.IO) { StoryVideoFiles.inspect(file) }
                                            recorded(source)
                                            handedOff = true
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            error = failure.message ?: "Не удалось прочитать запись"
                                        } finally { stopping = false }
                                    }.invokeOnCompletion { if (!handedOff) file.delete() }
                                }
                            })
                        } catch (failure: Exception) {
                            recording = false
                            error = failure.message ?: "Не удалось начать запись"
                        }
                    })
                } else if (recording) {
                    ApuPremiumButton(label = if (paused) "Продолжить" else "Пауза", enabled = !stopping,
                        onClick = {
                            if (paused) session.resume() else session.pause()
                            paused = !paused
                        })
                    ApuPremiumButton(label = "Завершить запись", enabled = !stopping, onClick = {
                        stopping = true
                        session.stop()
                    })
                } else Text("Подготовка записи…")
                ApuTextAction(label = "Отменить", onClick = { session.close(); dismiss() })
            }
        }
    }
}
