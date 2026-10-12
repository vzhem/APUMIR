package com.vladimir.messenger.data.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.vladimir.messenger.util.VoiceNote
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Запись голоса в AAC/m4a. Пока идёт запись, раз в 100 мс снимаем громкость:
 * по ней строится волна, которая поедет в имени файла.
 */
class VoiceRecorder(private val context: Context) {

    class Result(val file: File, val levels: List<Int>, val durationSec: Int)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var sampler: Job? = null
    private val levels = ArrayList<Int>()

    /** Текущая громкость 0-9 для живого индикатора. */
    @Volatile var level: Int = 0
        private set

    val isRecording: Boolean get() = recorder != null

    /** Запустить запись. false, если микрофон недоступен. */
    fun start(scope: CoroutineScope): Boolean {
        if (recorder != null) return true
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val out = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        val rec = try {
            (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceRecorder", "recorder start failed", e)
            out.delete()
            return false
        }
        recorder = rec
        file = out
        startedAt = System.currentTimeMillis()
        levels.clear()
        sampler = scope.launch {
            while (recorder != null) {
                val amp = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                // Корень, а не линейная шкала: тихая речь тоже даёт видимый столбик.
                val lvl = (sqrt(amp / 32767f) * 9f).toInt().coerceIn(0, 9)
                level = lvl
                levels.add(lvl)
                delay(100)
            }
        }
        return true
    }

    /** Остановить и отдать файл с волной. null, если записи не было или она короткая. */
    fun stop(): Result? {
        val rec = recorder ?: return null
        recorder = null
        sampler?.cancel()
        sampler = null
        level = 0
        val out = file
        file = null
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
        return try {
            rec.stop()
            if (out == null || !out.exists() || out.length() == 0L || seconds < 1) {
                out?.delete()
                null
            } else {
                // Файл переименовываем в имя с волной: по нему его увидит получатель.
                val named = File(out.parentFile, VoiceNote.fileName(levels.toList(), seconds))
                val finalFile = if (out.renameTo(named)) named else out
                Result(finalFile, levels.toList(), seconds)
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceRecorder", "recorder stop failed", e)
            out?.delete()
            null
        } finally {
            rec.release()
        }
    }

    /** Отменить запись и удалить файл. */
    fun cancel() {
        val rec = recorder
        recorder = null
        sampler?.cancel()
        sampler = null
        level = 0
        try { rec?.stop() } catch (_: Exception) { }
        rec?.release()
        file?.delete()
        file = null
    }

    /** Имя, под которым файл уйдёт получателю. */
    fun nameFor(result: Result): String = VoiceNote.fileName(result.levels, result.durationSec)
}
