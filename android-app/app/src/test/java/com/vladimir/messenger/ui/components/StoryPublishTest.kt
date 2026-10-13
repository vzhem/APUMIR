package com.vladimir.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессия на «друзья видят только подпись»: неудачное прикрепление фото не
 * должно превращаться в тихую публикацию истории без снимка.
 */
class StoryPublishTest {
    @Test fun failedAttachmentCannotBePublishedSilently() {
        assertFalse(StoryPublish.canPublish("подпись", photoB64 = null, photoMissing = true))
        assertFalse(StoryPublish.canPublish("", photoB64 = null, photoMissing = true))
    }

    @Test fun explicitChoiceAllowsACaptionOnlyStory() {
        assertTrue(StoryPublish.canPublish("подпись", photoB64 = null, photoMissing = false))
    }

    @Test fun photoStoryNeedsNoCaption() {
        assertTrue(StoryPublish.canPublish("", photoB64 = "AAAA", photoMissing = false))
        assertTrue(StoryPublish.canPublish("подпись", photoB64 = "AAAA", photoMissing = false))
    }

    @Test fun emptyStoryIsNotPublishable() {
        assertFalse(StoryPublish.canPublish("", photoB64 = null, photoMissing = false))
        assertFalse(StoryPublish.canPublish("   ", photoB64 = null, photoMissing = false))
    }

    @Test fun accessoryToolsHideWhenKeyboardIsOpen() {
        assertTrue(StoryComposerLayout.showAccessoryTools(imeOpen = false))
        assertFalse(StoryComposerLayout.showAccessoryTools(imeOpen = true))
    }

    @Test fun accessoryToolsHideInLandscapeHeight() {
        assertFalse(StoryComposerLayout.showAccessoryTools(imeOpen = false, heightDp = 360))
        assertTrue(StoryComposerLayout.showCompactToolRow(imeOpen = false, heightDp = 360))
        assertFalse(StoryComposerLayout.showCompactToolRow(imeOpen = true, heightDp = 360))
        assertTrue(StoryComposerLayout.showAccessoryTools(imeOpen = false, heightDp = 800))
    }

    @Test fun imeOverlapIgnoresNavigationBar() {
        assertEquals(0, StoryComposerLayout.imeOverlapPx(
            viewBottomOnScreen = 2000, visibleFrameBottom = 1900, viewHeight = 2000,
        ))
    }

    @Test fun imeOverlapDetectsKeyboard() {
        assertEquals(800, StoryComposerLayout.imeOverlapPx(
            viewBottomOnScreen = 2000, visibleFrameBottom = 1200, viewHeight = 2000,
        ))
    }

    @Test fun blueCheckPublishesWhenStoryIsReady() {
        assertTrue(StoryPublish.confirmOnIme("клава", photoB64 = "AAAA", photoMissing = false, preparing = false))
        assertFalse(StoryPublish.confirmOnIme("", photoB64 = null, photoMissing = false, preparing = false))
        assertFalse(StoryPublish.confirmOnIme("клава", photoB64 = "AAAA", photoMissing = false, preparing = true))
    }
}
