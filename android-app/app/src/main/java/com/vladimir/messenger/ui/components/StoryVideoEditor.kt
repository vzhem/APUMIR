package com.vladimir.messenger.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vladimir.messenger.data.story.StoryVideoDraft
import com.vladimir.messenger.data.story.StoryVideoExporter
import com.vladimir.messenger.data.story.StoryVideoFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local editor only. onReady hands off a verified export, it does not claim network publication. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun StoryVideoEditor(
    source: StoryVideoFiles.Source,
    onDismiss: () -> Unit,
    onReady: (StoryVideoExporter.Exported) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(source.file) { mutableStateOf(StoryVideoDraft.fromSource(source.durationMs)) }
    var error by remember(source.file) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val owner = LocalLifecycleOwner.current
    val player = remember(source.file, owner) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        owner.lifecycle.addObserver(observer)
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: androidx.media3.common.PlaybackException) {
                error = "Не удалось воспроизвести видео: ${playbackError.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose {
            exportJob?.cancel()
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, draft.startMs, draft.endMs) {
        player.pause()
        player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(source.file))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(draft.startMs).setEndPositionMs(draft.endMs).build()).build())
        player.prepare()
    }
    LaunchedEffect(player, draft.muted) { player.volume = if (draft.muted) 0f else 1f }

    Dialog(onDismissRequest = { exportJob?.cancel(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ApuSettingsCard(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Видеоистория · до 60 секунд")
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 300.dp))
                Text("Начало: ${draft.startMs / 1000} с · конец: ${draft.endMs / 1000} с")
                // Move the start while preserving an interval within the maximum duration.
                ApuPremiumSlider(value = draft.startMs.toFloat(), enabled = !busy,
                    valueRange = 0f..(source.durationMs - StoryVideoDraft.MIN_DURATION_MS).coerceAtLeast(1).toFloat(),
                    onValueChange = { value ->
                        val start = value.toLong().coerceAtMost(source.durationMs - StoryVideoDraft.MIN_DURATION_MS)
                        val end = (start + draft.durationMs).coerceAtMost(source.durationMs)
                        draft = draft.trim(start, end)
                    })
                Text("Длительность: ${draft.durationMs / 1000.0} с")
                val maxDuration = minOf(StoryVideoDraft.MAX_DURATION_MS, source.durationMs - draft.startMs)
                if (maxDuration > StoryVideoDraft.MIN_DURATION_MS) {
                    ApuPremiumSlider(value = draft.durationMs.toFloat(), enabled = !busy,
                        valueRange = StoryVideoDraft.MIN_DURATION_MS.toFloat()..maxDuration.toFloat(),
                        onValueChange = { draft = draft.trim(draft.startMs, draft.startMs + it.toLong()) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Без звука")
                    ApuPremiumSwitch(checked = draft.muted, enabled = !busy,
                        onCheckedChange = { draft = draft.copy(muted = it) })
                }
                Text("Обложка: ${(draft.coverMs - draft.startMs) / 1000.0} с от начала фрагмента")
                ApuPremiumSlider(value = draft.coverMs.toFloat(), enabled = !busy,
                    valueRange = draft.startMs.toFloat()..(draft.endMs - 1).toFloat(),
                    onValueChange = {
                        draft = draft.copy(coverMs = it.toLong().coerceIn(draft.startMs, draft.endMs - 1))
                        player.pause()
                        player.seekTo(draft.exportedCoverMs)
                    })
                ApuFormTextField(value = draft.caption, label = "Подпись (до 200 символов)",
                    singleLine = false, enabled = !busy,
                    onValueChange = { draft = draft.copy(caption = it.take(StoryVideoDraft.MAX_CAPTION_CHARS)) })
                error?.let { Text(it) }
                if (busy) Text("Подготовка видео: ${(progress * 100).toInt()}%")
                ApuPremiumButton(label = if (busy) "Подготавливаем…" else "Подготовить видео",
                    enabled = !busy, onClick = {
                        player.pause()
                        busy = true
                        error = null
                        progress = 0f
                        val snapshot = draft
                        exportJob = scope.launch {
                            var result: StoryVideoExporter.Exported? = null
                            try {
                                result = StoryVideoExporter.export(context, source, snapshot) { progress = it }
                                check(result.matches(source.file, draft)) { "Редактирование изменилось, повторите экспорт" }
                                onReady(result)
                                result = null // Ownership transfers to caller only after successful handoff.
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Не удалось подготовить видео"
                            } finally {
                                result?.delete()
                                busy = false
                            }
                        }
                    })
                ApuTextAction(label = if (busy) "Отменить подготовку" else "Закрыть",
                    onClick = { if (busy) exportJob?.cancel() else onDismiss() })
            }
        }
    }
}
