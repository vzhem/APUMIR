package com.vladimir.messenger.data.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Голосовое сообщение -> текст, полностью на телефоне (Vosk, офлайн).
 *
 * Модель языка маленькая (~40 МБ): в APK её нет, она скачивается один раз при
 * первом переводе голосового и дальше живёт в файлах приложения. Распознанный
 * текст кэшируется рядом, поэтому повторное нажатие мгновенно.
 *
 * Аудио наружу не уходит: ни модель, ни распознавание сети не требуют.
 */
object VoiceTranscriber {

    sealed interface Outcome {
        data class Success(val text: String) : Outcome
        data class Failure(val reason: Reason) : Outcome
    }

    enum class Reason { MODEL_DOWNLOAD, DECODE, TOO_LONG, EMPTY, ENGINE }

    /** Этапы для подписи под кнопкой: модель качается только в первый раз. */
    enum class Stage { MODEL, DECODE, RECOGNIZE }

    private const val SAMPLE_RATE = 16000
    /** Потолок длины: 12 минут голоса (PCM 16 кГц моно укладывается в память). */
    private const val MAX_PCM_BYTES = 12 * 60 * SAMPLE_RATE * 2

    private val modelMutex = Mutex()
    @Volatile private var model: Model? = null
    @Volatile private var modelLang: String? = null

    /** Язык модели: русский для русской локали, английский для остальных. */
    fun language(): String = if (Locale.getDefault().language == "ru") "ru" else "en"

    private fun modelUrl(lang: String): String = if (lang == "ru") {
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    } else {
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private fun modelDir(context: Context, lang: String): File =
        File(context.filesDir, "voice_models/$lang")

    private fun modelReady(context: Context, lang: String): Boolean =
        File(modelDir(context, lang), "am").isDirectory

    private fun cacheFile(context: Context, file: File): File {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(context.filesDir, "voice_transcripts/$hash.txt")
    }

    /** Кэшированный текст этого файла, если его уже распознавали. */
    fun cached(context: Context, file: File): String? = try {
        cacheFile(context, file).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    suspend fun transcribe(
        context: Context,
        file: File,
        onStage: (Stage) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        cached(context, file)?.let { return@withContext Outcome.Success(it) }
        val lang = language()
        val loaded = try {
            ensureModel(context, lang, onStage)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@withContext Outcome.Failure(Reason.MODEL_DOWNLOAD)
        }
        onStage(Stage.DECODE)
        val pcm = try {
            decodeToPcm16k(file)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@withContext Outcome.Failure(Reason.DECODE)
        } ?: return@withContext Outcome.Failure(Reason.DECODE)
        if (pcm.size > MAX_PCM_BYTES) return@withContext Outcome.Failure(Reason.TOO_LONG)
        if (pcm.size < SAMPLE_RATE) return@withContext Outcome.Failure(Reason.EMPTY)
        onStage(Stage.RECOGNIZE)
        val text = try {
            recognize(loaded, pcm)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@withContext Outcome.Failure(Reason.ENGINE)
        }
        if (text.isBlank()) return@withContext Outcome.Failure(Reason.EMPTY)
        try {
            val cache = cacheFile(context, file)
            cache.parentFile?.mkdirs()
            cache.writeText(text)
        } catch (_: Exception) {
            // Кэш не записался — текст всё равно покажем.
        }
        Outcome.Success(text)
    }

    /**
     * Загруженная модель языка (скачает, если ещё нет) или null при неудаче.
     * Общий вход для перевода голосовых и живого текста звонка.
     */
    suspend fun loadModel(context: Context, onStage: (Stage) -> Unit = {}): Model? = try {
        ensureModel(context, language(), onStage)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    private suspend fun ensureModel(context: Context, lang: String, onStage: (Stage) -> Unit): Model {
        model?.takeIf { modelLang == lang }?.let { return it }
        return modelMutex.withLock {
            model?.takeIf { modelLang == lang }?.let { return@withLock it }
            if (!modelReady(context, lang)) {
                onStage(Stage.MODEL)
                downloadModel(context, lang)
            }
            val loaded = Model(modelDir(context, lang).absolutePath)
            model = loaded
            modelLang = lang
            loaded
        }
    }

    private suspend fun downloadModel(context: Context, lang: String) {
        val target = modelDir(context, lang)
        val tmpDir = File(context.filesDir, "voice_models/tmp_$lang")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        val zipFile = File(tmpDir, "model.zip")
        val connection = URL(modelUrl(lang)).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.inputStream.use { input ->
            FileOutputStream(zipFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContextEnsureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        if (zipFile.length() < 5_000_000L) throw IllegalStateException("model zip too small")
        // Внутри архива одна папка vosk-model-.../ — её содержимое кладём в modelDir.
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val relative = entry.name.substringAfter('/', "")
                if (relative.isNotEmpty() && !relative.contains("..")) {
                    val outFile = File(target, relative)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        tmpDir.deleteRecursively()
        if (!modelReady(context, lang)) throw IllegalStateException("model incomplete")
    }

    private suspend fun currentCoroutineContextEnsureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }

    /** m4a/AAC -> PCM 16 бит, 16 кГц, моно. null, если дорожку не удалось прочитать. */
    private fun decodeToPcm16k(file: File): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val raw = ArrayList<ByteArray>()
                var rawBytes = 0
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            outputBuffer.get(chunk)
                            raw.add(chunk)
                            rawBytes += chunk.size
                            if (rawBytes > MAX_PCM_BYTES * 4) return null // заведомо длиннее потолка
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                val joined = ByteArray(rawBytes)
                var offset = 0
                for (chunk in raw) {
                    chunk.copyInto(joined, offset)
                    offset += chunk.size
                }
                return resampleTo16kMono(joined, sourceRate, channels)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Линейный ресемплинг в 16 кГц моно: для распознавания речи точности хватает. */
    private fun resampleTo16kMono(pcm: ByteArray, sourceRate: Int, channels: Int): ByteArray {
        val frameSize = 2 * channels.coerceAtLeast(1)
        val frames = pcm.size / frameSize
        if (frames == 0 || sourceRate <= 0) return ByteArray(0)
        fun sampleAt(frame: Int): Int {
            var sum = 0
            for (channel in 0 until channels.coerceAtLeast(1)) {
                val offset = frame * frameSize + channel * 2
                val low = pcm[offset].toInt() and 0xFF
                val high = pcm[offset + 1].toInt()
                sum += (high shl 8) or low
            }
            return sum / channels.coerceAtLeast(1)
        }
        if (sourceRate == SAMPLE_RATE && channels == 1) return pcm
        val outFrames = (frames.toLong() * SAMPLE_RATE / sourceRate).toInt()
        val out = ByteArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val sourcePos = i.toDouble() * sourceRate / SAMPLE_RATE
            val index = sourcePos.toInt()
            val fraction = sourcePos - index
            val first = sampleAt(index.coerceAtMost(frames - 1))
            val second = sampleAt((index + 1).coerceAtMost(frames - 1))
            val value = (first + (second - first) * fraction).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun recognize(model: Model, pcm: ByteArray): String {
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        try {
            val texts = ArrayList<String>()
            var offset = 0
            val chunkBytes = SAMPLE_RATE // 0.5 секунды за шаг
            while (offset < pcm.size) {
                val length = minOf(chunkBytes, pcm.size - offset)
                if (recognizer.acceptWaveForm(pcm.copyOfRange(offset, offset + length), length)) {
                    JSONObject(recognizer.result).optString("text")
                        .takeIf { it.isNotBlank() }?.let { texts.add(it) }
                }
                offset += length
            }
            JSONObject(recognizer.finalResult).optString("text")
                .takeIf { it.isNotBlank() }?.let { texts.add(it) }
            return texts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        } finally {
            recognizer.close()
        }
    }
}
