package com.vladimir.messenger.domain.model

/**
 * Канал доставки сообщения
 */
enum class MessageChannel {
    MQTT,       // Через MQTT broker (основной)
    CF,         // Через Cloudflare relay (fallback)
    LOCAL,      // Локальная сеть (P2P/mDNS)
    UNKNOWN;    // Неизвестно

    fun toIcon(): String = when (this) {
        MQTT -> "✈️"
        CF -> "🌐"
        LOCAL -> "📶"
        UNKNOWN -> "❓"
    }
}
