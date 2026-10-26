package com.vladimir.messenger.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Живой текст звонка: потоковое распознавание обеих сторон разговора (Vosk).
 *
 * Звук звонка — PCM 16 кГц моно, ровно формат распознавателя, поэтому кадры
 * движка уходят в него как есть. У каждой стороны свой Recognizer и своя
 * текущая фраза: завершённые фразы падают в общий список строками, незаконченная
 * обновляется на месте. Всё считается на телефоне, звук наружу не уходит.
 */
class CallCaptioner(model: Model) {

    enum class Speaker { ME, PEER }

    data class Line(val speaker: Speaker, val text: String, val done: Boolean)

    private val lock = Any()
    private val recognizers = HashMap<Speaker, Recognizer>()
    private val buffers = HashMap<Speaker, ByteArray>()
    private val doneLines = ArrayList<Line>()
    private val partials = HashMap<Speaker, String>()
    @Volatile private var stopped = false

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    init {
        recognizers[Speaker.ME] = Recognizer(model, SAMPLE_RATE.toFloat())
        recognizers[Speaker.PEER] = Recognizer(model, SAMPLE_RATE.toFloat())
    }

    /** PCM-кадр одной стороны (20 мс из движка). Вызывается из аудиопотоков. */
    fun feed(speaker: Speaker, pcm: ByteArray) {
        if (stopped) return
        synchronized(lock) {
            if (stopped) return
            val recognizer = recognizers[speaker] ?: return
            val buffered = (buffers[speaker] ?: ByteArray(0)) + pcm
            if (buffered.size < CHUNK_BYTES) {
                buffers[speaker] = buffered
                return
            }
            buffers[speaker] = ByteArray(0)
            try {
                if (recognizer.acceptWaveForm(buffered, buffered.size)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    partials.remove(speaker)
                    if (text.isNotEmpty()) {
                        doneLines.add(Line(speaker, text, done = true))
                        while (doneLines.size > MAX_LINES) doneLines.removeAt(0)
                    }
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                    if (partial.isEmpty()) partials.remove(speaker) else partials[speaker] = partial
                }
                publish()
            } catch (_: Exception) {
                // Один битый фрагмент не должен ронять ни звонок, ни подписи.
            }
        }
    }

    private fun publish() {
        val current = ArrayList<Line>(doneLines.size + partials.size)
        current.addAll(doneLines)
        for (speaker in Speaker.entries) {
            partials[speaker]?.takeIf { it.isNotEmpty() }?.let {
                current.add(Line(speaker, it, done = false))
            }
        }
        _lines.value = current
    }

    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            for ((speaker, recognizer) in recognizers) {
                try {
                    val text = JSONObject(recognizer.finalResult).optString("text").trim()
                    if (text.isNotEmpty()) {
                        doneLines.add(Line(speaker, text, done = true))
                    }
                } catch (e: Exception) {
                    // Один нераспознанный фрагмент не должен ронять ни звонок, ни
                    // остальные подписи — но причина должна быть видна.
                    android.util.Log.w("CallCaptioner", "segment recognition failed: ${e.message}")
                }
                runCatching { recognizer.close() }
            }
            recognizers.clear()
            partials.clear()
            publish()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        /** Подаём распознавателю кусками по полсекунды: и нагрузка ровнее, и фразы не рвутся. */
        const val CHUNK_BYTES = SAMPLE_RATE // 16000 байт = 0.5 с * 16 бит
        const val MAX_LINES = 40
    }
}
