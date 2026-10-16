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
}
