package com.vladimir.messenger.data.profile

import com.vladimir.messenger.data.story.StoryVideoDraft
import com.vladimir.messenger.data.story.StoryVideoMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пределы видеоаватара: возможности редактора общие с видеоисторией, а окно —
 * своё (15 секунд против 60), и «сбросить всё» не должен молча делать из
 * аватара минутный ролик.
 *
 * Файл чистый, поэтому тест компилируется и выполняется в JVM-гейте
 * `scripts/ci/check-chat-history.sh` без Android SDK.
 */
class VideoAvatarPolicyTest {

    private val hash = "a".repeat(64)

    private fun media(durationMs: Long, sizeBytes: Long = 4L * 1024 * 1024) = StoryVideoMedia(
        sha256 = hash,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        width = 720,
        height = 1280,
        hasAudio = true,
    )

    @Test
    fun avatarWindowIsFifteenSecondsWhileStoriesKeepSixty() {
        assertEquals(15_000L, VideoAvatarPolicy.MAX_DURATION_MS)
        assertEquals(60_000L, StoryVideoDraft.MAX_DURATION_MS)
        // Длинный ролик не отклоняется: в аватар берутся первые 15 секунд,
        // остальное человек добирает обрезкой.
        assertEquals(15_000L, VideoAvatarPolicy.initialDraft(60_000L).durationMs)
        assertEquals(7_000L, VideoAvatarPolicy.initialDraft(7_000L).durationMs)
        assertFalse(VideoAvatarPolicy.fitsWholeSource(60_000L))
        assertTrue(VideoAvatarPolicy.fitsWholeSource(15_000L))
    }

    @Test
    fun avatarIsCircleByDefaultBecauseItIsShownInARoundFrame() {
        assertTrue(VideoAvatarPolicy.initialDraft(60_000L).circle)
        assertFalse(VideoAvatarPolicy.initialDraft(60_000L, circle = false).circle)
    }

    @Test
    fun sourceShorterThanASecondIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoAvatarPolicy.initialDraft(500L)
        }
    }

    @Test
    fun resetFromTheSharedEditorIsClampedBackToTheAvatarWindow() {
        val draft = VideoAvatarPolicy.initialDraft(60_000L)
        // Общий редактор сбрасывает обрезку к 60 секундам истории.
        assertEquals(60_000L, draft.reset(StoryVideoDraft.Reset.TRIM).durationMs)
        assertEquals(60_000L, draft.reset(StoryVideoDraft.Reset.ALL).durationMs)
        assertEquals(15_000L, VideoAvatarPolicy.clampToAvatar(draft.reset(StoryVideoDraft.Reset.TRIM)).durationMs)
        assertEquals(15_000L, VideoAvatarPolicy.clampToAvatar(draft.reset(StoryVideoDraft.Reset.ALL)).durationMs)
        // Короткий ролик сброс не растягивает.
        val short = VideoAvatarPolicy.initialDraft(7_000L)
        assertEquals(7_000L, VideoAvatarPolicy.clampToAvatar(short.reset(StoryVideoDraft.Reset.ALL)).durationMs)
    }

    @Test
    fun trimStaysInsideTheSourceAndTheAvatarWindow() {
        val draft = VideoAvatarPolicy.initialDraft(60_000L)
        val moved = VideoAvatarPolicy.trim(draft, 40_000L, 90_000L)
        assertEquals(40_000L, moved.startMs)
        assertEquals(15_000L, moved.durationMs)
        assertEquals(55_000L, moved.endMs)
        // Конец не может уехать за ролик, даже если запас по окну есть.
        val tail = VideoAvatarPolicy.trim(draft, 55_000L, 60_000L)
        assertEquals(5_000L, tail.durationMs)
        assertEquals(60_000L, tail.endMs)
        // Кадр-постер остаётся внутри выбранного фрагмента.
        assertTrue(moved.coverMs in moved.startMs until moved.endMs)
    }

    @Test
    fun durationCeilingFollowsTheChosenStart() {
        assertEquals(15_000L, VideoAvatarPolicy.maxDurationMs(60_000L, 0L))
        assertEquals(15_000L, VideoAvatarPolicy.maxDurationMs(60_000L, 40_000L))
        assertEquals(5_000L, VideoAvatarPolicy.maxDurationMs(60_000L, 55_000L))
        assertEquals(3_000L, VideoAvatarPolicy.maxDurationMs(3_000L, 0L))
    }

    @Test
    fun exportLongerThanTheAvatarWindowIsRefused() {
        val draft = VideoAvatarPolicy.initialDraft(60_000L)
        assertTrue(VideoAvatarPolicy.isAcceptable(draft, media(15_000L)))
        assertFalse(VideoAvatarPolicy.isAcceptable(draft, media(60_000L)))
        assertThrows(IllegalStateException::class.java) {
            VideoAvatarPolicy.requireAcceptable(draft, media(60_000L))
        }
        // Погрешность кодировщика не делает из 15 секунд отказ.
        assertTrue(VideoAvatarPolicy.isAcceptable(draft, media(15_050L)))
    }

    @Test
    fun captionLimitIsTheStoryOneBecauseTheEditorIsShared() {
        assertEquals(StoryVideoDraft.MAX_CAPTION_CHARS, VideoAvatarPolicy.MAX_CAPTION_CHARS)
        assertEquals(StoryVideoDraft.MAX_EXPORT_BYTES, VideoAvatarPolicy.MAX_EXPORT_BYTES)
        val caption = "ы".repeat(VideoAvatarPolicy.MAX_CAPTION_CHARS)
        val draft = VideoAvatarPolicy.initialDraft(9_000L).copy(caption = caption)
        assertTrue(VideoAvatarPolicy.isAcceptable(draft, media(9_000L)))
    }

    @Test
    fun posterIsACentralSquareBoundedToTheAvatarSize() {
        val wide = VideoAvatarPolicy.posterCrop(1280, 720)
        assertEquals(720, wide.side)
        assertEquals(280, wide.left)
        assertEquals(0, wide.top)
        assertEquals(480, wide.scaledSide)

        val small = VideoAvatarPolicy.posterCrop(300, 200)
        assertEquals(200, small.side)
        assertEquals(50, small.left)
        assertEquals(0, small.top)
        // Меньше потолка — не растягиваем: масштабирование вверх только мылит.
        assertEquals(200, small.scaledSide)

        assertThrows(IllegalArgumentException::class.java) { VideoAvatarPolicy.posterCrop(0, 10) }
    }
}
