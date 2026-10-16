package com.vladimir.messenger.data.story

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Actual re-encoding: exact clipping and audio removal, not only a muted preview player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StoryVideoExporter {
    /** Bound to both the source and exact edit: changing cover/caption also invalidates this result. */
    data class Exported(
        val sourcePath: String,
        val draft: StoryVideoDraft,
        val file: File,
        val cover: File,
        val media: StoryVideoMedia,
    ) {
        fun matches(source: File, current: StoryVideoDraft): Boolean =
            source.absolutePath == sourcePath && current == draft && file.isFile && cover.isFile
        fun delete() { file.delete(); cover.delete() }
    }

    suspend fun export(context: Context, source: StoryVideoFiles.Source, draft: StoryVideoDraft,
                       onProgress: (Float) -> Unit): Exported {
        require(source.durationMs == draft.sourceDurationMs && source.file.isFile)
        val output = File.createTempFile("export-", ".mp4", StoryVideoFiles.directory(context))
        val cover = File.createTempFile("cover-", ".jpg", StoryVideoFiles.directory(context))
        try {
            // Transformer requires a path which does not exist yet.
            check(output.delete())
            transform(context.applicationContext, source.file, output, draft, onProgress)
            val media = StoryVideoFiles.media(output)
            StoryVideoPreparation.Ready(draft, media)
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.absolutePath)
                    // Export is bounded to 720x1280, so API 26's non-scaled extraction is bounded too.
                    val bitmap = retriever.getFrameAtTime(draft.exportedCoverMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST) ?: error("Не удалось получить обложку")
                    try {
                        cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                    } finally { bitmap.recycle() }
                } finally { retriever.release() }
            }
            return Exported(source.file.absolutePath, draft, output, cover, media)
        } catch (error: Throwable) {
            output.delete(); cover.delete()
            throw error
        }
    }

    private suspend fun transform(context: Context, source: File, output: File, draft: StoryVideoDraft,
                                  onProgress: (Float) -> Unit) = withContext(Dispatchers.Main.immediate) {
        coroutineScope {
            val item = MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(draft.startMs)
                    .setEndPositionMs(draft.endMs).build()).build()
            val edited = EditedMediaItem.Builder(item).setRemoveAudio(draft.muted)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(720, 1280,
                    Presentation.LAYOUT_SCALE_TO_FIT)))).build()
            var transformer: Transformer? = null
            val progressJob = launch {
                val progress = ProgressHolder()
                while (true) {
                    val current = transformer
                    if (current != null && current.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progress.progress / 100f)
                    }
                    delay(200)
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val encoder = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_000_000).build()).build()
                    val active = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        }).build()
                    transformer = active
                    continuation.invokeOnCancellation {
                        // Cancellation may be requested from any dispatcher; Transformer is main-thread confined.
                        android.os.Handler(android.os.Looper.getMainLooper()).post { active.cancel(); output.delete() }
                    }
                    try { active.start(edited, output.absolutePath) }
                    catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
                onProgress(1f)
            } finally { progressJob.cancel(); transformer?.cancel() }
        }
    }
}
