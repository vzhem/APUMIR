package com.vladimir.messenger.ui.components

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
}
