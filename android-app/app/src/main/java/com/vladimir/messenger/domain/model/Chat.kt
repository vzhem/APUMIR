package com.vladimir.messenger.domain.model

data class Chat(
    val id: String,
    val contactId: String = "",
    val contactName: String,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val isContactOnline: Boolean = false,
    /** Whether this conversation is pinned on the home inbox. */
    val isPinned: Boolean = false,
    val pinnedAtMs: Long? = null,
    /** р249: чат убран в архив - он цел, но не показывается в общем списке. */
    val isArchived: Boolean = false,
    /** р249: звук выключен до этого времени (0 - звук включён). */
    val mutedUntilMs: Long = 0L,
) {
    /** р249: звук выключен прямо сейчас. */
    fun isMuted(nowMs: Long = System.currentTimeMillis()): Boolean = mutedUntilMs > nowMs
}
