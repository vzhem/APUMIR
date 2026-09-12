package com.vladimir.messenger.data.call

/**
 * IMA ADPCM (DVI4) для голоса звонка по узким путям: мобильная сеть, брокер.
 *
 * PCM 16 кГц s16le кадр 20 мс = 640 байт → 164 байта (4 байта заголовка +
 * 2 отсчёта на байт), то есть 64 кбит/с вместо 256. Качество — «широкополосная
 * телефония»: речь разборчива, музыка страдает, зато кодек чистый Kotlin,
 * детерминирован, без MediaCodec и без зависимости от железа.
 *
 * Каждый кадр самодостаточен: в заголовке лежит состояние предсказателя и
 * индекс шага НА НАЧАЛО кадра, поэтому потеря пачки кадров по дороге не ломает
 * декодирование следующих (важно: через брокер кадры теряются штатно).
 * Кодер при этом ведёт состояние непрерывно — адаптация шага не сбрасывается
 * на границе кадра.
 *
 * Формат кадра: [predictor s16 BE][index u8][0][160 байт: старший полубайт —
 * первый отсчёт пары]. Провод — codec=3 (CallWire.CODEC_ADPCM_16K).
 */
class AdpcmCodec {

    private var predictor = 0
    private var index = 0

    /** Сброс состояния (новый звонок). */
    fun reset() {
        predictor = 0
        index = 0
    }

    /**
     * Закодировать один кадр PCM (ровно FRAME_SAMPLES отсчётов s16le).
     * @return ENCODED_FRAME_BYTES байт.
     */
    fun encodeFrame(pcm: ByteArray): ByteArray {
        require(pcm.size == PCM_FRAME_BYTES) { "ADPCM: bad PCM frame size ${pcm.size}" }
        val out = ByteArray(ENCODED_FRAME_BYTES)
        out[0] = (predictor shr 8).toByte()
        out[1] = predictor.toByte()
        out[2] = index.toByte()
        out[3] = 0
        var o = HEADER_BYTES
        var i = 0
        while (i < PCM_FRAME_BYTES) {
            val high = encodeSample(sample(pcm, i))
            val low = encodeSample(sample(pcm, i + 2))
            out[o++] = ((high shl 4) or low).toByte()
            i += 4
        }
        return out
    }

    /**
     * Раскодировать кадр в PCM. Состояние берётся из заголовка, поэтому метод
     * можно звать на любом экземпляре и в любом порядке.
     * @return PCM_FRAME_BYTES байт или null, если кадр неверного размера.
     */
    fun decodeFrame(frame: ByteArray): ByteArray? {
        if (frame.size != ENCODED_FRAME_BYTES) return null
        var pred = ((frame[0].toInt() shl 8) or (frame[1].toInt() and 0xFF)).toShort().toInt()
        var idx = frame[2].toInt() and 0xFF
        if (idx > MAX_INDEX) return null
        val out = ByteArray(PCM_FRAME_BYTES)
        var o = 0
        for (i in HEADER_BYTES until ENCODED_FRAME_BYTES) {
            val b = frame[i].toInt() and 0xFF
            val first = decodeSample((b shr 4) and 0x0F, pred, idx)
            pred = first.predictor
            idx = first.index
            putSample(out, o, pred)
            o += 2
            val second = decodeSample(b and 0x0F, pred, idx)
            pred = second.predictor
            idx = second.index
            putSample(out, o, pred)
            o += 2
        }
        return out
    }

    private fun encodeSample(sample: Int): Int {
        val step = STEP_TABLE[index]
        var diff = sample - predictor
        var code = 0
        if (diff < 0) {
            code = 8
            diff = -diff
        }
        var temp = step
        if (diff >= temp) {
            code = code or 4
            diff -= temp
        }
        temp = temp shr 1
        if (diff >= temp) {
            code = code or 2
            diff -= temp
        }
        temp = temp shr 1
        if (diff >= temp) code = code or 1

        val state = decodeSample(code, predictor, index)
        predictor = state.predictor
        index = state.index
        return code
    }

    private class State(val predictor: Int, val index: Int)

    private fun decodeSample(code: Int, predictorIn: Int, indexIn: Int): State {
        val step = STEP_TABLE[indexIn]
        var diffq = step shr 3
        if (code and 4 != 0) diffq += step
        if (code and 2 != 0) diffq += step shr 1
        if (code and 1 != 0) diffq += step shr 2
        var pred = if (code and 8 != 0) predictorIn - diffq else predictorIn + diffq
        if (pred > 32767) pred = 32767
        if (pred < -32768) pred = -32768
        var idx = indexIn + INDEX_TABLE[code]
        if (idx < 0) idx = 0
        if (idx > MAX_INDEX) idx = MAX_INDEX
        return State(pred, idx)
    }

    private fun sample(pcm: ByteArray, at: Int): Int =
        ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort().toInt()

    private fun putSample(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value shr 8).toByte()
    }

    companion object {
        const val FRAME_SAMPLES = 320
        const val PCM_FRAME_BYTES = FRAME_SAMPLES * 2
        const val HEADER_BYTES = 4
        const val ENCODED_FRAME_BYTES = HEADER_BYTES + FRAME_SAMPLES / 2
        private const val MAX_INDEX = 88

        private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )
    }
}
