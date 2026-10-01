package com.vladimir.messenger.data.local

/** Policy for pins shared by all conversation rows on the main inbox. */
object InboxPinPolicy {
    const val MAX_PINNED = 10
    const val LIMIT_REACHED_MESSAGE = "Можно закрепить не более 10 бесед"

    fun canAddPin(currentCount: Int): Boolean = currentCount < MAX_PINNED
}
