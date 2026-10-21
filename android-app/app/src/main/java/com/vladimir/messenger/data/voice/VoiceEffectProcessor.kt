package com.vladimir.messenger.data.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Потоковый DSP-процессор изменения голоса.
 *
 * Работает на PCM16 mono и не меняет число сэмплов: поэтому один и тот же код
 * применяется и к живому потоку звонка (кадры по 20 мс), и к голосовому
 * сообщению. Высота меняется двойной линией задержки с треугольным
 * кроссфейдом (классический вокальный гармонизатор), тембр — фильтрами,
 * кольцевой модуляцией, дисторшном и ревербератором Шрёдера.
 */
class VoiceEffectProcessor(
    val effect: VoiceEffect,
    private val sampleRate: Int,
    live: Boolean = false,
) {
    private data class Spec(
        val pitch: Float,
        val lowPassHz: Float? = null,
        val highPassHz: Float? = null,
        val drive: Float = 1f,
        val ringHz: Float = 0f,
        val ringMix: Float = 0f,
        val tremoloHz: Float = 0f,
        val tremoloDepth: Float = 0f,
        val reverbMix: Float = 0f,
        val quantizeLevels: Float = 0f,
        val subMix: Float = 0f,
        val gain: Float = 1f,
        val underwater: Boolean = false,
    )

    private val spec: Spec = when (effect) {
        VoiceEffect.ORIGINAL -> Spec(1f)
        VoiceEffect.DARTH_VADER -> Spec(pitch = 0.68f, lowPassHz = 2_200f, drive = 1.6f, gain = 1.08f)
        VoiceEffect.MINION -> Spec(pitch = 1.62f, highPassHz = 450f, gain = 0.96f)
        VoiceEffect.ROBOT -> Spec(
            pitch = 1f, highPassHz = 280f, lowPassHz = 3_900f,
            ringHz = 48f, ringMix = 0.82f, quantizeLevels = 34f,
        )
        VoiceEffect.DEMON -> Spec(
            pitch = 0.55f, lowPassHz = 1_700f, drive = 3.0f,
            reverbMix = 0.18f, gain = 1.1f,
        )
        VoiceEffect.ALIEN -> Spec(
            pitch = 1.23f, highPassHz = 300f,
            ringHz = 12f, ringMix = 0.38f, tremoloHz = 8f, tremoloDepth = 0.42f,
        )
        VoiceEffect.MONSTER -> Spec(
            pitch = 0.69f, lowPassHz = 2_500f, drive = 3.2f,
            reverbMix = 0.12f, subMix = 0.24f, gain = 1.08f,
        )
        VoiceEffect.CHILD -> Spec(pitch = 1.38f, highPassHz = 320f)
        VoiceEffect.FEMALE -> Spec(pitch = 1.18f, highPassHz = 240f, gain = 0.98f)
        VoiceEffect.MALE -> Spec(pitch = 0.84f, lowPassHz = 3_100f, gain = 1.02f)
        VoiceEffect.CATHEDRAL -> Spec(pitch = 1f, reverbMix = 0.46f)
        VoiceEffect.RADIO -> Spec(
            pitch = 1f, highPassHz = 480f, lowPassHz = 2_900f,
            drive = 2.4f, quantizeLevels = 56f, gain = 1.12f,
        )
        VoiceEffect.UNDERWATER -> Spec(pitch = 0.92f, underwater = true)
        // В живом звонке длительность менять нельзя (поток уйдёт из реального
        // времени), поэтому «быстрый/медленный» там — это высота+тембр.
        // В файле голосового сообщения скорость меняется честно ресемплером.
        VoiceEffect.FAST -> Spec(pitch = if (live) 1.28f else 1f, highPassHz = 180f)
        VoiceEffect.SLOW -> Spec(pitch = if (live) 0.74f else 1f, lowPassHz = 3_300f)
    }

    private val pitchShifter = PitchShifter(sampleRate, spec.pitch)
    private val lowPass = spec.lowPassHz?.let { Biquad.lowPass(it, sampleRate) }
    private val highPass = spec.highPassHz?.let { Biquad.highPass(it, sampleRate) }
    private val reverb = if (spec.reverbMix > 0f) SchroederReverb(sampleRate) else null

    private var oscillatorPhase = 0.0
    private var tremoloPhase = 0.0
    private var wobblePhase = 0.0
    private var underwaterState = 0f
    private var previousPitchSample = 0f
    private var subSign = 1f
    private var subSmooth = 0f

    fun process(input: ShortArray, length: Int = input.size): ShortArray {
        val out = ShortArray(length)
        if (effect == VoiceEffect.ORIGINAL) {
            input.copyInto(out, 0, 0, length)
            return out
        }
        for (i in 0 until length) {
            out[i] = processSample(input[i])
        }
        return out
    }

    /** PCM16 little-endian кадр той же длины (для звонка). */
    fun processPcmLe(frame: ByteArray): ByteArray {
        if (effect == VoiceEffect.ORIGINAL) return frame.copyOf()
        val samples = ShortArray(frame.size / 2)
        for (i in samples.indices) {
            val lo = frame[i * 2].toInt() and 0xff
            val hi = frame[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        val processed = process(samples)
        val out = ByteArray(frame.size)
        for (i in processed.indices) {
            val v = processed[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Тишина на прогрев линии задержки — конец файла голосового сообщения. */
    fun flushSilence(samples: Int): ShortArray = process(ShortArray(samples))

    private fun processSample(inputSample: Short): Short {
        var x = inputSample / 32768f
        x = pitchShifter.process(x)

        if (spec.underwater) {
            wobblePhase += 2.0 * PI * 0.35 / sampleRate
            val cutoff = 760.0 + 430.0 * sin(wobblePhase)
            val alpha = (1.0 - exp(-2.0 * PI * cutoff / sampleRate)).toFloat()
            underwaterState += alpha * (x - underwaterState)
            x = underwaterState
        } else {
            highPass?.let { x = it.process(x) }
            lowPass?.let { x = it.process(x) }
        }

        if (spec.subMix > 0f) {
            if (previousPitchSample <= 0f && x > 0f) subSign = -subSign
            previousPitchSample = x
            subSmooth += 0.18f * (subSign - subSmooth)
            x += spec.subMix * subSmooth * minOf(1f, abs(x) * 3f)
        }

        if (spec.ringMix > 0f) {
            oscillatorPhase += 2.0 * PI * spec.ringHz / sampleRate
            val carrier = sin(oscillatorPhase).toFloat()
            x = x * (1f - spec.ringMix) + x * carrier * spec.ringMix
        }

        val tremoloHz = if (spec.underwater) 1.15f else spec.tremoloHz
        val tremoloDepth = if (spec.underwater) 0.24f else spec.tremoloDepth
        if (tremoloDepth > 0f) {
            tremoloPhase += 2.0 * PI * tremoloHz / sampleRate
            val gain = 1f - tremoloDepth / 2f + tremoloDepth / 2f * sin(tremoloPhase).toFloat()
            x *= gain
        }

        if (spec.drive > 1.01f) {
            x = (tanh((spec.drive * x).toDouble()) / tanh(spec.drive.toDouble())).toFloat()
        }

        if (spec.quantizeLevels > 0f) {
            val levels = spec.quantizeLevels
            x = (kotlin.math.round(x * levels) / levels)
        }

        reverb?.let { verb ->
            val wet = verb.process(x)
            x = x * (1f - spec.reverbMix * 0.72f) + wet * spec.reverbMix
        }

        x *= spec.gain
        // Мягкий ограничитель вместо жёсткого клиппинга: без треска на пиках.
        if (x > 0.92f || x < -0.92f) x = tanh(x.toDouble()).toFloat()
        return (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
}

/** Изменение высоты двойной линией задержки с треугольным кроссфейдом. */
private class PitchShifter(
    sampleRate: Int,
    private val pitch: Float,
) {
    private val window = (sampleRate * 0.048).toInt().coerceIn(256, 4_096)
    private val baseDelay = window * 1.25f
    private val buffer = FloatArray(BUFFER_SIZE)
    private var writeIndex = BUFFER_SIZE.toLong()
    private var delayA = baseDelay + window / 2f
    private var delayB = baseDelay

    fun process(input: Float): Float {
        if (kotlin.math.abs(pitch - 1f) < 0.001f) return input
        buffer[(writeIndex and MASK).toInt()] = input

        delayA = advance(delayA)
        delayB = advance(delayB)
        val weightA = triangularWeight(delayA)
        val weightB = triangularWeight(delayB)
        val out = read(delayA) * weightA + read(delayB) * weightB
        writeIndex++
        return out
    }

    private fun advance(delay: Float): Float {
        var d = delay - (pitch - 1f)
        if (d < baseDelay) d += window
        if (d >= baseDelay + window) d -= window
        return d
    }

    private fun triangularWeight(delay: Float): Float {
        val phase = ((delay - baseDelay) / window).coerceIn(0f, 1f)
        return 1f - abs(2f * phase - 1f)
    }

    private fun read(delay: Float): Float {
        val position = writeIndex - delay.toDouble()
        val index = floor(position).toLong()
        val fraction = (position - index).toFloat()
        val a = buffer[(index and MASK).toInt()]
        val b = buffer[((index + 1) and MASK).toInt()]
        return a + (b - a) * fraction
    }

    private companion object {
        const val BUFFER_SIZE = 16_384
        const val MASK = (BUFFER_SIZE - 1).toLong()
    }
}

private class Biquad(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    companion object {
        fun lowPass(freqHz: Float, sampleRate: Int): Biquad = make(freqHz, sampleRate, low = true)
        fun highPass(freqHz: Float, sampleRate: Int): Biquad = make(freqHz, sampleRate, low = false)

        private fun make(freqHz: Float, sampleRate: Int, low: Boolean): Biquad {
            val freq = freqHz.coerceIn(40f, sampleRate * 0.42f)
            val w = 2.0 * PI * freq / sampleRate
            val alpha = sin(w) / (2.0 * 0.7071)
            val c = cos(w)
            val a0 = 1.0 + alpha
            return if (low) {
                Biquad(
                    b0 = ((1 - c) / 2 / a0).toFloat(),
                    b1 = ((1 - c) / a0).toFloat(),
                    b2 = ((1 - c) / 2 / a0).toFloat(),
                    a1 = (-2 * c / a0).toFloat(),
                    a2 = ((1 - alpha) / a0).toFloat(),
                )
            } else {
                Biquad(
                    b0 = ((1 + c) / 2 / a0).toFloat(),
                    b1 = (-(1 + c) / a0).toFloat(),
                    b2 = ((1 + c) / 2 / a0).toFloat(),
                    a1 = (-2 * c / a0).toFloat(),
                    a2 = ((1 - alpha) / a0).toFloat(),
                )
            }
        }
    }
}

/** Ревербератор Шрёдера: 4 гребёнки + 2 allpass — «собор» без внешних библиотек. */
private class SchroederReverb(sampleRate: Int) {
    private class Comb(delay: Int) {
        val buffer = FloatArray(delay)
        var index = 0
        var dampState = 0f
    }

    private class AllPass(delay: Int) {
        val buffer = FloatArray(delay)
        var index = 0
    }

    private val scale = sampleRate / 44_100.0
    private val combs = listOf(1557, 1617, 1491, 1422).map { Comb((it * scale).toInt().coerceAtLeast(16)) }
    private val allPasses = listOf(556, 441).map { AllPass((it * scale).toInt().coerceAtLeast(8)) }

    fun process(x: Float): Float {
        var wet = 0f
        for (comb in combs) {
            val delayed = comb.buffer[comb.index]
            comb.dampState += 0.28f * (delayed - comb.dampState)
            comb.buffer[comb.index] = x + comb.dampState * 0.84f
            comb.index = (comb.index + 1) % comb.buffer.size
            wet += delayed
        }
        wet *= 0.25f
        for (pass in allPasses) {
            val delayed = pass.buffer[pass.index]
            val y = delayed - 0.5f * wet
            pass.buffer[pass.index] = wet + 0.5f * y
            pass.index = (pass.index + 1) % pass.buffer.size
            wet = y
        }
        return wet
    }
}
