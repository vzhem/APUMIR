package com.vladimir.messenger.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.vladimir.messenger.data.voice.VoiceEffect
import com.vladimir.messenger.data.voice.VoiceEffectFileProcessor
import com.vladimir.messenger.data.voice.VoiceEffectPreferences
import com.vladimir.messenger.data.voice.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/** Состояние записи голоса: идёт ли запись, сколько секунд, старт/стоп, отмена. */
class VoiceRecordController(
    val recording: Boolean,
    val seconds: Int,
    val toggle: () -> Unit,
    val cancel: () -> Unit,
    /** Выбранный эффект изменения голоса (применится ко всей записи). */
    val effect: VoiceEffect = VoiceEffect.ORIGINAL,
    /** Идёт обработка записи эффектом перед отправкой. */
    val processing: Boolean = false,
    val selectEffect: (VoiceEffect) -> Unit = {},
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
    var effect by remember { mutableStateOf(VoiceEffectPreferences.load(context)) }
    var processing by remember { mutableStateOf(false) }

    fun selectEffect(newEffect: VoiceEffect) {
        effect = newEffect
        VoiceEffectPreferences.save(context, newEffect)
    }
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
        val chosen = effect
        if (chosen == VoiceEffect.ORIGINAL) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", result.file)
            readyCallback(uri)
            return
        }
        // Эффект применяется ко всей записи; если обработка не удалась,
        // отправляем оригинал, а не теряем сообщение.
        processing = true
        scope.launch {
            val processed = withContext(Dispatchers.IO) {
                runCatching { VoiceEffectFileProcessor.process(result.file, chosen) }.getOrNull()
            }
            processing = false
            val file = processed?.file ?: result.file
            if (processed != null) runCatching { result.file.delete() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            readyCallback(uri)
        }
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
        effect = effect,
        processing = processing,
        selectEffect = ::selectEffect,
    )
}

/** Полоса во время записи: красная точка, время, «Отмена» и «Отправить». */
@Composable
fun VoiceRecordBar(controller: VoiceRecordController, modifier: Modifier = Modifier) {
    if (controller.processing) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ApuPremiumSpinner(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.voice_effect_processing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var showEffectPicker by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
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
        // Эффект можно сменить прямо во время записи: он применится ко всей
        // записи целиком, когда нажмут «Отправить».
        TextButton(onClick = { showEffectPicker = true }) {
            Text(
                stringResource(
                    R.string.voice_effect_button,
                    controller.effect.symbol + " " + stringResource(controller.effect.labelRes),
                ),
            )
        }
    }
    if (showEffectPicker) {
        VoiceEffectPickerDialog(
            selected = controller.effect,
            onSelected = controller.selectEffect,
            onDismiss = { showEffectPicker = false },
        )
    }
}
