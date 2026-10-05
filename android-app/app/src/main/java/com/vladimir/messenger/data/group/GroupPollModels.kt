package com.vladimir.messenger.data.group

/**
 * Доменные модели опросов в группах, темах и каналах (р250).
 *
 * UI работает с ними, а не с Room-сущностями: тот же принцип, что у
 * [GroupSummary]. Итоги считает репозиторий из строк голосов, поэтому
 * [PollSummary.counts] всегда одной длины с [PollSummary.options].
 */
data class PollOption(
    val index: Int,
    val text: String,
    /** Сколько участников отметили этот вариант. */
    val votes: Int = 0,
)

data class PollVoter(
    val nodeId: String,
    val name: String,
    /** Отмеченные варианты (номера с нуля). */
    val choices: List<Int>,
)

data class PollSummary(
    val pollId: String,
    val groupId: String,
    val topicId: String,
    /** Сообщение (или пост канала), под которым показывается карточка. */
    val messageId: String,
    val question: String,
    val options: List<PollOption>,
    val anonymous: Boolean = false,
    val multiChoice: Boolean = false,
    val creatorId: String = "",
    val createdAtMs: Long = 0L,
    val closed: Boolean = false,
    val isMine: Boolean = false,
    /**
     * Мой текущий выбор (номера вариантов). Пусто - я ещё не голосовал или
     * отозвал голос; в анонимном опросе это единственный способ показать
     * галочку у своего варианта, не раскрывая имён.
     */
    val myChoices: List<Int> = emptyList(),
    /** Кто как проголосовал: пуст у анонимного опроса - имена не показываем. */
    val voters: List<PollVoter> = emptyList(),
) {
    /** Сколько всего участников проголосовало (не сумма голосов: в опросе с несколькими вариантами они различаются). */
    val totalVoters: Int get() = voters.size

    /** Сколько всего голосов отдано (в опросе с несколькими вариантами больше числа проголосовавших). */
    val totalVotes: Int get() = options.sumOf { it.votes }

    /** Процент от общего числа голосов; 0, если голосов ещё нет. */
    fun percentOf(index: Int): Int {
        val total = totalVotes
        if (total <= 0) return 0
        return (options.getOrNull(index)?.votes ?: 0) * 100 / total
    }
}

/** Черновик опроса из окна создания: что ввёл человек до нажатия «Создать». */
data class PollDraft(
    val question: String,
    val options: List<String>,
    val anonymous: Boolean = false,
    val multiChoice: Boolean = false,
) {
    /** Годен ли черновик: есть вопрос и хотя бы два непустых варианта. */
    fun isValid(): Boolean = question.isNotBlank() &&
        options.count { it.isNotBlank() } >= GroupWire.MIN_POLL_OPTIONS
}
