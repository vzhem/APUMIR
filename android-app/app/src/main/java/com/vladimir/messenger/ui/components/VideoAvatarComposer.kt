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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vladimir.messenger.R
import com.vladimir.messenger.data.profile.VideoAvatarInstaller
import com.vladimir.messenger.data.profile.VideoAvatarPolicy
import com.vladimir.messenger.data.profile.VideoAvatarRankGate
import com.vladimir.messenger.data.story.StoryVideoDraft
import com.vladimir.messenger.data.story.StoryVideoExporter
import com.vladimir.messenger.data.story.StoryVideoFiles
import com.vladimir.messenger.data.story.StoryVideoSizeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Создание видеоаватара: те же возможности, что у видеоистории.
 *
 * Поток сознательно повторяет `StoryVideoComposer` — камера (передняя/задняя,
 * пауза, звук, фонарик, живые фильтры лица, замена фона, эффекты голоса),
 * галерея, затем ОДИН общий редактор `StoryVideoEditor` (форма, обрезка, звук,
 * кадр-постер, подпись, сброс по одному параметру) и тот же экспорт
 * `StoryVideoExporter`. Отличий ровно три: предел 15 секунд вместо 60, свои
 * подписи экрана и установка в профиль вместо публикации в ленту.
 *
 * Ранг: видеоаватар открывается с «Круга друзей» (3). Проверка стоит здесь
 * (человек видит отказ сразу, а не после записи) и ещё раз в
 * [VideoAvatarInstaller] — там, где файл сохраняется.
 *
 * Файлы: исходник и экспорт принадлежат этому экрану и удаляются при уходе;
 * установщик делает СВОИ копии, поэтому закрытие окна после установки не
 * ломает готовый аватар.
 */
@Composable
internal fun VideoAvatarComposer(
    onDismiss: () -> Unit,
    onInstalled: (VideoAvatarInstaller.Installed) -> Unit,
) {
    val context = LocalContext.current
    // Ранг читаем один раз на открытие: смена ранга посреди записи не должна
    // отнимать уже сделанную работу, а сохранение всё равно проверяется снова.
    val allowed = remember { VideoAvatarRankGate.canSet(context) }
    if (!allowed) {
        ApuPremiumNoticeDialog(
            title = stringResource(R.string.av_video),
            message = VideoAvatarRankGate.lockedNotice(context),
            onDismiss = onDismiss,
        )
        return
    }
    VideoAvatarFlow(onDismiss = onDismiss, onInstalled = onInstalled)
}

@Composable
private fun VideoAvatarFlow(
    onDismiss: () -> Unit,
    onInstalled: (VideoAvatarInstaller.Installed) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<StoryVideoFiles.Source?>(null) }
    var draft by remember { mutableStateOf<StoryVideoDraft?>(null) }
    var exported by remember { mutableStateOf<StoryVideoExporter.Exported?>(null) }
    // Аватар показывается в круге, поэтому форма по умолчанию круглая;
    // «обычный» сохраняет пропорции внутри той же круглой рамки.
    var circle by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            source?.file?.delete()
            exported?.delete()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                val imported = StoryVideoFiles.import(context, uri)
                source?.file?.delete()
                source = imported
                draft = VideoAvatarPolicy.initialDraft(imported.durationMs, circle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Ошибка видна человеку на экране, но и в логе она нужна:
                // «не открылось видео» без причины невозможно разобрать.
                android.util.Log.w("VideoAvatar", "Cannot import the avatar video", failure)
                error = failure.message ?: context.getString(R.string.story_record_open_video_failed)
            } finally {
                busy = false
            }
        }
    }

    val ready = exported
    val input = source
    when {
        camera -> StoryVideoCamera(
            circle = circle,
            maxDurationMs = VideoAvatarPolicy.MAX_DURATION_MS,
            titleRes = R.string.av_video_editor_title,
            onDismiss = { camera = false },
            onRecorded = {
                source?.file?.delete()
                source = it
                draft = VideoAvatarPolicy.initialDraft(it.durationMs, circle)
                camera = false
            },
        )

        input != null && ready == null -> StoryVideoEditor(
            input,
            draft = checkNotNull(draft),
            // Каждое изменение приводится к правилам аватара: «сбросить всё» в
            // общем редакторе возвращает 60 секунд истории.
            onDraftChange = {
                val clamped = VideoAvatarPolicy.clampToAvatar(it)
                draft = clamped
                circle = clamped.circle
            },
            maxDurationMs = VideoAvatarPolicy.MAX_DURATION_MS,
            // Подпись в видеоаватаре не показывается (запрос владельца
            // 2026-11-06): поле подписи остаётся только у видеоисторий.
            showCaption = false,
            titleRes = R.string.av_video_editor_title,
            coverLabelRes = R.string.av_video_cover_label,
            resetHintRes = R.string.av_video_reset_hint,
            resetDoneBodyRes = R.string.av_video_reset_done_body,
            onDismiss = {
                input.file.delete()
                source = null
                draft = null
            },
            onReady = { exported = it },
        )

        else -> Dialog(onDismissRequest = onDismiss) {
            ApuSettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.av_video_editor_title))
                    if (ready != null) {
                        Text(
                            stringResource(
                                R.string.story_video_export_summary,
                                (ready.media.durationMs / 1000.0).toString(),
                                StoryVideoSizeFormat.megabytes(
                                    ready.media.sizeBytes,
                                    LocalConfiguration.current.locales[0],
                                ),
                            ),
                        )
                        Text(
                            stringResource(
                                if (ready.draft.circle) R.string.story_photo_circle
                                else R.string.story_photo_rectangle,
                            ),
                        )
                        Text(stringResource(R.string.av_video_result_hint))
                        ApuPremiumButton(
                            label = stringResource(
                                if (installing) R.string.av_video_installing else R.string.av_video_install,
                            ),
                            enabled = !installing,
                            onClick = {
                                installing = true
                                error = null
                                scope.launch {
                                    try {
                                        val installed = VideoAvatarInstaller.install(context, ready)
                                        // Копии сделаны: черновик больше не нужен.
                                        ready.delete()
                                        exported = null
                                        onInstalled(installed)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        // Отказ по рангу приходит сюда же: человек
                                        // видит текст, а в логе остаётся причина.
                                        android.util.Log.w("VideoAvatar", "Cannot set the video avatar", failure)
                                        error = failure.message
                                            ?: context.getString(R.string.av_video_failed)
                                        installing = false
                                    }
                                }
                            },
                        )
                        ApuTextAction(
                            label = stringResource(R.string.story_record_back_to_editor),
                            enabled = !installing,
                            onClick = {
                                ready.delete()
                                exported = null
                            },
                        )
                    } else {
                        Text(stringResource(R.string.av_video_source_hint))
                        // Длинный ролик не отклоняется: его можно обрезать, как
                        // в историях. Подсказка объясняет, сколько поместится.
                        if (input != null && !VideoAvatarPolicy.fitsWholeSource(input.durationMs)) {
                            Text(stringResource(R.string.av_video_trim_hint))
                        }
                        StoryVideoShapeSelector(circle, enabled = !busy, onChange = { circle = it })
                        ApuPremiumButton(
                            label = stringResource(R.string.story_record_record_video),
                            enabled = !busy,
                            onClick = { camera = true },
                        )
                        ApuPremiumButton(
                            label = stringResource(R.string.story_record_pick_video),
                            enabled = !busy,
                            onClick = { gallery.launch("video/*") },
                        )
                    }
                    if (busy) Text(stringResource(R.string.story_record_opening))
                    error?.let { Text(it) }
                    ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
                }
            }
        }
    }
}
