package com.vladimir.messenger.data.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Применяет [VoiceEffect] к звуковой дорожке готового видео (истории):
 * звук прогоняется через тот же DSP, что голосовые сообщения, видеодорожка
 * копируется в новый контейнер без перекодирования. Null — эффекта нет,
 * звука нет или что-то не срослось: вызывающий оставит исходный файл.
 */
object VoiceEffectVideoProcessor {

    fun apply(input: File, effect: VoiceEffect): File? {
        if (effect == VoiceEffect.ORIGINAL || !input.isFile) return null
        var processedAudio: File? = null
        var output: File? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            // Шаг 1: звук из mp4 декодируется/обрабатывается/кодируется в m4a
            // существующим файловым процессором (он берёт первую аудиодорожку).
            processedAudio = VoiceEffectFileProcessor.process(input, effect)?.file ?: return null

            // Шаг 2: чистый ремукс — видеосэмплы оригинала + новый звук.
            videoExtractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
            var videoTrack = -1
            var videoFormat: android.media.MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if (format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrack < 0 || videoFormat == null) return null

            audioExtractor = MediaExtractor().apply { setDataSource(processedAudio.absolutePath) }
            var audioTrack = -1
            var audioFormat: android.media.MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrack < 0 || audioFormat == null) return null

            output = File(input.parentFile, "voicefx_${input.nameWithoutExtension}_${System.currentTimeMillis()}.mp4")
            val mediaMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            val outVideoTrack = mediaMuxer.addTrack(videoFormat)
            val outAudioTrack = mediaMuxer.addTrack(audioFormat)
            mediaMuxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            videoExtractor.selectTrack(videoTrack)
            while (true) {
                val size = videoExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, videoExtractor.sampleTime, videoExtractor.sampleFlags)
                mediaMuxer.writeSampleData(outVideoTrack, buffer, info)
                videoExtractor.advance()
            }

            audioExtractor.selectTrack(audioTrack)
            while (true) {
                val size = audioExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                mediaMuxer.writeSampleData(outAudioTrack, buffer, info)
                audioExtractor.advance()
            }

            mediaMuxer.stop()
            val result = output
            return if (result.isFile && result.length() > 0) result else null
        } catch (e: Exception) {
            android.util.Log.w("VoiceEffectVideo", "video voice effect failed: ${e.message}", e)
            output?.delete()
            return null
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            processedAudio?.delete()
        }
    }
}
