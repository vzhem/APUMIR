package com.vladimir.messenger.data.story

/** Editing contract; timestamps are in the source timeline, not in the exported clip. */
data class StoryVideoDraft(
    val sourceDurationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val coverMs: Long,
    val muted: Boolean = false,
    val caption: String = "",
    val circle: Boolean = false,
) {
    init {
        require(sourceDurationMs > 0) { "Видео пустое или повреждено" }
        require(startMs >= 0 && endMs <= sourceDurationMs && endMs > startMs) { "Некорректные границы обрезки" }
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) { "Выберите от 1 до 60 секунд" }
        require(coverMs >= startMs && coverMs < endMs) { "Обложка должна быть внутри выбранного фрагмента" }
        require(caption.length <= MAX_CAPTION_CHARS) { "Подпись длиннее 200 символов" }
    }

    val durationMs: Long get() = endMs - startMs
    val exportedCoverMs: Long get() = coverMs - startMs

    /** Trimming cannot leave the cover pointing outside the exported video. */
    fun trim(startMs: Long, endMs: Long): StoryVideoDraft {
        require(startMs >= 0 && endMs > startMs && endMs <= sourceDurationMs)
        return copy(startMs = startMs, endMs = endMs, coverMs = coverMs.coerceIn(startMs, endMs - 1))
    }

    enum class Reset { TRIM, COVER, AUDIO, CAPTION, SHAPE, ALL }

    /** Reset only the chosen setting; the source file and unrelated edits are untouched. */
    fun reset(setting: Reset): StoryVideoDraft = when (setting) {
        Reset.TRIM -> trim(0, minOf(sourceDurationMs, MAX_DURATION_MS))
        Reset.COVER -> copy(coverMs = startMs)
        Reset.AUDIO -> copy(muted = false)
        Reset.CAPTION -> copy(caption = "")
        Reset.SHAPE -> copy(circle = false)
        Reset.ALL -> fromSource(sourceDurationMs)
    }

    companion object {
        const val MAX_DURATION_MS = 60_000L
        const val MIN_DURATION_MS = 1_000L
        const val MAX_CAPTION_CHARS = 200
        // Proposed export budget: bounded on both sender and receiver, separate from photo packets.
        const val MAX_EXPORT_BYTES = 24L * 1024 * 1024
        fun fromSource(durationMs: Long): StoryVideoDraft =
            StoryVideoDraft(durationMs, 0, minOf(durationMs, MAX_DURATION_MS), 0)
    }
}

/** Export states with duration/audio validation. Controller must also bind output to the draft revision. */
sealed class StoryVideoPreparation {
    data class Editing(val draft: StoryVideoDraft) : StoryVideoPreparation()
    data class Exporting(val draft: StoryVideoDraft, val progress: Float) : StoryVideoPreparation() {
        init { require(progress.isFinite() && progress in 0f..1f) }
    }
    data class Ready(val draft: StoryVideoDraft, val media: StoryVideoMedia) : StoryVideoPreparation() {
        init {
            require(media.durationMs <= StoryVideoDraft.MAX_DURATION_MS)
            require(kotlin.math.abs(media.durationMs - draft.durationMs) <= 100)
            require(!draft.muted || !media.hasAudio) { "Отключённый звук не должен попасть в экспорт" }
        }
    }
    data class Failed(val draft: StoryVideoDraft, val reason: String) : StoryVideoPreparation()
}

/** File descriptor only: no base64 MP4 in story/photo JSON or saved-instance bundles. */
data class StoryVideoMedia(
    val sha256: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "Invalid video hash" }
        require(sizeBytes in 1..StoryVideoDraft.MAX_EXPORT_BYTES) { "Video exceeds export budget" }
        require(durationMs in StoryVideoDraft.MIN_DURATION_MS..StoryVideoDraft.MAX_DURATION_MS)
        require(width in 1..1920 && height in 1..1920)
    }
}

/** Fit the original aspect ratio into the export budget, without baking in black bars. */
object StoryVideoGeometry {
    fun exportHeight(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 720.0 / width, 1280.0 / height)
        return ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
    }
}

/** Decimal megabytes, matching the MB label, rounded rather than truncated. */
object StoryVideoSizeFormat {
    fun megabytes(bytes: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        require(bytes >= 0)
        return String.format(locale, "%.2f", bytes / 1_000_000.0)
    }
}
