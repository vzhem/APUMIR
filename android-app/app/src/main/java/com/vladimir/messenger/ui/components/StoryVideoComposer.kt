package com.vladimir.messenger.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vladimir.messenger.data.story.StoryVideoExporter
import com.vladimir.messenger.data.story.StoryVideoFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** File ownership moves to the view model only on an explicit publish tap. */
@Composable
internal fun StoryVideoComposer(onDismiss: () -> Unit, onPublish: (StoryVideoExporter.Exported) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<StoryVideoFiles.Source?>(null) }
    var exported by remember { mutableStateOf<StoryVideoExporter.Exported?>(null) }
    var camera by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { source?.file?.delete(); exported?.delete() }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try { source = StoryVideoFiles.import(context, uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Не удалось открыть видео" }
            finally { busy = false }
        }
    }
    val ready = exported
    val input = source
    when {
        camera -> StoryVideoCamera(onDismiss = { camera = false }, onRecorded = {
            source?.file?.delete()
            source = it
            camera = false
        })
        input != null && ready == null -> StoryVideoEditor(input, onDismiss = {
            input.file.delete(); source = null
        }, onReady = { exported = it })
        else -> Dialog(onDismissRequest = onDismiss) {
            ApuSettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Видеоистория · до 60 секунд")
                    if (ready != null) {
                        Text("${ready.media.durationMs / 1000.0} с · ${ready.media.sizeBytes / (1024 * 1024)} МБ")
                        Text(ready.draft.caption)
                        Text("Доступна вашим контактам 24 часа. Доставка продолжится при подключении получателей.")
                        ApuPremiumButton(label = "Опубликовать видео", onClick = {
                            onPublish(ready)
                            exported = null
                        })
                        ApuTextAction(label = "Вернуться к редактору", onClick = { ready.delete(); exported = null })
                    } else {
                        ApuPremiumButton(label = "Выбрать видео", enabled = !busy, onClick = { gallery.launch("video/*") })
                        ApuPremiumButton(label = "Записать видео", enabled = !busy, onClick = { camera = true })
                    }
                    if (busy) Text("Открываем видео…")
                    error?.let { Text(it) }
                    ApuTextAction(label = "Отменить", onClick = onDismiss)
                }
            }
        }
    }
}
