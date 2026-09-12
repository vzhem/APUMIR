package com.vladimir.messenger.data.call

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdpcmCodecTest {

    private fun sine(frames: Int, hz: Double, amplitude: Int, phase0: Double = 0.0): List<ByteArray> {
        var n = 0
        return List(frames) {
            val pcm = ByteArray(AdpcmCodec.PCM_FRAME_BYTES)
            for (i in 0 until AdpcmCodec.FRAME_SAMPLES) {
                val v = (amplitude * sin(phase0 + 2 * PI * hz * n / 16_000.0)).toInt()
                pcm[2 * i] = v.toByte()
                pcm[2 * i + 1] = (v shr 8).toByte()
                n++
            }
            pcm
        }
    }

    private fun samples(pcm: ByteArray): IntArray = IntArray(pcm.size / 2) { i ->
        ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
    }

    /** Отношение сигнал/шум в дБ между оригиналом и восстановленным. */
    private fun snrDb(original: List<ByteArray>, decoded: List<ByteArray>): Double {
        var signal = 0.0
        var noise = 0.0
        original.zip(decoded).forEach { (o, d) ->
            val a = samples(o)
            val b = samples(d)
            for (i in a.indices) {
                signal += a[i].toDouble() * a[i]
                val e = (a[i] - b[i]).toDouble()
                noise += e * e
            }
        }
        return 10 * kotlin.math.log10(signal / maxOf(noise, 1.0))
    }

    @Test
    fun frameSizesMatchWire() {
        assertEquals(640, AdpcmCodec.PCM_FRAME_BYTES)
        assertEquals(CallAudioEngine.FRAME_BYTES, AdpcmCodec.PCM_FRAME_BYTES)
        assertEquals(164, AdpcmCodec.ENCODED_FRAME_BYTES)
        val encoded = AdpcmCodec().encodeFrame(ByteArray(AdpcmCodec.PCM_FRAME_BYTES))
        assertEquals(AdpcmCodec.ENCODED_FRAME_BYTES, encoded.size)
    }

    @Test
    fun silenceStaysSilent() {
        val codec = AdpcmCodec()
        val decoded = codec.decodeFrame(codec.encodeFrame(ByteArray(AdpcmCodec.PCM_FRAME_BYTES)))
        assertNotNull(decoded)
        assertTrue(samples(decoded!!).all { abs(it) <= 2 })
    }

    @Test
    fun speechBandSineSurvivesWithGoodSnr() {
        val original = sine(frames = 25, hz = 440.0, amplitude = 12_000)
        val encoder = AdpcmCodec()
        val decoder = AdpcmCodec()
        val decoded = original.map { decoder.decodeFrame(encoder.encodeFrame(it))!! }
        val snr = snrDb(original.drop(2), decoded.drop(2)) // первые кадры — разгон адаптации
        assertTrue("SNR too low: $snr dB", snr > 20.0)
    }

    @Test
    fun framesAreSelfContainedAfterLoss() {
        // Через брокер пачки теряются штатно: декодер должен продолжить с любого кадра.
        val original = sine(frames = 30, hz = 300.0, amplitude = 9_000)
        val encoder = AdpcmCodec()
        val encoded = original.map { encoder.encodeFrame(it) }
        val decoder = AdpcmCodec()
        // Пропускаем кадры 5..14, затем декодируем 15..29 «холодным» декодером.
        val tail = (15 until 30).map { decoder.decodeFrame(encoded[it])!! }
        val snr = snrDb(original.subList(15, 30), tail)
        assertTrue("SNR after loss too low: $snr dB", snr > 20.0)
    }

    @Test
    fun decodeIsDeterministicAcrossInstances() {
        val original = sine(frames = 3, hz = 1_000.0, amplitude = 20_000)
        val encoder = AdpcmCodec()
        val encoded = original.map { encoder.encodeFrame(it) }
        val a = encoded.map { AdpcmCodec().decodeFrame(it)!! }
        val b = AdpcmCodec().let { d -> encoded.map { d.decodeFrame(it)!! } }
        a.zip(b).forEach { (x, y) -> assertArrayEquals(x, y) }
    }

    @Test
    fun rejectsBadFrames() {
        val codec = AdpcmCodec()
        assertNull(codec.decodeFrame(ByteArray(163)))
        assertNull(codec.decodeFrame(ByteArray(165)))
        val badIndex = ByteArray(AdpcmCodec.ENCODED_FRAME_BYTES).also { it[2] = 89 }
        assertNull(codec.decodeFrame(badIndex))
        try {
            codec.encodeFrame(ByteArray(638))
            throw AssertionError("short PCM frame must throw")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun fullScaleDoesNotOverflow() {
        val original = sine(frames = 10, hz = 200.0, amplitude = 32_000)
        val encoder = AdpcmCodec()
        val decoder = AdpcmCodec()
        original.forEach { frame ->
            val decoded = decoder.decodeFrame(encoder.encodeFrame(frame))!!
            assertEquals(AdpcmCodec.PCM_FRAME_BYTES, decoded.size)
            // Пиков за пределами s16 быть не может по построению; проверяем, что энергия сопоставима.
            val rmsIn = sqrt(samples(frame).sumOf { it.toDouble() * it } / AdpcmCodec.FRAME_SAMPLES)
            val rmsOut = sqrt(samples(decoded).sumOf { it.toDouble() * it } / AdpcmCodec.FRAME_SAMPLES)
            assertTrue("rms in=$rmsIn out=$rmsOut", rmsOut > rmsIn * 0.5 && rmsOut < rmsIn * 1.5)
        }
    }
}
