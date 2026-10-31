package com.vladimir.messenger.data.diagnostics

// =============================================================================
// DIRECTCHANNELTEXT.KT — прямой канал по-человечески
// =============================================================================
// Ядро отдаёт машинную строку `DIRECT: bind=… public=… lan_v6=… relay=…`
// (`P2PCore::direct_channel_status`). Здесь она превращается в слова, которые
// видит человек в карточке «Сеть сообщений» и в отчёте «Логи».
//
// Зачем отдельный файл, а не строка в ядре: текст интерфейса живёт рядом с
// остальными разборами (MqttLinkText) и проверяется JVM-тестом без Android.
//
// Что означает каждая часть:
//   bind   — на каком стеке поднят прямой сокет. `ipv6` = dual-stack: один
//            сокет `[::]` принимает и IPv6, и IPv4 (`only_v6=false`);
//   public — какой внешний адрес мы знаем (STUN или отражение от соседа);
//   lan_v6 — есть ли у телефона свой IPv6 (нет — сеть IPv4-only);
//   relay  — нужен ли обходной путь: `not-needed` при живом прямом соседе;
//   ipv6   — kill-switch из настроек: `off` = IPv6 выключен человеком,
//            и тогда «в сети нет IPv6» — не диагноз, а следствие настройки.
// =============================================================================

/** Человекочитаемый разбор строки прямого канала. */
object DirectChannelText {

    /** Разобранная строка. null — это не наша строка (пусто/другой формат). */
    data class State(
        val bind: String,
        val public: String,
        val lanV6: Boolean,
        val relay: String,
        val ipv6Enabled: Boolean = true,
    ) {
        val up: Boolean get() = bind != "down"
        val relayNeeded: Boolean get() = relay == "fallback"
    }

    private val fieldRegex = Regex("(bind|public|lan_v6|relay|ipv6)=([a-z0-9_-]+)")

    /** Разбор; null — если строка не начинается с `DIRECT: ` или в ней нет `bind`. */
    fun parse(raw: String): State? {
        if (!raw.startsWith("DIRECT: ")) return null
        val fields = fieldRegex.findAll(raw)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val bind = fields["bind"] ?: return null
        return State(
            bind = bind,
            public = fields["public"] ?: "none",
            lanV6 = fields["lan_v6"] == "yes",
            relay = fields["relay"] ?: "fallback",
            // Старые строки ядра поля не знают — считаем IPv6 включённым.
            ipv6Enabled = fields["ipv6"] != "off",
        )
    }

    /** Человекочитаемая строка целиком; пусто — экран покажет только MQTT. */
    fun humanize(raw: String): String {
        val state = parse(raw) ?: return ""
        val head = when (state.bind) {
            "ipv6" -> "🟢 Прямой канал: IPv6 и IPv4"
            "ipv4" -> "🙂 Прямой канал: только IPv4"
            else -> "🔴 Прямой канал не поднят"
        }
        val parts = mutableListOf(head)
        when (state.public) {
            "none" -> parts += "внешний адрес ещё не определён"
            // Знакомый внешний адрес есть, но он IPv4: телефон за NAT,
            // IPv6-путь из интернета может быть недоступен.
            "ipv4" -> if (state.bind == "ipv6") parts += "из интернета нас видно по IPv4"
            "ipv6" -> if (state.bind == "ipv6") parts += "из интернета нас видно по IPv6" else
                parts += "внешний адрес уже IPv6"
            else -> Unit
        }
        // Порядок важен: если IPv6 выключен человеком, «в сети IPv6 нет» —
        // неверный совет. Говорим причину, а не следствие.
        if (!state.ipv6Enabled) parts += "IPv6 выключен в настройках — работаем по IPv4"
        else if (!state.lanV6) parts += "в этой сети IPv6 нет — работаем по IPv4"
        parts += when {
            state.relayNeeded -> "сейчас доставка идёт обходным путём (relay)"
            !state.up -> "обходной путь тоже запирается в ожидании"
            else -> "обходной путь не нужен"
        }
        return parts.joinToString(" · ")
    }
}
