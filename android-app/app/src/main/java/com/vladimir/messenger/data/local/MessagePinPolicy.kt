package com.vladimir.messenger.data.local

/** Общая политика количества закреплённых сообщений в одном контексте. */
object MessagePinPolicy {
    const val MAX_PINNED_PER_SCOPE = 10

    const val LIMIT_REACHED_MESSAGE =
        "Лимит — 10 закреплённых сообщений. Открепите одно, чтобы добавить новое."

    const val SCOPE_CONFLICT_MESSAGE =
        "Сообщение закреплено в другом списке. Сначала открепите его там."

    /** Повторное закрепление уже закреплённой строки не занимает новое место. */
    fun canAddPin(currentPinnedCount: Int, alreadyPinned: Boolean = false): Boolean =
        alreadyPinned || currentPinnedCount.coerceAtLeast(0) < MAX_PINNED_PER_SCOPE
}
