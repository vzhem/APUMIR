package com.vladimir.messenger.data.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Телефон молчит» — правило без Android: по строкам установки ключей сказать
 * человеку причину или промолчать. Строки те же, что пишет `CoreServerService`
 * в отчёт, поэтому тест ловит и случайное переименование статуса.
 */
class IdentityHealthTest {

    @Test
    fun readyKeysSayNothingToThePerson() {
        assertEquals(IdentityHealth.Reason.READY, IdentityHealth.classify("ready", "ready"))
        assertNull(IdentityHealth.notice(IdentityHealth.Snapshot(IdentityHealth.Reason.READY)))
    }

    @Test
    fun firstLaunchIsNotBreakage() {
        // Личность ещё не создана — это не поломка, и пугать человека нечем.
        assertEquals(
            IdentityHealth.Reason.NO_IDENTITY,
            IdentityHealth.classify("skipped_no_identity", "skipped_no_signing"),
        )
        assertNull(IdentityHealth.notice(IdentityHealth.Snapshot(IdentityHealth.Reason.NO_IDENTITY)))
    }

    @Test
    fun unreadableKeyAsksForProfileRestore() {
        // Сводка владельца 2026-10-15: подпись unavailable, обмен skipped_no_signing.
        assertEquals(
            IdentityHealth.Reason.UNREADABLE,
            IdentityHealth.classify("unavailable", "skipped_no_signing"),
        )
        assertEquals(
            IdentityHealth.Reason.UNREADABLE,
            IdentityHealth.notice(IdentityHealth.Snapshot(IdentityHealth.Reason.UNREADABLE)),
        )
    }

    @Test
    fun exchangeKeyFailureIsNotBlamedOnThePersonKey() {
        // Подпись готова, обмен - нет: ключ личности рабочий, и говорить
        // «ключ этого телефона не читается» было бы неправдой.
        assertEquals(
            IdentityHealth.Reason.EXCHANGE_UNAVAILABLE,
            IdentityHealth.classify("ready", "unavailable"),
        )
        assertEquals(
            IdentityHealth.Reason.EXCHANGE_UNAVAILABLE,
            IdentityHealth.notice(IdentityHealth.Snapshot(IdentityHealth.Reason.EXCHANGE_UNAVAILABLE)),
        )
    }

    @Test
    fun timeoutIsReportedSeparatelyFromUnreadable() {
        // «Не успел» и «не читается» требуют разных действий: перезапуск и копия.
        assertEquals(IdentityHealth.Reason.TIMEOUT, IdentityHealth.classify("timeout", "skipped_no_signing"))
        assertEquals(IdentityHealth.Reason.TIMEOUT, IdentityHealth.classify("ready", "timeout"))
        assertEquals(
            IdentityHealth.Reason.TIMEOUT,
            IdentityHealth.notice(IdentityHealth.Snapshot(IdentityHealth.Reason.TIMEOUT)),
        )
    }

    @Test
    fun bootNoteCarriesBothInstallWords() {
        IdentityHealth.noteBoot("unavailable", "skipped_no_signing")
        val state = IdentityHealth.state.value
        assertEquals(IdentityHealth.Reason.UNREADABLE, state.reason)
        assertEquals("unavailable", state.signingInstall)
        assertEquals("skipped_no_signing", state.exchangeInstall)
        // Подписка на состояние — то, что читает список чатов.
        assertEquals(IdentityHealth.Reason.UNREADABLE, IdentityHealth.notice(state))
    }

    @Test
    fun silenceIsTheDefaultUntilTheCoreSaysOtherwise() {
        // До старта ядра ничего не утверждаем: докладывать нечего.
        IdentityHealth.resetForTests()
        assertEquals(IdentityHealth.Reason.UNKNOWN, IdentityHealth.state.value.reason)
        assertNull(IdentityHealth.notice(IdentityHealth.state.value))
    }

    /**
     * Состояние — общий объект на весь JVM: тест, который его заполнил, не должен
     * влиять на соседей (check-chat-history.sh прогоняет всё одним JUnitCore).
     */
    @After
    fun clearState() {
        IdentityHealth.resetForTests()
    }
}
