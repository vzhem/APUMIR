package com.vladimir.messenger.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePinPolicyTest {
    @Test
    fun allowsUpToTenPinsAndRejectsTheEleventh() {
        assertEquals(10, MessagePinPolicy.MAX_PINNED_PER_SCOPE)
        assertTrue(MessagePinPolicy.canAddPin(0))
        assertTrue(MessagePinPolicy.canAddPin(9))
        assertFalse(MessagePinPolicy.canAddPin(10))
        assertFalse(MessagePinPolicy.canAddPin(11))
    }

    @Test
    fun pinningAnAlreadyPinnedMessageDoesNotConsumeAnotherSlot() {
        assertTrue(MessagePinPolicy.canAddPin(10, alreadyPinned = true))
    }

    @Test
    fun negativeCountsAreTreatedAsEmpty() {
        assertTrue(MessagePinPolicy.canAddPin(-1))
    }

    @Test
    fun failedPinNoticeRemainsOnlyWhenTheActiveFeedIsFull() {
        val notice = MessagePinPolicy.LIMIT_REACHED_MESSAGE
        assertEquals(notice, MessagePinPolicy.visibleError(notice, 10))
        assertEquals(notice, MessagePinPolicy.visibleError(notice, 11))
    }

    @Test
    fun freeingOneSlotClearsTheNoticeImmediately() {
        val notice = MessagePinPolicy.LIMIT_REACHED_MESSAGE
        for (count in listOf(9, 8, 1, 0, -1)) {
            assertEquals(null, MessagePinPolicy.visibleError(notice, count))
        }
    }

    @Test
    fun topicsListNeverShowsThePinQuotaNotice() {
        for (count in listOf(0, 9, 10, 11)) {
            assertEquals(
                null,
                MessagePinPolicy.visibleError(MessagePinPolicy.LIMIT_REACHED_MESSAGE, count, inMessageFeed = false),
            )
        }
    }

    @Test
    fun unrelatedErrorsArePreservedInBothTopicsAndMessageFeed() {
        for (error in listOf("Недостаточно прав", "Не удалось отправить", MessagePinPolicy.SCOPE_CONFLICT_MESSAGE)) {
            assertEquals(error, MessagePinPolicy.visibleError(error, 9))
            assertEquals(error, MessagePinPolicy.visibleError(error, 10))
            assertEquals(error, MessagePinPolicy.visibleError(error, 10, inMessageFeed = false))
        }
    }

    @Test
    fun clearedNoticeDoesNotReappearWhenTheSlotIsFilledAgain() {
        var error: String? = MessagePinPolicy.LIMIT_REACHED_MESSAGE
        error = MessagePinPolicy.visibleError(error, 9)
        assertEquals(null, error)
        error = MessagePinPolicy.visibleError(error, 10)
        assertEquals(null, error)
    }

    @Test
    fun reachingTenWithoutAFailedAttemptDoesNotInventAnError() {
        assertEquals(null, MessagePinPolicy.visibleError(null, 10))
        assertEquals(null, MessagePinPolicy.visibleError(null, 10, inMessageFeed = false))
    }

    @Test
    fun movingToAnotherContextClearsOnlyTheQuotaNotice() {
        val previous = MessagePinPolicy.LIMIT_REACHED_MESSAGE
        assertEquals(null, MessagePinPolicy.visibleError(previous, 0))
        assertEquals("Ошибка вложения", MessagePinPolicy.visibleError("Ошибка вложения", 0))
    }
}
