package com.vladimir.messenger.data.story

import org.junit.Assert.*
import org.junit.Test

/**
 * Книга откликов: правила слияния пакетов, которые приходят в произвольном
 * порядке (роевая доставка порядок не гарантирует).
 */
class StoryFeedbackLedgerTest {

    private val story = "story-1"
    private val me = "pk_" + "a".repeat(32)
    private val friend = "pk_" + "b".repeat(32)

    private fun reaction(actor: String, emoji: String, atMs: Long) =
        StoryFeedbackWire.Reaction(actor, "Имя", emoji, atMs)

    private fun comment(id: String, actor: String, body: String, atMs: Long) =
        StoryFeedbackWire.Comment(id, actor, "Имя", body, atMs)

    @Test fun lastWriteWinsByTimeSoAnOldCopyCannotResurrectAReaction() {
        val ledger = StoryFeedbackLedger()
        assertTrue(ledger.applyReaction(story, reaction(friend, "🔥", 200L)))
        // Сняли позже, чем поставили: надгробие сильнее старой копии.
        assertTrue(ledger.applyReaction(story, reaction(friend, "", 300L)))
        assertEquals(0, ledger.summary(story, me).reactionTotal)
        // Опоздавшая копия «поставил» с более ранним временем ничего не меняет.
        assertFalse(ledger.applyReaction(story, reaction(friend, "🔥", 100L)))
        assertEquals(0, ledger.summary(story, me).reactionTotal)
        assertNull(ledger.myEmoji(story, friend))
    }

    @Test fun changeOfEmojiReplacesTheOldOneAndDoesNotDoubleCount() {
        val ledger = StoryFeedbackLedger()
        ledger.applyReaction(story, reaction(friend, "🔥", 100L))
        ledger.applyReaction(story, reaction(friend, "❤️", 200L))
        val summary = ledger.summary(story, me)
        assertEquals(1, summary.reactionTotal)
        assertEquals("❤️", summary.reactions.single().emoji)
    }

    @Test fun commentIsImmutableAndDuplicateDeliveryIsIgnored() {
        val ledger = StoryFeedbackLedger()
        assertTrue(ledger.applyComment(story, comment("c-1", friend, "Привет", 100L)))
        // Повторная волна того же комментария вторую копию не заводит.
        assertFalse(ledger.applyComment(story, comment("c-1", friend, "Привет", 100L)))
        // И подмена текста по тому же идентификатору не проходит.
        assertFalse(ledger.applyComment(story, comment("c-1", friend, "Другой текст", 300L)))
        assertEquals("Привет", ledger.summary(story, me).comments.single().body)
    }

    @Test fun emptyCommentIsNotAnEntry() {
        val ledger = StoryFeedbackLedger()
        assertFalse(ledger.applyComment(story, comment("c-2", friend, "", 100L)))
        assertTrue(ledger.summary(story, me).isEmpty)
    }

    @Test fun summaryCountsArePublicAndMarkOwnReaction() {
        val ledger = StoryFeedbackLedger()
        ledger.applyReaction(story, reaction(friend, "🔥", 100L))
        ledger.applyReaction(story, reaction("pk_" + "c".repeat(32), "🔥", 101L))
        ledger.applyReaction(story, reaction(me, "👍", 102L))
        val summary = ledger.summary(story, me)
        assertEquals(3, summary.reactionTotal)
        assertEquals("🔥", summary.reactions.first().emoji)
        assertEquals(2, summary.reactions.first().count)
        assertEquals("👍", summary.myEmoji)
        assertTrue(summary.reactions.first { it.emoji == "👍" }.mine)
        assertFalse(summary.reactions.first { it.emoji == "🔥" }.mine)
    }

    @Test fun commentsAreSortedOldestFirstForTheFeed() {
        val ledger = StoryFeedbackLedger()
        ledger.applyComment(story, comment("c-3", friend, "третье", 300L))
        ledger.applyComment(story, comment("c-1", friend, "первое", 100L))
        ledger.applyComment(story, comment("c-2", friend, "второе", 200L))
        assertEquals(listOf("первое", "второе", "третье"), ledger.summary(story, me).comments.map { it.body })
    }

    @Test fun capsAreHeldAndTheOldestEntriesAreEvicted() {
        val ledger = StoryFeedbackLedger(maxReactionsPerStory = 3, maxCommentsPerStory = 2)
        (1..6).forEach { ledger.applyReaction(story, reaction("pk_$it", "🔥", it.toLong())) }
        (1..6).forEach { ledger.applyComment(story, comment("c-$it", friend, "текст $it", it.toLong())) }
        val summary = ledger.summary(story, me)
        assertEquals(3, summary.reactionTotal)
        assertEquals(2, summary.comments.size)
        assertEquals(listOf("текст 5", "текст 6"), summary.comments.map { it.body })
    }

    @Test fun stateFromTheAuthorMergesIntoWhatTheViewerAlreadyHas() {
        val ledger = StoryFeedbackLedger()
        // Зритель уже поставил свой значок, а сводка автора его ещё не знает.
        ledger.applyReaction(story, reaction(me, "👍", 500L))
        val applied = ledger.applyState(
            story,
            listOf(reaction(friend, "🔥", 100L), reaction(me, "👍", 400L)),
            listOf(comment("c-1", friend, "Привет", 100L)),
        )
        assertEquals(2, applied)
        val summary = ledger.summary(story, me)
        // Своя более новая запись не затёрта старой из сводки.
        assertEquals(2, summary.reactionTotal)
        assertEquals("👍", summary.myEmoji)
        assertEquals(1, summary.comments.size)
        // Повтор той же сводки ничего не меняет: рассылать дальше нечего.
        assertEquals(
            0,
            ledger.applyState(story, listOf(reaction(friend, "🔥", 100L)), listOf(comment("c-1", friend, "Привет", 100L))),
        )
    }

    @Test fun perStoryVersionMovesOnlyForThatStory() {
        val ledger = StoryFeedbackLedger()
        ledger.applyReaction(story, reaction(friend, "🔥", 100L))
        val first = ledger.versionOf(story)
        assertEquals(0L, ledger.versionOf("story-2"))
        ledger.applyReaction("story-2", reaction(friend, "🔥", 100L))
        assertEquals(first, ledger.versionOf(story))
        assertEquals(1L, ledger.versionOf("story-2"))
        assertTrue(ledger.version >= 2L)
    }

    @Test fun revokedStoryTakesItsFeedbackWithIt() {
        val ledger = StoryFeedbackLedger()
        ledger.applyReaction(story, reaction(friend, "🔥", 100L))
        ledger.applyComment(story, comment("c-1", friend, "Привет", 100L))
        assertTrue(ledger.forget(story))
        assertTrue(ledger.summary(story, me).isEmpty)
        assertEquals(0L, ledger.versionOf(story))
        assertFalse(ledger.forget(story))
    }

    @Test fun retainDropsFeedbackOfStoriesThatAreNoLongerAlive() {
        val ledger = StoryFeedbackLedger()
        ledger.applyReaction(story, reaction(friend, "🔥", 100L))
        ledger.applyReaction("story-2", reaction(friend, "🔥", 100L))
        assertEquals(1, ledger.retain(setOf(story)))
        assertTrue(ledger.summary("story-2", me).isEmpty)
        assertFalse(ledger.summary(story, me).isEmpty)
        assertEquals(0, ledger.retain(setOf(story)))
    }

    @Test fun snapshotRestoresTheSameBook() {
        val ledger = StoryFeedbackLedger()
        ledger.applyReaction(story, reaction(friend, "🔥", 100L))
        ledger.applyReaction(story, reaction(me, "", 200L))
        ledger.applyComment(story, comment("c-1", friend, "Привет", 150L))
        val rows = ledger.rows()
        val restored = StoryFeedbackLedger()
        restored.restore(rows)
        assertEquals(ledger.summary(story, me), restored.summary(story, me))
        // Надгробие снятого значка переживает снимок: значок не воскреснет.
        assertTrue(restored.reactionEntries(story).any { it.removed })
    }

    @Test fun tooManyStoriesAreEvictedOldestFirst() {
        val ledger = StoryFeedbackLedger(maxStories = 2)
        ledger.applyReaction("s1", reaction(friend, "🔥", 100L))
        ledger.applyReaction("s2", reaction(friend, "🔥", 200L))
        ledger.applyReaction("s3", reaction(friend, "🔥", 300L))
        assertTrue(ledger.knownStoryIds().size <= 2)
        assertFalse(ledger.summary("s3", me).isEmpty)
    }

    @Test fun blankIdentifiersAreRejected() {
        val ledger = StoryFeedbackLedger()
        assertFalse(ledger.applyReaction("", reaction(friend, "🔥", 100L)))
        assertFalse(ledger.applyReaction(story, reaction("", "🔥", 100L)))
        assertFalse(ledger.applyComment(story, comment("", friend, "текст", 100L)))
        assertTrue(ledger.summary(story, me).isEmpty)
    }
}
