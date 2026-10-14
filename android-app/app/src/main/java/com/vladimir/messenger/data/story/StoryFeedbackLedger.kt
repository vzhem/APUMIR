package com.vladimir.messenger.data.story

/**
 * Книга откликов на истории: значки и комментарии по каждой истории.
 *
 * Правила слияния - как у реестра, который собирается из пакетов в произвольном
 * порядке (роевая доставка порядок не гарантирует):
 *
 *  - значок одного человека - «побеждает последний по времени»: снятая реакция
 *    (надгробие с пустым значком) не воскрешается более старой копией, которая
 *    дошла позже;
 *  - комментарий неизменяем, поэтому «побеждает первый»: повторная волна того же
 *    комментария (тот же идентификатор) вторую копию не заводит;
 *  - отсутствие записи в чужой сводке НЕ означает удаление: сводка ограничена по
 *    размеру и приходит целиком, а удаляет только явное надгробие.
 *
 * Класс намеренно чистый Kotlin (ни Android, ни Room, ни coroutines): его
 * компилирует и проверяет `scripts/ci/check-chat-history.sh` на runner без
 * Android SDK, то есть правила слияния ловятся до выпуска.
 */
class StoryFeedbackLedger(
    private val maxStories: Int = MAX_STORIES,
    private val maxReactionsPerStory: Int = MAX_REACTIONS_PER_STORY,
    private val maxCommentsPerStory: Int = MAX_COMMENTS_PER_STORY,
) {

    /** Значок и сколько человек его поставили. */
    data class ReactionCount(val emoji: String, val count: Int, val mine: Boolean)

    /** Что показывает экран одной истории. */
    data class Summary(
        val reactions: List<ReactionCount>,
        val reactionTotal: Int,
        /** Мой значок на этой истории или null. */
        val myEmoji: String?,
        /** Кто именно реагировал - список для автора и для карточки значков. */
        val actors: List<StoryFeedbackWire.Reaction>,
        val comments: List<StoryFeedbackWire.Comment>,
        /** Время последнего принятого отклика: по нему экран решает, пора ли спросить сводку. */
        val updatedAtMs: Long,
    ) {
        val isEmpty: Boolean get() = reactions.isEmpty() && comments.isEmpty()
    }

    /**
     * Одна история в локальном снимке на диске.
     *
     * Названа не `Row`: имя `Row` в проекте занято композиционной строкой, и
     * проверка имён (`tools/sandbox/check_unresolved_symbols.py`) считает его
     * именем проекта - после этого каждый Compose-файл с `layout.*` начинает
     * выглядеть подозрительным.
     */
    data class Snapshot(
        val storyId: String,
        val reactions: List<StoryFeedbackWire.Reaction>,
        val comments: List<StoryFeedbackWire.Comment>,
    )

    private val reactions = LinkedHashMap<String, MutableMap<String, StoryFeedbackWire.Reaction>>()
    private val comments = LinkedHashMap<String, MutableMap<String, StoryFeedbackWire.Comment>>()
    private val touched = LinkedHashMap<String, Long>()
    private val versions = LinkedHashMap<String, Long>()

    /** Растёт при каждом принятом изменении: по нему экран понимает «перерисовать». */
    var version: Long = 0
        private set

    fun knownStoryIds(): Set<String> = reactions.keys + comments.keys

    fun lastChangeAt(storyId: String): Long = touched[storyId] ?: 0L

    /**
     * Отдельный счётчик истории: рассылка сводки сравнивает его с версией
     * последней отправленной волны. Общий [version] для этого не годится -
     * отклик на чужую историю поднял бы «есть новое» у всех остальных.
     */
    fun versionOf(storyId: String): Long = versions[storyId] ?: 0L

    /** Значок одного человека: поставить, заменить или снять (пустой значок). */
    fun applyReaction(storyId: String, reaction: StoryFeedbackWire.Reaction): Boolean {
        if (storyId.isEmpty() || reaction.actorId.isEmpty()) return false
        val table = reactions.getOrPut(storyId) { LinkedHashMap<String, StoryFeedbackWire.Reaction>() }
        val existing = table[reaction.actorId]
        if (existing != null && existing.atMs > reaction.atMs) return false
        if (existing == reaction) return false
        table[reaction.actorId] = reaction
        trimReactions(storyId, table)
        bump(storyId, reaction.atMs)
        return true
    }

    /** Новый комментарий. Повтор того же идентификатора ничего не меняет. */
    fun applyComment(storyId: String, comment: StoryFeedbackWire.Comment): Boolean {
        if (storyId.isEmpty() || comment.id.isEmpty() || comment.actorId.isEmpty()) return false
        if (comment.body.isEmpty()) return false
        val table = comments.getOrPut(storyId) { LinkedHashMap<String, StoryFeedbackWire.Comment>() }
        if (table.containsKey(comment.id)) return false
        table[comment.id] = comment
        while (table.size > maxCommentsPerStory) {
            val oldest = table.values.minByOrNull { it.atMs }?.id ?: break
            table.remove(oldest)
        }
        bump(storyId, comment.atMs)
        return true
    }

    /**
     * Сводка автора: применить всё, что в ней есть. Возвращает число принятых
     * записей - ноль значит «у нас уже то же самое», рассылать дальше нечего.
     */
    fun applyState(
        storyId: String,
        incomingReactions: List<StoryFeedbackWire.Reaction>,
        incomingComments: List<StoryFeedbackWire.Comment>,
    ): Int {
        var applied = 0
        incomingReactions.forEach { if (applyReaction(storyId, it)) applied++ }
        incomingComments.forEach { if (applyComment(storyId, it)) applied++ }
        return applied
    }

    /** Запись значка одного человека: нужна, чтобы дослать своё действие. */
    fun reactionOf(storyId: String, actorId: String): StoryFeedbackWire.Reaction? =
        reactions[storyId]?.get(actorId)

    /** Все записи значков, включая надгробия снятых: сводка обязана нести снятие. */
    fun reactionEntries(storyId: String): List<StoryFeedbackWire.Reaction> =
        reactions[storyId].orEmpty().values.sortedBy { it.atMs }

    /** Комментарий по идентификатору: по нему досылаем свой текст. */
    fun commentById(storyId: String, commentId: String): StoryFeedbackWire.Comment? =
        comments[storyId]?.get(commentId)

    fun commentEntries(storyId: String): List<StoryFeedbackWire.Comment> =
        comments[storyId].orEmpty().values.sortedBy { it.atMs }

    /** Мой значок на истории (для экрана и для повторной отправки снятия). */
    fun myEmoji(storyId: String, ownId: String?): String? {
        if (ownId.isNullOrEmpty()) return null
        return reactions[storyId]?.get(ownId)?.emoji?.takeIf { it.isNotEmpty() }
    }

    fun summary(storyId: String, ownId: String?): Summary {
        val own = if (ownId.isNullOrEmpty()) null else ownId
        val actors = reactions[storyId].orEmpty().values
            .filter { !it.removed }
            .sortedWith(compareByDescending<StoryFeedbackWire.Reaction> { it.atMs })
        val counts = actors.groupBy { it.emoji }
            .map { (emoji, rows) ->
                ReactionCount(emoji, rows.size, rows.any { it.actorId == own })
            }
            .sortedWith(compareByDescending<ReactionCount> { it.count }.thenBy { it.emoji })
        val list = comments[storyId].orEmpty().values.sortedBy { it.atMs }
        return Summary(
            reactions = counts,
            reactionTotal = actors.size,
            myEmoji = actors.firstOrNull { it.actorId == own }?.emoji,
            actors = actors,
            comments = list,
            updatedAtMs = touched[storyId] ?: list.lastOrNull()?.atMs ?: 0L,
        )
    }

    /** История отозвана или истекла - отклики за ней не оставляем. */
    fun forget(storyId: String): Boolean {
        val hadReaction = reactions.remove(storyId) != null
        val hadComment = comments.remove(storyId) != null
        touched.remove(storyId)
        versions.remove(storyId)
        if (hadReaction || hadComment) version++
        return hadReaction || hadComment
    }

    /**
     * Уборка по живым историям. Возвращает число историй, чьи отклики удалены:
     * лента историй живёт сутки, и книга откликов не должна жить дольше.
     */
    fun retain(aliveStoryIds: Set<String>): Int {
        val dead = knownStoryIds().filterNot { it in aliveStoryIds }
        dead.forEach { forget(it) }
        while (reactions.size + comments.size > maxStories * 2) {
            val oldest = touched.entries.minByOrNull { it.value }?.key ?: break
            forget(oldest)
        }
        return dead.size
    }

    fun rows(): List<Snapshot> = knownStoryIds().map { storyId ->
        Snapshot(
            storyId = storyId,
            reactions = reactions[storyId].orEmpty().values.sortedBy { it.atMs },
            comments = comments[storyId].orEmpty().values.sortedBy { it.atMs },
        )
    }.sortedBy { touched[it.storyId] ?: 0L }

    /** Восстановление из снимка: те же правила слияния, что и из сети. */
    fun restore(rows: List<Snapshot>) {
        rows.take(maxStories).forEach { row ->
            row.reactions.forEach { applyReaction(row.storyId, it) }
            row.comments.forEach { applyComment(row.storyId, it) }
        }
    }

    private fun trimReactions(
        storyId: String,
        table: MutableMap<String, StoryFeedbackWire.Reaction>,
    ) {
        while (table.size > maxReactionsPerStory) {
            val oldest = table.values.minByOrNull { it.atMs }?.actorId ?: break
            table.remove(oldest)
        }
        if (table.isEmpty()) reactions.remove(storyId)
    }

    private fun bump(storyId: String, atMs: Long) {
        touched[storyId] = maxOf(touched[storyId] ?: 0L, atMs)
        versions[storyId] = (versions[storyId] ?: 0L) + 1
        version++
        while (touched.size > maxStories) {
            val oldest = touched.entries.minByOrNull { it.value }?.key ?: break
            forget(oldest)
        }
    }

    companion object {
        /** Историй в ленте не больше сотни (`StoryStore`), отклики держим по той же планке. */
        const val MAX_STORIES = 100

        /** Значков на одну историю: по одному на человека, больше зрителей не бывает. */
        const val MAX_REACTIONS_PER_STORY = 64

        /** Комментариев на одну историю: дальше вытесняются самые старые. */
        const val MAX_COMMENTS_PER_STORY = 40

        /** Пустая сводка - экран показывает её, пока откликов нет. */
        val EMPTY_SUMMARY = Summary(
            reactions = emptyList(),
            reactionTotal = 0,
            myEmoji = null,
            actors = emptyList(),
            comments = emptyList(),
            updatedAtMs = 0L,
        )
    }
}
