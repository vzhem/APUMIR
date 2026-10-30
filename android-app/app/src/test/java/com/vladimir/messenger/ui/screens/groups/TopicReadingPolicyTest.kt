package com.vladimir.messenger.ui.screens.groups

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец 2026-10-30: сообщения SOS отмечались прочитанными, пока он только
 * смотрел список тем и внутрь не заходил.
 */
class TopicReadingPolicyTest {

    @Test
    fun `the topics list alone never marks anything read`() {
        assertFalse(TopicReadingPolicy.shouldMarkRead(null, "general", appResumed = true))
    }

    @Test
    fun `an auto selected topic is not read while the list is on screen`() {
        // Модель уже выбрала тему, но её лента человеку не видна.
        assertFalse(TopicReadingPolicy.shouldMarkRead(null, "general", appResumed = true))
        assertFalse(TopicReadingPolicy.shouldMarkRead("", "general", appResumed = true))
    }

    @Test
    fun `the open topic is read`() {
        assertTrue(TopicReadingPolicy.shouldMarkRead("general", "general", appResumed = true))
    }

    @Test
    fun `another topic stays unread`() {
        assertFalse(TopicReadingPolicy.shouldMarkRead("general", "flood", appResumed = true))
    }

    @Test
    fun `a topic left open in the background is not read`() {
        assertFalse(TopicReadingPolicy.shouldMarkRead("general", "general", appResumed = false))
    }

    @Test
    fun `the jump to the first unread needs the same conditions`() {
        assertFalse(TopicReadingPolicy.canCaptureUnreadJump(null, "general", appResumed = true))
        assertFalse(TopicReadingPolicy.canCaptureUnreadJump("general", "general", appResumed = false))
        assertTrue(TopicReadingPolicy.canCaptureUnreadJump("general", "general", appResumed = true))
    }
}
