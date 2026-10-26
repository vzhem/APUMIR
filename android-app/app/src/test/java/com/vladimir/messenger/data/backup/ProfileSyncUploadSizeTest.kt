package com.vladimir.messenger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Связка «сколько сырых байт влезает в лимит релея» (аудит 2026-10-26, п. 4.5).
 *
 * Раньше предел проверялся уже по готовой строке base64 — то есть копия
 * успевала целиком оказаться в памяти. Теперь размер файла сравнивается с
 * [ProfileSyncNet.MAX_RAW_BYTES] ДО чтения, а тело запроса объявляется как
 * `4 * ceil(raw / 3)` байт. Эти два числа обязаны совпадать: если формула
 * разойдётся с лимитом, релей начнёт отбивать копию, которую сам же и
 * разрешил, либо HttpURLConnection упадёт на несовпадении длины.
 */
class ProfileSyncUploadSizeTest {

    /** Длина base64 без переводов строк — ровно так её считает HttpURLConnection. */
    private fun base64Length(rawBytes: Long): Long = 4L * ((rawBytes + 2) / 3)

    @Test
    fun rawLimitIsTheLargestCopyThatStillFitsTheRelay() {
        assertEquals(
            ProfileSyncNet.MAX_B64_CHARS.toLong(),
            base64Length(ProfileSyncNet.MAX_RAW_BYTES),
        )
    }

    @Test
    fun oneByteMoreWouldNotFit() {
        assertTrue(
            base64Length(ProfileSyncNet.MAX_RAW_BYTES + 1) > ProfileSyncNet.MAX_B64_CHARS
        )
    }

    @Test
    fun emptyAndSmallCopiesStillProduceAValidLength() {
        assertEquals(0L, base64Length(0))
        assertEquals(4L, base64Length(1))
        assertEquals(4L, base64Length(3))
        assertEquals(8L, base64Length(4))
    }
}
