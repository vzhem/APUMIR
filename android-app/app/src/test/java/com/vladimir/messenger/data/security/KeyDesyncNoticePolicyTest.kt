package com.vladimir.messenger.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение «показывать ли плашку „от собеседника не открывается“».
 *
 * Владелец 2026-10-07: обменялся QR в обе стороны, переписка начала работать —
 * а плашка появилась опять. Причина: обмен ключами чинит НОВЫЕ сообщения, а в
 * сети ещё летят СТАРЫЕ копии, запечатанные прежним ключом; каждая такая копия
 * зажигала плашку с первого же конверта. Эти тесты фиксируют правило: плашка —
 * про «ничего не открывается», а не про «одна копия не открылась».
 */
class KeyDesyncNoticePolicyTest {

    private val minute = 60_000L

    @Test
    fun oneEnvelopeThatDidNotOpenIsNotEnoughForTheNotice() {
        // Даже если переписка молчит давно: один конверт = ещё «копия в пути».
        assertFalse(KeyDesyncNotice.shouldShow(failCount = 1, lastOpenAt = 0L, nowMs = 10 * minute))
        assertFalse(
            KeyDesyncNotice.shouldShow(
                failCount = KeyDesyncNotice.MIN_FAILS - 1,
                lastOpenAt = 0L,
                nowMs = 10 * minute,
            ),
        )
    }

    @Test
    fun severalFailuresWithNothingOpeningShowTheNotice() {
        assertTrue(
            KeyDesyncNotice.shouldShow(
                failCount = KeyDesyncNotice.MIN_FAILS,
                lastOpenAt = 0L,
                nowMs = 10 * minute,
            ),
        )
    }

    @Test
    fun workingChatSuppressesTheNoticeEvenAfterSeveralStaleCopies() {
        val openedAt = 100 * minute
        // Копии продолжают прилетать, но что-то от него открылось только что:
        // ключ рабочий — тревожить человека нечем.
        assertFalse(
            KeyDesyncNotice.shouldShow(
                failCount = 12,
                lastOpenAt = openedAt,
                nowMs = openedAt + KeyDesyncNotice.WORKING_QUIET_MS / 2,
            ),
        )
    }

    @Test
    fun noticeReturnsWhenNothingOpenedForALongTime() {
        val openedAt = 100 * minute
        assertTrue(
            KeyDesyncNotice.shouldShow(
                failCount = KeyDesyncNotice.MIN_FAILS,
                lastOpenAt = openedAt,
                nowMs = openedAt + KeyDesyncNotice.WORKING_QUIET_MS + 1,
            ),
        )
    }

    @Test
    fun copyRightAfterAWorkingMessageIsALeftover() {
        val openedAt = 100 * minute
        assertTrue(KeyDesyncNotice.isLeftoverCopy(openedAt, openedAt + 5_000L))
        assertTrue(
            KeyDesyncNotice.isLeftoverCopy(openedAt, openedAt + KeyDesyncNotice.LEFTOVER_MS),
        )
        // Не открывалось вовсе или прошло заметно больше минуты — считаем.
        assertFalse(KeyDesyncNotice.isLeftoverCopy(0L, openedAt))
        assertFalse(
            KeyDesyncNotice.isLeftoverCopy(openedAt, openedAt + KeyDesyncNotice.LEFTOVER_MS + 1),
        )
    }
}
