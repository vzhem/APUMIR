package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Досылка правок группы (GroupEditRetry): «исправил сообщение — исправилось
 * везде», в том числе у тех, кто в момент правки был офлайн.
 *
 * Часы подменяются вручную, поэтому тест не спит и не зависит от времени.
 */
class GroupEditRetryTest {

    private var now = 1_000_000L
    private val retry = GroupEditRetry(clock = { now })

    private val groupId = "g1"
    private val envelope = "APUGRP1|edit|g1|t1|m1|new%20text"

    // ── запоминание ─────────────────────────────────────────────────────────

    /** Недоставка запоминается по каждому узлу; доставшимся — ничего. */
    @Test
    fun remembersOnlyFailedPeers() {
        retry.remember(groupId, envelope, listOf("peer-a", "peer-b"), editedAtMs = 10L)

        assertEquals(2, retry.size())
        assertNotNull(retry.pendingFor("peer-a"))
        assertNotNull(retry.pendingFor("peer-b"))
        assertNull(retry.pendingFor("peer-c"))
    }

    /** Пустой список и пустые узлы — не повод заводить записи. */
    @Test
    fun ignoresEmptyInput() {
        retry.remember(groupId, envelope, emptyList(), editedAtMs = 10L)
        retry.remember(groupId, envelope, listOf("", "  "), editedAtMs = 10L)
        retry.remember(groupId, "", listOf("peer-a"), editedAtMs = 10L)

        assertEquals(0, retry.size())
    }

    /** Более свежая правка вытесняет прежнюю: досылаем последний текст. */
    @Test
    fun newerEditReplacesOlder() {
        retry.remember(groupId, "old", listOf("peer-a"), editedAtMs = 10L)
        retry.remember(groupId, "new", listOf("peer-a"), editedAtMs = 20L)

        assertEquals("new", retry.pendingFor("peer-a")?.envelope)
    }

    /** Устаревшая правка не откатывает уже запомненную свежую. */
    @Test
    fun olderEditDoesNotOverwriteNewer() {
        retry.remember(groupId, "new", listOf("peer-a"), editedAtMs = 20L)
        retry.remember(groupId, "old", listOf("peer-a"), editedAtMs = 10L)

        assertEquals("new", retry.pendingFor("peer-a")?.envelope)
    }

    // ── выдача и ограничение частоты ────────────────────────────────────────

    /** Сразу после запоминания досылать нечего: ждём интервал. */
    @Test
    fun takeIsRateLimited() {
        retry.remember(groupId, envelope, listOf("peer-a"), editedAtMs = now)

        assertNull("сразу повторять нельзя", retry.take("peer-a"))

        now += GroupEditRetry.RETRY_INTERVAL_MS
        assertNotNull("через интервал — пора", retry.take("peer-a"))
    }

    /** После попытки отсчёт начинается заново. */
    @Test
    fun takeRestartsCooldown() {
        retry.remember(groupId, envelope, listOf("peer-a"), editedAtMs = now)
        now += GroupEditRetry.RETRY_INTERVAL_MS

        val first = retry.take("peer-a")
        assertNotNull(first)
        retry.failed("peer-a", first!!)

        assertNull("сразу после попытки — нельзя", retry.take("peer-a"))

        now += GroupEditRetry.RETRY_INTERVAL_MS
        assertNotNull(retry.take("peer-a"))
    }

    /** Незнакомый узел и пустой идентификатор ничего не возвращают. */
    @Test
    fun takeIgnoresUnknownPeers() {
        assertNull(retry.take("peer-unknown"))
        assertNull(retry.take(""))
    }

    // ── успех и провал ──────────────────────────────────────────────────────

    /** Успешная досылка убирает запись: больше не повторяем. */
    @Test
    fun deliveredClearsEntry() {
        retry.remember(groupId, envelope, listOf("peer-a"), editedAtMs = now)
        now += GroupEditRetry.RETRY_INTERVAL_MS
        val attempt = retry.take("peer-a")!!

        retry.delivered("peer-a", attempt)

        assertEquals(0, retry.size())
        assertNull(retry.pendingFor("peer-a"))
    }

    /** Неудача оставляет запись, чтобы попробовать при следующем появлении. */
    @Test
    fun failedKeepsEntry() {
        retry.remember(groupId, envelope, listOf("peer-a"), editedAtMs = now)
        now += GroupEditRetry.RETRY_INTERVAL_MS
        val attempt = retry.take("peer-a")!!

        retry.failed("peer-a", attempt)

        assertEquals(1, retry.size())
    }

    /** Неудачная попытка не откатывает более свежую правку того же узла. */
    @Test
    fun failedDoesNotDowngradeFresherEdit() {
        retry.remember(groupId, "old", listOf("peer-a"), editedAtMs = now)
        now += GroupEditRetry.RETRY_INTERVAL_MS
        val stale = retry.take("peer-a")!!

        retry.remember(groupId, "new", listOf("peer-a"), editedAtMs = now + 1)
        retry.failed("peer-a", stale)

        assertEquals("new", retry.pendingFor("peer-a")?.envelope)
    }

    /**
     * Пока досылка шла, автор успел поправить ещё раз: старая попытка не
     * должна стереть новую запись.
     */
    @Test
    fun staleDeliveryDoesNotEraseFresherEdit() {
        retry.remember(groupId, "old", listOf("peer-a"), editedAtMs = now)
        now += GroupEditRetry.RETRY_INTERVAL_MS
        val stale = retry.take("peer-a")!!

        retry.remember(groupId, "new", listOf("peer-a"), editedAtMs = now + 1)

        retry.delivered("peer-a", stale)

        assertEquals("newer edit must survive", "new", retry.pendingFor("peer-a")?.envelope)
    }

    /** Узлы независимы: успех одному не трогает очередь другого. */
    @Test
    fun peersAreIndependent() {
        retry.remember(groupId, envelope, listOf("peer-a", "peer-b"), editedAtMs = now)
        now += GroupEditRetry.RETRY_INTERVAL_MS

        val toA = retry.take("peer-a")!!
        retry.delivered("peer-a", toA)

        assertEquals(1, retry.size())
        assertNull(retry.pendingFor("peer-a"))
        assertNotNull(retry.pendingFor("peer-b"))
    }

    /** Очистка убирает всё. */
    @Test
    fun clearEmptiesQueue() {
        retry.remember(groupId, envelope, listOf("peer-a", "peer-b"), editedAtMs = now)
        retry.clear()

        assertEquals(0, retry.size())
    }
}
