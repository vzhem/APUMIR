package com.vladimir.messenger.data.file

// =============================================================================
// FILETRANSFERRETENTION.KT — сколько живёт «потеряшка»
// =============================================================================
// Владелец 2026-10-07: «У нас нет такого что некоторые потеряшки живут в очереди
// вечно? Текст и малые файлы объёмы можно по дольше хранить. А вот видео фото и
// всё тяжёлое максимум нужно хранить 24 часа.»
//
// Черта «тяжёлого» здесь та же, что у раздачи в режиме абонента
// (ServerMode.LIGHT_SERVE_MAX_BYTES, 2 МиБ) плюс сам тип: фото и видео —
// тяжёлые независимо от размера. Одна черта на две задачи: не раздавать чужое
// тяжёлое и не хранить его же дольше суток.
//
// Модуль чистый (без Android): правило проверяется JVM-тестами на runner,
// поэтому сроки не разъезжаются по файлам.
// =============================================================================

object FileTransferRetention {
    /**
     * Черта «тяжёлого», в байтах. Совпадает с `ServerMode.LIGHT_SERVE_MAX_BYTES`
     * (это сверяет контракт `scripts/ci/check-diagnostics-report.py`): тяжёлое —
     * то, что абонент и не раздаёт, и не хранит дольше суток.
     */
    const val HEAVY_MAX_BYTES: Long = 2L * 1024 * 1024

    /** Тяжёлое (фото, видео, гифки, большие файлы) — максимум сутки. */
    const val HEAVY_TTL_MS: Long = 24L * 60L * 60L * 1000L

    /** Текст и малое — неделя, как было до этого правила. */
    const val LIGHT_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L

    /** Фото и видео тяжёлые всегда: даже маленькая картинка — это вложение. */
    fun isHeavyCategory(mediaType: String): Boolean {
        val type = mediaType.trim().lowercase()
        return type.startsWith("image/") || type.startsWith("video/")
    }

    /** Тяжёлое ли вложение: по типу (фото/видео) или по размеру. */
    fun isHeavy(mediaType: String, totalBytes: Long): Boolean =
        totalBytes > HEAVY_MAX_BYTES || isHeavyCategory(mediaType)

    /** Сколько миллисекунд хранить и предлагать эту передачу. */
    fun ttlMs(mediaType: String, totalBytes: Long): Long =
        if (isHeavy(mediaType, totalBytes)) HEAVY_TTL_MS else LIGHT_TTL_MS

    /** Строка для отчёта «Логи»: владелец видит правило, а не догадывается. */
    fun describe(): String = "срок хранения: тяжёлое 24 ч · текст и малое 7 сут"

    /** Короткое объяснение для журнала, когда уборка что-то убрала. */
    fun describeSweep(removedRows: Int, freedCopies: Int): String =
        "просроченное убрано: строк=$removedRows копий=$freedCopies"
}
