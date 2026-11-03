package com.vladimir.messenger.data.directpoll

/**
 * Правила личного опроса без Android, сети и базы — только то, что видно
 * человеку: какой черновик годится, что делает нажатие на вариант, кто вправе
 * закрыть опрос.
 *
 * Зачем отдельно. В личном чате опрос — это ровно два голоса, и соблазн
 * размазать «плюс-минус вариант» по трём местам (пузырь, окно создания,
 * приём) велик. Здесь одна проверяемая точка, её и покрывает JVM-тест
 * `DirectPollTallyTest`.
 */
object DirectPollTally {

    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 10
    const val MAX_OPTION_CHARS = 100
    const val MAX_QUESTION_CHARS = 200

    /**
     * Черновик из окна создания, приведённый к тому, что реально уйдёт:
     * пробелы убраны, пустые варианты выброшены, длинные обрезаны, лишние
     * варианты отброшены. Возвращает null, если опрос не годен.
     */
    fun normalize(question: String, options: List<String>): Pair<String, List<String>>? {
        val cleanQuestion = question.trim().take(MAX_QUESTION_CHARS)
        val cleanOptions = options
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_OPTIONS)
            .map { it.take(MAX_OPTION_CHARS) }
        if (cleanQuestion.isBlank() || cleanOptions.size < MIN_OPTIONS) return null
        return cleanQuestion to cleanOptions
    }

    /**
     * Нажатие на вариант в опросе с одним выбором: повторное нажатие на свой
     * вариант отзывает голос, другой вариант — переголосовывает.
     */
    fun toggle(current: List<Int>, index: Int): List<Int> =
        if (current == listOf(index)) emptyList() else listOf(index)

    /** Индекс варианта существует в опросе из [optionCount] вариантов. */
    fun isValidChoice(optionCount: Int, index: Int): Boolean = index in 0 until optionCount

    /**
     * Кто может закрыть опрос: только автор. В личном чате «автор» — тот, чьё
     * это исходящее сообщение; поэтому кнопку видит лишь отправитель вопроса.
     */
    fun canClose(isMine: Boolean, closed: Boolean): Boolean = isMine && !closed

    /** Можно ли голосовать: опрос не закрыт и вариант существует. */
    fun canVote(closed: Boolean, optionCount: Int, index: Int): Boolean =
        !closed && isValidChoice(optionCount, index)
}
