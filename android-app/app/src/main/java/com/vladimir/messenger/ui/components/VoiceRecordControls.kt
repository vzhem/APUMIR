package com.vladimir.messenger.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.Manifest
import android.content.pm.PackageManager
import com.vladimir.messenger.R
import com.vladimir.messenger.data.voice.VoiceRecorder
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope

/** Состояние записи голоса: идёт ли запись, сколько секунд, старт/стоп, отмена. */
class VoiceRecordController(
    val recording: Boolean,
    val seconds: Int,
    val toggle: () -> Unit,
    val cancel: () -> Unit,
)

/**
 * Запись голоса для экранов с полем ввода. По остановке файл с волной в имени
 * отдаётся через [onReady] как content:// URI (тот же путь, что у любого файла).
 * Запрашивает доступ к микрофону при первом нажатии.
 */
@Composable
fun rememberVoiceRecorder(onReady: (Uri) -> Unit): VoiceRecordController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    val readyCallback by rememberUpdatedState(onReady)

    LaunchedEffect(recording) {
        seconds = 0
        while (recording) {
            delay(1000)
            seconds++
        }
    }
    DisposableEffect(recorder) {
        onDispose { recorder.cancel() }
    }

    fun stopAndSend() {
        recording = false
        val result = recorder.stop() ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", result.file)
        readyCallback(uri)
    }
    fun cancelRecording() {
        recording = false
        recorder.cancel()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && recorder.start(scope)) recording = true
    }
    fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (recorder.start(scope)) {
            recording = true
        }
    }

    return VoiceRecordController(
        recording = recording,
        seconds = seconds,
        toggle = { if (recording) stopAndSend() else startRecording() },
        cancel = { cancelRecording() },
    )
}

/** Полоса во время записи: красная точка, время, «Отмена» и «Отправить». */
@Composable
fun VoiceRecordBar(controller: VoiceRecordController, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color(0xFFE5484D), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.voice_recording) + " " + formatVoiceTime(controller.seconds),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = controller.cancel) { Text(stringResource(R.string.voice_cancel)) }
        TextButton(onClick = controller.toggle) { Text(stringResource(R.string.voice_stop)) }
    }
}
