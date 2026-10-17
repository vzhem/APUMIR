package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryVideoDraftTest {
    @Test fun longGalleryVideoInitiallySelectsOneMinute() {
        val draft = StoryVideoDraft.fromSource(300_000)
        assertEquals(60_000L, draft.durationMs)
        assertEquals(300_000L, draft.sourceDurationMs)
    }

    @Test fun trimMovesCoverInsideClipAndConvertsItsTimeForExport() {
        val draft = StoryVideoDraft(120_000, 0, 60_000, 30_000)
        val trimmed = draft.trim(40_000, 100_000)
        assertEquals(40_000L, trimmed.coverMs)
        assertEquals(0L, trimmed.exportedCoverMs)
        val endTrim = draft.trim(0, 10_000)
        assertEquals(9_999L, endTrim.coverMs)
        assertEquals(60_000L, trimmed.durationMs)
    }

    @Test fun rejectsInvalidDurationsAndCaption() {
        assertTrue(runCatching { StoryVideoDraft.fromSource(0) }.isFailure)
        assertTrue(runCatching { StoryVideoDraft.fromSource(999) }.isFailure)
        assertTrue(runCatching { StoryVideoDraft(120_000, 0, 60_001, 0) }.isFailure)
        assertTrue(runCatching { StoryVideoDraft(60_000, -1, 60_000, 0) }.isFailure)
        assertTrue(runCatching { StoryVideoDraft(60_000, 0, 60_000, 60_000) }.isFailure)
        assertTrue(runCatching { StoryVideoDraft.fromSource(60_000).copy(caption = "x".repeat(201)) }.isFailure)
    }

    @Test fun muteMustActuallyRemoveAudioFromExport() {
        val draft = StoryVideoDraft.fromSource(60_000).copy(muted = true)
        val video = StoryVideoMedia("a".repeat(64), 1000, 60_000, 720, 1280, true)
        assertTrue(runCatching { StoryVideoPreparation.Ready(draft, video) }.isFailure)
        StoryVideoPreparation.Ready(draft, video.copy(hasAudio = false))
    }

    @Test fun cannotPublishOldExportAfterChangingTrim() {
        val draft = StoryVideoDraft.fromSource(60_000).trim(0, 30_000)
        val video = StoryVideoMedia("a".repeat(64), 1000, 60_000, 720, 1280, true)
        assertTrue(runCatching { StoryVideoPreparation.Ready(draft, video) }.isFailure)
    }

    @Test fun malformedMediaAndProgressAreRejected() {
        assertTrue(runCatching { StoryVideoMedia("wrong", 1000, 60_000, 720, 1280, true) }.isFailure)
        assertTrue(runCatching { StoryVideoMedia("a".repeat(64), Long.MAX_VALUE, 60_000, 720, 1280, true) }.isFailure)
        assertTrue(runCatching { StoryVideoPreparation.Exporting(StoryVideoDraft.fromSource(60_000), Float.NaN) }.isFailure)
    }
    @Test fun shapeIsReversibleAndPartOfTheDraftRevision() {
        val regular = StoryVideoDraft.fromSource(60_000)
        val circle = regular.copy(circle = true)
        assertEquals(false, regular.circle)
        assertTrue(regular != circle)
        assertEquals(regular, circle.copy(circle = false))
        assertTrue(circle.trim(1_000, 30_000).circle)
    }

    @Test fun exportPreservesOrientationWithoutLetterboxingOrUpscaling() {
        assertEquals(1280, StoryVideoGeometry.exportHeight(1080, 1920))
        assertEquals(404, StoryVideoGeometry.exportHeight(1920, 1080))
        assertEquals(720, StoryVideoGeometry.exportHeight(1000, 1000))
        assertEquals(480, StoryVideoGeometry.exportHeight(640, 480))
        assertEquals(640, StoryVideoGeometry.exportHeight(480, 640))
        assertTrue(runCatching { StoryVideoGeometry.exportHeight(0, 720) }.isFailure)
    }

    @Test fun partialResetsKeepUnrelatedEdits() {
        val edited = StoryVideoDraft(120_000, 65_000, 100_000, 80_000,
            muted = true, caption = "Keep my caption", circle = true)
        assertEquals(edited.copy(muted = false), edited.reset(StoryVideoDraft.Reset.AUDIO))
        assertEquals(edited.copy(caption = ""), edited.reset(StoryVideoDraft.Reset.CAPTION))
        assertEquals(edited.copy(circle = false), edited.reset(StoryVideoDraft.Reset.SHAPE))
        assertEquals(edited.copy(coverMs = 65_000), edited.reset(StoryVideoDraft.Reset.COVER))
        val trimmed = edited.reset(StoryVideoDraft.Reset.TRIM)
        assertEquals(0L, trimmed.startMs)
        assertEquals(60_000L, trimmed.endMs)
        assertEquals(59_999L, trimmed.coverMs)
        assertEquals(edited.caption, trimmed.caption)
        assertTrue(trimmed.muted && trimmed.circle)
        assertEquals(StoryVideoDraft.fromSource(120_000), edited.reset(StoryVideoDraft.Reset.ALL))
        assertEquals(4_363L, StoryVideoDraft.fromSource(4_363).copy(caption = "x")
            .reset(StoryVideoDraft.Reset.ALL).durationMs)
    }

    @Test fun resetTrimKeepsCoverIfItIsStillInsideTheClip() {
        val draft = StoryVideoDraft(120_000, 10_000, 50_000, 30_000)
        assertEquals(30_000L, draft.reset(StoryVideoDraft.Reset.TRIM).coverMs)
    }

    @Test fun videoSizeShowsTwoDecimalPlacesAndUsesTheDisplayLocale() {
        assertEquals("1.27", StoryVideoSizeFormat.megabytes(1_270_000, java.util.Locale.US))
        assertEquals("1,27", StoryVideoSizeFormat.megabytes(1_270_000, java.util.Locale.forLanguageTag("ru")))
        assertEquals("2.00", StoryVideoSizeFormat.megabytes(1_999_000, java.util.Locale.US))
        assertEquals("0.00", StoryVideoSizeFormat.megabytes(0, java.util.Locale.US))
        assertEquals("0.50", StoryVideoSizeFormat.megabytes(500_000, java.util.Locale.US))
        assertEquals("1.05", StoryVideoSizeFormat.megabytes(1_048_576, java.util.Locale.US))
        assertTrue(runCatching { StoryVideoSizeFormat.megabytes(-1) }.isFailure)
    }

}
