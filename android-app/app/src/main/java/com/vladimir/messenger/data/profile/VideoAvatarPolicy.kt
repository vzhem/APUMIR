package com.vladimir.messenger.data.profile

import com.vladimir.messenger.data.story.StoryVideoDraft
import com.vladimir.messenger.data.story.StoryVideoMedia

/**
 * Правила видеоаватара: тот же редактор, что у видеоистории, но короче и легче.
 *
 * Почему отдельный файл, а не правка [StoryVideoDraft]: возможности видеоисторий
 * (обрезка, кадр-постер, звук, подпись, форма, сброс по одному параметру) нужны
 * видеоаватару ЦЕЛИКОМ, а пределы у него свои — аватар играет по кругу и не
 * обязан быть фильмом. Черновик остаётся общим, поэтому человек в обоих потоках
 * видит одни и те же ползунки и кнопки; здесь живут только границы аватара и
 * приведение черновика к ним (в том числе после «сбросить всё», которое в общем
 * редакторе возвращает 60 секунд).
 *
 * Файл чистый (без Android), поэтому пределы проверяются JVM-тестом
 * `VideoAvatarPolicyTest` и компилируются в гейте `check-chat-history.sh`.
 */
object VideoAvatarPolicy {

    /** Аватар, а не фильм: 15 секунд против 60 у видеоистории. */
    const val MAX_DURATION_MS = 15_000L

    /** Короче секунды аватар не бывает: общий редактор столько не показывает. */
    const val MIN_DURATION_MS = StoryVideoDraft.MIN_DURATION_MS

    /** Подпись та же, что у истории: тот же редактор, тот же предел. */
    const val MAX_CAPTION_CHARS = StoryVideoDraft.MAX_CAPTION_CHARS

    /** Потолок готового файла: как у видеоистории, 24 МБ. */
    const val MAX_EXPORT_BYTES = StoryVideoDraft.MAX_EXPORT_BYTES

    /** Кадр-постер уходит друзьям обычным аватаром: квадрат не больше этой стороны. */
    const val POSTER_SIDE = 480

    /** Погрешность кодировщика: экспорт не обязан попасть в миллисекунду. */
    const val DURATION_TOLERANCE_MS = 100L

    /**
     * Черновик под источник: первые 15 секунд (или весь короткий ролик),
     * кадр-постер в начале, звук как в источнике. Форма по умолчанию круглая —
     * аватар показывается в круге, и центральное кадрирование здесь уместнее.
     */
    fun initialDraft(sourceDurationMs: Long, circle: Boolean = true): StoryVideoDraft {
        require(sourceDurationMs >= MIN_DURATION_MS) {
            "Видео короче секунды — для аватара не годится"
        }
        return StoryVideoDraft(
            sourceDurationMs = sourceDurationMs,
            startMs = 0L,
            endMs = minOf(sourceDurationMs, MAX_DURATION_MS),
            coverMs = 0L,
            muted = false,
            caption = "",
            circle = circle,
        )
    }

    /**
     * Верхняя граница ползунка длительности при выбранном начале: не длиннее
     * остатка ролика и не длиннее самого аватара.
     */
    fun maxDurationMs(sourceDurationMs: Long, startMs: Long): Long {
        require(sourceDurationMs > 0 && startMs in 0 until sourceDurationMs)
        return minOf(MAX_DURATION_MS, sourceDurationMs - startMs)
    }

    /**
     * Обрезка в пределах аватара: начало выбирает человек, конец сжимается так,
     * чтобы фрагмент остался от 1 секунды до [MAX_DURATION_MS] и не вышел за
     * ролик. Кадр-постер общий черновик сам удерживает внутри фрагмента.
     */
    fun trim(draft: StoryVideoDraft, startMs: Long, endMs: Long): StoryVideoDraft {
        val start = startMs.coerceIn(0L, draft.sourceDurationMs - MIN_DURATION_MS)
        val ceiling = start + maxDurationMs(draft.sourceDurationMs, start)
        val end = endMs.coerceIn(start + MIN_DURATION_MS, ceiling)
        return draft.trim(start, end)
    }

    /**
     * Привести черновик к правилам аватара. Нужен потому, что общий редактор
     * умеет «сбросить всё» и «обрезку к началу» — они возвращают 60 секунд,
     * и без приведения аватар молча стал бы минутным.
     */
    fun clampToAvatar(draft: StoryVideoDraft): StoryVideoDraft {
        val ceiling = maxDurationMs(draft.sourceDurationMs, draft.startMs)
        return if (draft.durationMs > ceiling) trim(draft, draft.startMs, draft.startMs + ceiling) else draft
    }

    /** Годится ли готовый экспорт в видеоаватары. */
    fun isAcceptable(draft: StoryVideoDraft, media: StoryVideoMedia): Boolean =
        media.durationMs <= MAX_DURATION_MS + DURATION_TOLERANCE_MS &&
            media.durationMs >= MIN_DURATION_MS &&
            media.sizeBytes in 1..MAX_EXPORT_BYTES &&
            draft.caption.length <= MAX_CAPTION_CHARS

    /**
     * Строгая проверка перед установкой: интерфейс можно обойти (старый экран,
     * прямой вызов), поэтому предел проверяется там, где файл реально
     * сохраняется. Текст технический — человеку показывается строка ресурса.
     */
    fun requireAcceptable(draft: StoryVideoDraft, media: StoryVideoMedia) {
        check(isAcceptable(draft, media)) {
            "Видеоаватар вне правил: ${media.durationMs} мс, ${media.sizeBytes} байт, " +
                "подпись ${draft.caption.length} симв."
        }
    }

    /** Квадрат из центра кадра: те же границы, что и у обычной аватарки. */
    fun posterCrop(width: Int, height: Int): PosterCrop {
        require(width > 0 && height > 0) { "Кадр пустой" }
        val side = minOf(width, height)
        return PosterCrop(
            left = (width - side) / 2,
            top = (height - side) / 2,
            side = side,
            scaledSide = if (side > POSTER_SIDE) POSTER_SIDE else side,
        )
    }

    /**
     * Окно обрезки в секундах для подсказки: видно, сколько из длинного ролика
     * помещается в аватар.
     */
    fun fitsWholeSource(sourceDurationMs: Long): Boolean = sourceDurationMs <= MAX_DURATION_MS

    data class PosterCrop(val left: Int, val top: Int, val side: Int, val scaledSide: Int)
}
