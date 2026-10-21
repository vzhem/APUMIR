package com.vladimir.messenger.data.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEffectProcessorTest {

    private fun sine(sampleRate: Int, hz: Double, seconds: Double, amplitude: Int = 12_000): ShortArray {
        val count = (sampleRate * seconds).toInt()
        return ShortArray(count) { i ->
            (amplitude * sin(2.0 * PI * hz * i / sampleRate)).toInt().toShort()
        }
    }

    /** Положительные переходы через ноль ≈ частота основного тона. */
    private fun risingCrossings(samples: ShortArray, from: Int): Int {
        var count = 0
        for (i in maxOf(1, from) until samples.size) {
            if (samples[i - 1] < 0 && samples[i] >= 0) count++
        }
        return count
    }

    @Test
    fun originalIsIdentity() {
        val input = sine(16_000, 220.0, 0.25)
        val out = VoiceEffectProcessor(VoiceEffect.ORIGINAL, 16_000).process(input)
        assertTrue(input.contentEquals(out))
    }

    @Test
    fun allEffectsKeepLengthAndProduceSound() {
        val input = sine(16_000, 220.0, 1.0)
        for (effect in VoiceEffect.entries) {
            val out = VoiceEffectProcessor(effect, 16_000, live = true).process(input)
            assertEquals("length for $effect", input.size, out.size)
            val peak = out.maxOf { abs(it.toInt()) }
            assertTrue("silent output for $effect (peak=$peak)", peak > 500)
        }
    }

    @Test
    fun pitchGoesUpForMinionAndDownForVader() {
        val sampleRate = 16_000
        val input = sine(sampleRate, 220.0, 1.5)
        val skip = sampleRate / 2 // прогрев линии задержки
        val base = risingCrossings(input, skip)

        val minion = VoiceEffectProcessor(VoiceEffect.MINION, sampleRate).process(input)
        val vader = VoiceEffectProcessor(VoiceEffect.DARTH_VADER, sampleRate).process(input)

        assertTrue(
            "minion crossings ${risingCrossings(minion, skip)} vs base $base",
            risingCrossings(minion, skip) > base * 1.25,
        )
        assertTrue(
            "vader crossings ${risingCrossings(vader, skip)} vs base $base",
            risingCrossings(vader, skip) < base * 0.85,
        )
    }

    @Test
    fun pcmFrameLengthPreservedForCall() {
        val frame = ByteArray(640) { (it % 251).toByte() }
        val out = VoiceEffectProcessor(VoiceEffect.DARTH_VADER, 16_000, live = true).processPcmLe(frame)
        assertEquals(frame.size, out.size)
    }
}
