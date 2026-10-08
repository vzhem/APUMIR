package com.vladimir.messenger.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило сроков хранения «потеряшек» (владелец 2026-10-07): текст и малое
 * можно хранить дольше, тяжёлое — фото, видео и большие файлы — максимум сутки.
 * Тест чистый (без Android), поэтому его гоняет scripts/ci/check-chat-history.sh
 * на runner: сроки проверяются до выпуска, а не на телефоне владельца.
 */
class FileTransferRetentionTest {

    @Test
    fun heavyIsPhotoVideoOrBigFile() {
        // Фото и видео тяжелы независимо от размера: это вложения, а не текст.
        assertTrue(FileTransferRetention.isHeavy("image/jpeg", 120_000L))
        assertTrue(FileTransferRetention.isHeavy("image/png", 1L))
        assertTrue(FileTransferRetention.isHeavy("video/mp4", 900_000L))
        assertTrue(FileTransferRetention.isHeavy("IMAGE/GIF", 10L))
        // Большой файл тяжелый и без «фото» в типе.
        assertTrue(FileTransferRetention.isHeavy("application/pdf", FileTransferRetention.HEAVY_MAX_BYTES + 1))
        assertTrue(FileTransferRetention.isHeavy("application/zip", 40L * 1024 * 1024))
        // Текст и малое остаются надолго.
        assertFalse(FileTransferRetention.isHeavy("text/plain", 4_000L))
        assertFalse(FileTransferRetention.isHeavy("application/pdf", 200_000L))
        assertFalse(FileTransferRetention.isHeavy("", 0L))
    }

    @Test
    fun heavyLivesOneDayAndLightLivesAWeek() {
        assertEquals(24L * 60 * 60 * 1000, FileTransferRetention.ttlMs("video/mp4", 5L))
        assertEquals(24L * 60 * 60 * 1000, FileTransferRetention.ttlMs("image/webp", 5L))
        assertEquals(
            24L * 60 * 60 * 1000,
            FileTransferRetention.ttlMs("application/octet-stream", 9L * 1024 * 1024),
        )
        assertEquals(7L * 24 * 60 * 60 * 1000, FileTransferRetention.ttlMs("text/plain", 1_000L))
        assertEquals(7L * 24 * 60 * 60 * 1000, FileTransferRetention.ttlMs("application/pdf", 512_000L))
        // Ровно на черте — ещё не тяжёлое: граница не должна «прыгать».
        assertEquals(
            7L * 24 * 60 * 60 * 1000,
            FileTransferRetention.ttlMs("application/pdf", FileTransferRetention.HEAVY_MAX_BYTES),
        )
        assertTrue(FileTransferRetention.HEAVY_TTL_MS < FileTransferRetention.LIGHT_TTL_MS)
    }

    @Test
    fun logsSayTheRuleAndTheSweep() {
        // Владелец должен видеть правило в «Логах», а не догадываться о нём.
        assertEquals("срок хранения: тяжёлое 24 ч · текст и малое 7 сут", FileTransferRetention.describe())
        assertEquals(
            "просроченное убрано: строк=3 копий=5",
            FileTransferRetention.describeSweep(3, 5),
        )
    }
}
