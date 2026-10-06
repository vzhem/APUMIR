package com.vladimir.messenger.domain.model

data class Contact(
    val id: String,
    val displayName: String,
    val fingerprint: String = "",
    val isOnline: Boolean = false,
    /** Старое текстовое поле, сохранено для совместимости с ранними базами. */
    val lastSeen: String? = null,
    /** Когда этот телефон в последний раз наблюдал контакт в сети. */
    val lastSeenAtMs: Long? = null,
    /** Оригинальное имя через собаку, например @nickname. */
    val username: String = "",
)
