package com.vladimir.messenger.data.story

import org.junit.Assert.*
import org.junit.Test

/**
 * Очередь откликов: схлопывание намерений, паузы, повторы и пределы.
 *
 * Очередь держит намерения, а не тексты: сводка строится при отправке, поэтому
 * десять быстрых значков уходят одной волной, а не десятью пакетами.
 */
class StoryFeedbackOutboxTest {

    private val story = "story-1"
    private val author = "pk_" + "a".repeat(32)

    @Test fun ownActionGoesFirstAndBroadcastWaitsForItsDebounce() {
        val outbox = StoryFeedbackOutbox()
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, story, 1_000L,
            notBeforeMs = 1_000L + StoryFeedbackOutbox.BROADCAST_DEBOUNCE_MS)
        outbox.enqueue(StoryFeedbackOutbox.Kind.REACT, story, 1_000L, peer = author)
        // До конца окна слипания сводка не готова, а свой значок уходит сразу.
        val due = outbox.due(1_000L, StoryFeedbackOutbox.MAX_TASKS_PER_PUMP)
        assertEquals(StoryFeedbackOutbox.Kind.REACT, due.single().kind)
        val afterDebounce = outbox.due(1_000L + StoryFeedbackOutbox.BROADCAST_DEBOUNCE_MS, 10)
        // Своё действие человека всегда раньше рассылки сводки соседям.
        assertEquals(listOf(StoryFeedbackOutbox.Kind.REACT, StoryFeedbackOutbox.Kind.BROADCAST), afterDebounce.map { it.kind })
    }

    @Test fun repeatedReactionsCollapseIntoOneIntent() {
        val outbox = StoryFeedbackOutbox()
        repeat(5) { outbox.enqueue(StoryFeedbackOutbox.Kind.REACT, story, 1_000L + it, peer = author) }
        assertEquals(1, outbox.size())
        assertEquals(1, outbox.due(2_000L, 10).size)
    }

    @Test fun twoCommentsAreTwoPacketsButOneBroadcast() {
        val outbox = StoryFeedbackOutbox()
        outbox.enqueue(StoryFeedbackOutbox.Kind.COMMENT, story, 1_000L, peer = author, commentId = "c-1")
        outbox.enqueue(StoryFeedbackOutbox.Kind.COMMENT, story, 1_000L, peer = author, commentId = "c-2")
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, story, 1_000L)
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, story, 1_100L)
        val tasks = outbox.due(2_000L, 10)
        assertEquals(3, tasks.size)
        assertEquals(2, tasks.count { it.kind == StoryFeedbackOutbox.Kind.COMMENT })
        assertEquals(1, tasks.count { it.kind == StoryFeedbackOutbox.Kind.BROADCAST })
    }

    @Test fun failedSendIsRetriedAndAcceptedSendIsRemoved() {
        val outbox = StoryFeedbackOutbox()
        outbox.enqueue(StoryFeedbackOutbox.Kind.COMMENT, story, 1_000L, peer = author, commentId = "c-1")
        val task = outbox.due(1_000L, 10).single()
        outbox.result(task, false, 1_000L)
        assertTrue(outbox.due(1_000L, 10).isEmpty())
        val retry = outbox.due(1_000L + StoryFeedbackOutbox.RETRY_MS, 10).single()
        assertEquals(1, retry.attempts)
        outbox.result(retry, true, 1_000L + StoryFeedbackOutbox.RETRY_MS)
        assertEquals(0, outbox.size())
    }

    @Test fun deferredBroadcastWaitsItsWindowAndIsNotCountedAsAnAttempt() {
        val outbox = StoryFeedbackOutbox()
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, story, 1_000L)
        val task = outbox.due(1_000L, 10).single()
        val window = StoryFeedbackOutbox.BROADCAST_MIN_INTERVAL_MS
        // Сводка ещё не созрела: пауза между волнами, а не отказ сети.
        outbox.defer(task, 1_000L, window)
        assertTrue(outbox.due(1_000L, 10).isEmpty())
        val later = outbox.due(1_000L + window, 10).single()
        // Отсрочка не считается попыткой и не снимает задачу.
        assertEquals(0, later.attempts)
        assertEquals(task.generation, later.generation)
        assertEquals(1, outbox.size())
        // Отсрочка не укорачивает прежнюю паузу: более ранний срок не применяется
        // (maxOf), иначе свежая сводка ушла бы раньше окна между волнами.
        outbox.defer(later, 1_000L, 10L)
        assertEquals(1_000L + window, outbox.snapshot().single().notBeforeMs)
    }

    @Test fun staleResultOfReplacedTaskCannotPostponeTheNewIntent() {
        val outbox = StoryFeedbackOutbox()
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, story, 1_000L)
        val stale = outbox.due(1_000L, 10).single()
        // Пока пакет был в пути, пришёл новый отклик: намерение перезаписано.
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, story, 1_200L)
        outbox.result(stale, false, 1_200L)
        val kept = outbox.snapshot().single()
        assertEquals(1_000L, kept.createdAtMs)
        // Чужой исход не отложил свежую сводку на минуту и не снял её.
        assertEquals(0, kept.attempts)
        assertTrue(kept.notBeforeMs <= 1_200L)
        assertEquals(1, outbox.due(1_200L, 10).size)
    }

    @Test fun capacityEvictsBroadcastsBeforeSomebodyElsesComment() {
        val outbox = StoryFeedbackOutbox(maxEntries = 3)
        outbox.enqueue(StoryFeedbackOutbox.Kind.COMMENT, story, 1_000L, peer = author, commentId = "c-1")
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, "s2", 1_000L)
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, "s3", 1_000L)
        outbox.enqueue(StoryFeedbackOutbox.Kind.UNICAST, "s4", 1_000L, peer = author)
        assertEquals(3, outbox.size())
        // Комментарий человека пережил вытеснение, а самая старая сводка - нет.
        assertTrue(outbox.contains(StoryFeedbackOutbox.key(StoryFeedbackOutbox.Kind.COMMENT, story, author, "c-1")))
        assertFalse(outbox.contains(StoryFeedbackOutbox.key(StoryFeedbackOutbox.Kind.BROADCAST, "s2", "", "")))
    }

    @Test fun purgeDropsTasksOlderThanTheStoryLifetime() {
        val outbox = StoryFeedbackOutbox()
        outbox.enqueue(StoryFeedbackOutbox.Kind.COMMENT, story, 1_000L, peer = author, commentId = "c-1")
        assertEquals(0, outbox.purge(1_000L))
        assertEquals(1, outbox.purge(1_000L + StoryFeedbackOutbox.TTL_MS))
        assertEquals(0, outbox.size())
        // Пакет из будущего (расхождение часов) не живёт в очереди.
        outbox.enqueue(StoryFeedbackOutbox.Kind.REACT, story, 1_000_000L, peer = author)
        assertEquals(1, outbox.purge(1_000L))
    }

    @Test fun revokedStoryTakesItsTasksWithIt() {
        val outbox = StoryFeedbackOutbox()
        outbox.enqueue(StoryFeedbackOutbox.Kind.REACT, story, 1_000L, peer = author)
        outbox.enqueue(StoryFeedbackOutbox.Kind.BROADCAST, story, 1_000L)
        outbox.enqueue(StoryFeedbackOutbox.Kind.REACT, "story-2", 1_000L, peer = author)
        assertEquals(2, outbox.forgetStory(story))
        assertEquals(1, outbox.size())
    }

    @Test fun invalidStoryIdIsNotQueued() {
        val outbox = StoryFeedbackOutbox()
        assertFalse(outbox.enqueue(StoryFeedbackOutbox.Kind.REACT, "", 1_000L, peer = author))
        assertFalse(outbox.enqueue(StoryFeedbackOutbox.Kind.REACT, "x".repeat(101), 1_000L, peer = author))
        assertEquals(0, outbox.size())
    }

    @Test fun viewerAsksForASummaryNoMoreOftenThanTheCooldown() {
        val outbox = StoryFeedbackOutbox()
        val cooldown = StoryFeedbackOutbox.ASK_COOLDOWN_MS
        // Первый раз откликов нет - спрашиваем.
        assertTrue(
            StoryFeedbackOutbox.shouldAsk(mine = false, nowMs = 10_000L, lastAskMs = 0L, lastChangeMs = 0L, hasAnyFeedback = false)
        )
        // Сразу повторно - нет.
        assertFalse(
            StoryFeedbackOutbox.shouldAsk(mine = false, nowMs = 10_000L + cooldown - 1, lastAskMs = 10_000L, lastChangeMs = 0L, hasAnyFeedback = false)
        )
        // Свежая сводка уже есть - спрашивать нечего.
        assertFalse(
            StoryFeedbackOutbox.shouldAsk(mine = false, nowMs = 10_000L + cooldown, lastAskMs = 0L, lastChangeMs = 10_000L + cooldown - 100, hasAnyFeedback = true)
        )
        // После окна охлаждения спрашиваем снова: сводка могла устареть.
        assertTrue(
            StoryFeedbackOutbox.shouldAsk(mine = false, nowMs = 10_000L + cooldown * 2, lastAskMs = 10_000L, lastChangeMs = 10_000L, hasAnyFeedback = true)
        )
        // Своя история: автор сам себе сводку не просит.
        assertFalse(
            StoryFeedbackOutbox.shouldAsk(mine = true, nowMs = 10_000L, lastAskMs = 0L, lastChangeMs = 0L, hasAnyFeedback = false)
        )
        // Просьба встаёт в очередь и не дублируется.
        assertTrue(outbox.enqueue(StoryFeedbackOutbox.Kind.ASK, story, 10_000L, peer = author))
        outbox.enqueue(StoryFeedbackOutbox.Kind.ASK, story, 10_500L, peer = author)
        assertEquals(1, outbox.size())
    }

    @Test fun authorBroadcastsOneWavePerIntervalAndOnlyWhenSomethingChanged() {
        val interval = StoryFeedbackOutbox.BROADCAST_MIN_INTERVAL_MS
        val debounce = StoryFeedbackOutbox.BROADCAST_DEBOUNCE_MS
        // Ничего нового с прошлой волны - пакет не нужен.
        assertFalse(StoryFeedbackOutbox.shouldBroadcast(5_000L, 0L, pendingVersion = 3L, sentVersion = 3L, queuedAtMs = 4_000L))
        // Есть новое, но окно слипания ещё не прошло: ждём пачку.
        assertFalse(StoryFeedbackOutbox.shouldBroadcast(4_500L, 0L, pendingVersion = 4L, sentVersion = 3L, queuedAtMs = 4_000L))
        assertTrue(StoryFeedbackOutbox.shouldBroadcast(4_000L + debounce, 0L, pendingVersion = 4L, sentVersion = 3L, queuedAtMs = 4_000L))
        // Чаще раза в интервал одна история не рассылается.
        assertFalse(
            StoryFeedbackOutbox.shouldBroadcast(10_000L, 9_000L, pendingVersion = 9L, sentVersion = 3L, queuedAtMs = 8_000L)
        )
        assertTrue(
            StoryFeedbackOutbox.shouldBroadcast(9_000L + interval, 9_000L, pendingVersion = 9L, sentVersion = 3L, queuedAtMs = 8_000L)
        )
    }

    @Test fun broadcastSkipsTheActorWhoAlreadyAppliedTheirOwnFeedback() {
        val contacts = listOf("pk_x", " pk_x ", "", "pk_y")
        assertEquals(listOf("pk_x", "pk_y"), StoryFeedbackOutbox.broadcastRecipients(contacts, null))
        assertEquals(listOf("pk_y"), StoryFeedbackOutbox.broadcastRecipients(contacts, "pk_x"))
    }
}
