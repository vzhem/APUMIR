package com.vladimir.messenger.data.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.vladimir.messenger.util.VoiceNote
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Применяет [VoiceEffect] к готовому голосовому сообщению (AAC/m4a):
 * декодирует в PCM, прогоняет через потоковый DSP и кодирует обратно в m4a.
 * Для FAST/SLOW длительность меняется честно (реsemплер скорости), поэтому
 * пересчитываются и волна, и длительность для имени файла [VoiceNote].
 */
object VoiceEffectFileProcessor {

    data class Result(val file: File, val levels: List<Int>, val durationSec: Int)

    fun process(input: File, effect: VoiceEffect): Result? {
        if (effect == VoiceEffect.ORIGINAL || !input.isFile) return null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: M4aEncoder? = null
        var temp: File? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    format = candidate
                    break
                }
            }
            if (audioTrack < 0 || format == null) return null
            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (sampleRate <= 0) return null

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            temp = File(input.parentFile, "voicefx_${System.currentTimeMillis()}.m4a")
            val sink = M4aEncoder(temp, sampleRate)
            encoder = sink
            sink.start()

            val processor = VoiceEffectProcessor(effect, sampleRate, live = false)
            val speed = when (effect) {
                VoiceEffect.FAST -> 1.25
                VoiceEffect.SLOW -> 0.80
                else -> 1.0
            }
            val resampler = if (speed != 1.0) SpeedResampler(speed) else null
            val stats = WaveformStats(sampleRate)

            var decodedSamples = 0L
            var emittedSamples = 0L
            fun emit(samples: ShortArray, capTotal: Long = Long.MAX_VALUE) {
                if (samples.isEmpty() || emittedSamples >= capTotal) return
                val transformed = resampler?.process(samples) ?: samples
                val allowed = (capTotal - emittedSamples).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val part = if (transformed.size <= allowed) transformed else transformed.copyOf(allowed)
                if (part.isEmpty()) return
                stats.add(part)
                sink.feed(part)
                emittedSamples += part.size
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(inBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            val pcm = decodePcm(outBuffer, bufferInfo, channels)
                            decodedSamples += pcm.size
                            emit(processor.process(pcm))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    }
                }
            }

            // Хвост эффекта (линия задержки высоты/реверберация) не должен
            // пропасть: догреваем тишиной и обрезаем ровно до исходной длины
            // (для скорости — до честно пересчитанной).
            val targetTotal = Math.round(decodedSamples / speed)
            emit(processor.flushSilence(sampleRate / 8), capTotal = targetTotal)
            if (resampler != null) {
                // Ресемплер мог недодать последние сэмплы из-за дробной фазы.
                while (emittedSamples < targetTotal) {
                    val tail = resampler.process(ShortArray(256))
                    if (tail.isEmpty()) break
                    val allowed = (targetTotal - emittedSamples).toInt()
                    val part = tail.copyOf(minOf(tail.size, allowed))
                    stats.add(part)
                    sink.feed(part)
                    emittedSamples += part.size
                }
            }

            sink.queueEndOfStream()
            // Контейнер нужно финализировать ДО проверки длины и переименования.
            encoder?.release()
            encoder = null
            val outFile = temp
            if (outFile == null || !outFile.isFile || outFile.length() == 0L) return null
            val durationSec = ((emittedSamples + sampleRate / 2) / sampleRate).toInt().coerceAtLeast(1)
            val named = File(input.parentFile, VoiceNote.fileName(stats.levels(), durationSec))
            if (named.exists()) named.delete()
            val finalFile = if (outFile.renameTo(named)) named else outFile
            return Result(finalFile, stats.levels(), durationSec)
        } catch (e: Exception) {
            android.util.Log.w("VoiceEffectFile", "effect processing failed: ${e.message}", e)
            temp?.delete()
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodePcm(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channels: Int): ShortArray {
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(info.offset)
        duplicate.limit(info.offset + info.size)
        val shortBuffer = duplicate.asShortBuffer()
        val totalShorts = shortBuffer.remaining()
        if (channels <= 1) {
            return ShortArray(totalShorts).also { shortBuffer.get(it) }
        }
        val frames = totalShorts / channels
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                sum += shortBuffer.get(frame * channels + channel).toInt()
            }
            out[frame] = (sum / channels).toShort()
        }
        return out
    }

    /** Линейный ресемплер скорости воспроизведения для FAST/SLOW. */
    private class SpeedResampler(private val step: Double) {
        private var nextPosition = 0.0
        private var chunkStart = 0L
        private var previous: Float = 0f
        private var hasPrevious = false

        fun process(input: ShortArray): ShortArray {
            if (input.isEmpty()) return ShortArray(0)
            val out = ArrayList<Short>((input.size / step).toInt() + 2)
            val lastGlobal = chunkStart + input.size - 1
            while (nextPosition <= lastGlobal) {
                val local = nextPosition - chunkStart
                val index = floor(local).toInt()
                val fraction = (local - index).toFloat()
                val current: Float
                val next: Float
                when {
                    index < 0 -> {
                        current = if (hasPrevious) previous else input[0].toFloat()
                        next = input[0].toFloat()
                    }
                    index >= input.lastIndex -> {
                        current = input[input.lastIndex].toFloat()
                        next = current
                    }
                    else -> {
                        current = input[index].toFloat()
                        next = input[index + 1].toFloat()
                    }
                }
                out.add((current + (next - current) * fraction).toInt().toShort())
                nextPosition += step
            }
            previous = input[input.lastIndex].toFloat()
            hasPrevious = true
            chunkStart += input.size
            return out.toShortArray()
        }
    }

    /** Волна 0..9 по 100 мс — та же шкала, что у VoiceRecorder. */
    private class WaveformStats(sampleRate: Int) {
        private val bucketSize = (sampleRate / 10).coerceAtLeast(1)
        private val levels = ArrayList<Int>()
        private var sumSquares = 0.0
        private var count = 0

        fun add(samples: ShortArray) {
            for (sample in samples) {
                val normalized = sample / 32768.0
                sumSquares += normalized * normalized
                count++
                if (count >= bucketSize) finishBucket()
            }
        }

        fun levels(): List<Int> {
            if (count > 0) finishBucket()
            return levels.toList()
        }

        private fun finishBucket() {
            val rms = if (count == 0) 0.0 else sqrt(sumSquares / count)
            levels.add((sqrt(rms) * 9.0).toInt().coerceIn(0, 9))
            sumSquares = 0.0
            count = 0
        }
    }

    /** Синхронный AAC-LC кодер в MPEG-4 контейнер. */
    private class M4aEncoder(
        private val output: File,
        private val sampleRate: Int,
    ) {
        private var encoder: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private val bufferInfo = MediaCodec.BufferInfo()
        private var trackIndex = -1
        private var muxerStarted = false
        private var samplesWritten = 0L
        private var ended = false

        fun start() {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
        }

        fun feed(samples: ShortArray) {
            val codec = encoder ?: return
            var offset = 0
            while (offset < samples.size) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex < 0) {
                    drain(endOfStream = false)
                    continue
                }
                val inBuffer = codec.getInputBuffer(inIndex) ?: continue
                inBuffer.clear()
                inBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val capacity = minOf(inBuffer.remaining() / 2, samples.size - offset)
                val byteBuffer = inBuffer.asShortBuffer()
                byteBuffer.put(samples, offset, capacity)
                val bytes = capacity * 2
                val ptsUs = samplesWritten * 1_000_000L / sampleRate
                codec.queueInputBuffer(inIndex, 0, bytes, ptsUs, 0)
                samplesWritten += capacity
                offset += capacity
                drain(endOfStream = false)
            }
        }

        fun queueEndOfStream() {
            val codec = encoder ?: return
            if (ended) return
            var queued = false
            while (!queued) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(
                        inIndex, 0, 0,
                        samplesWritten * 1_000_000L / sampleRate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    queued = true
                } else {
                    drain(endOfStream = false)
                }
            }
            ended = true
            drain(endOfStream = true)
        }

        private fun drain(endOfStream: Boolean) {
            val codec = encoder ?: return
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            if (trackIndex >= 0) {
                                muxer?.start()
                                muxerStarted = true
                            }
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer?.writeSampleData(trackIndex, outBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
            }
        }

        fun release() {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
        }
    }
}
