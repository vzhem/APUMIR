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
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vladimir.messenger.data.story.StoryVideoDraft
import com.vladimir.messenger.data.story.StoryVideoSizeFormat
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
    var draft by remember { mutableStateOf<StoryVideoDraft?>(null) }
    var exported by remember { mutableStateOf<StoryVideoExporter.Exported?>(null) }
    var circle by remember { mutableStateOf(false) }
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
            try {
                val imported = StoryVideoFiles.import(context, uri)
                source = imported
                draft = StoryVideoDraft.fromSource(imported.durationMs).copy(circle = circle)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: context.getString(R.string.story_record_open_video_failed) }
            finally { busy = false }
        }
    }
    val ready = exported
    val input = source
    when {
        camera -> StoryVideoCamera(circle = circle, onDismiss = { camera = false }, onRecorded = {
            source?.file?.delete()
            source = it
            draft = StoryVideoDraft.fromSource(it.durationMs).copy(circle = circle)
            camera = false
        })
        input != null && ready == null -> StoryVideoEditor(input, draft = checkNotNull(draft), onDraftChange = {
            draft = it
            circle = it.circle
        }, onDismiss = {
            input.file.delete(); source = null; draft = null
        }, onReady = { exported = it })
        else -> Dialog(onDismissRequest = onDismiss) {
            ApuSettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.story_record_title))
                    if (ready != null) {
                        Text(androidx.compose.ui.res.stringResource(com.vladimir.messenger.R.string.story_video_export_summary,
                            (ready.media.durationMs / 1000.0).toString(),
                            StoryVideoSizeFormat.megabytes(ready.media.sizeBytes,
                                androidx.compose.ui.platform.LocalConfiguration.current.locales[0])))
                        Text(androidx.compose.ui.res.stringResource(if (ready.draft.circle)
                            com.vladimir.messenger.R.string.story_photo_circle else com.vladimir.messenger.R.string.story_photo_rectangle))
                        Text(ready.draft.caption)
                        Text(stringResource(R.string.story_record_audience_hint))
                        ApuPremiumButton(label = stringResource(R.string.story_record_publish), onClick = {
                            onPublish(ready)
                            exported = null
                        })
                        ApuTextAction(label = stringResource(R.string.story_record_back_to_editor), onClick = { ready.delete(); exported = null })
                    } else {
                        StoryVideoShapeSelector(circle, enabled = !busy, onChange = { circle = it })
                        ApuPremiumButton(label = stringResource(R.string.story_record_pick_video), enabled = !busy, onClick = { gallery.launch("video/*") })
                        ApuPremiumButton(label = stringResource(R.string.story_record_record_video), enabled = !busy, onClick = { camera = true })
                    }
                    if (busy) Text(stringResource(R.string.story_record_opening))
                    error?.let { Text(it) }
                    ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
                }
            }
        }
    }
}
