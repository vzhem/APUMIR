package com.vladimir.messenger.data.diagnostics

// =============================================================================
// MQTTLINKTEXT.KT — «Сообщения сети» по-человечески, одним разбором на всё
// =============================================================================
// Ядро отдаёт одну строку: «MQTT: <режим>, ConnAck N с назад[, ошибка N с
// назад: текст]». Её показывают и карточка «Сообщения сети» в настройках, и
// отчёт «Логи». Раньше разбор жил внутри SettingsViewModel; теперь он здесь,
// чтобы оба места говорили ОДНО И ТО ЖЕ и чтобы формат проверялся
// JVM-тестами на runner (файл не тянет Android, см. DiagnosticsReport.kt).
//
// Логика статуса: что произошло ПОЗЖЕ — то и состояние. ConnAck приходит раз
// на (пере)подключение, поэтому «подтверждена N назад» = сколько живёт
// текущая сессия связи с брокером.
// =============================================================================

/** Человекочитаемый разбор строки MQTT ядра. */
object MqttLinkText {

    /** Разобранная строка. null — это не строка MQTT (пусто/другой формат). */
    data class State(
        /** «через наш сервер», «напрямую», «через мост»… Адрес сервера не показываем. */
        val path: String,
        val connAckAgoSec: Int?,
        val errorAgoSec: Int?,
        val errorText: String,
    ) {
        /** Связь подтверждена, и после неё ошибок не было. */
        val connected: Boolean
            get() = connAckAgoSec != null && (errorAgoSec == null || connAckAgoSec <= errorAgoSec)

        /** Связь была, но позже сорвалась: сейчас переподключаемся сами. */
        val droppedAfterConnect: Boolean
            get() = connAckAgoSec != null && errorAgoSec != null && errorAgoSec < connAckAgoSec
    }

    private val connAckRegex = Regex("ConnAck (\\d+) с назад")
    private val errorRegex = Regex(", ошибка (\\d+) с назад: (.+)$")

    /** Разбор; null — если строка не начинается с «MQTT: » или режим пуст. */
    fun parse(raw: String): State? {
        if (!raw.startsWith("MQTT: ")) return null
        val body = raw.removePrefix("MQTT: ")
        val path = pathOf(body) ?: return null
        val connAckSec = connAckRegex.find(body)?.groupValues?.get(1)?.toIntOrNull()
        val errMatch = errorRegex.find(body)
        val errSec = errMatch?.groupValues?.get(1)?.toIntOrNull()
        val errText = errMatch?.groupValues?.get(2)?.trim().orEmpty()
        return State(path = path, connAckAgoSec = connAckSec, errorAgoSec = errSec, errorText = errText)
    }

    /** Человекочитаемая строка целиком; пусто = экран покажет сырую строку ядра. */
    fun humanize(raw: String): String {
        val state = parse(raw) ?: return ""
        val head = when {
            state.connected ->
                "🟢 На связи (подтверждена ${humanAgo(state.connAckAgoSec ?: 0)})"
            state.connAckAgoSec != null ->
                "🟡 Был перебой ${humanAgo(state.errorAgoSec ?: 0)} — подключаемся снова сами"
            state.errorAgoSec != null ->
                "🟡 Нет ответа, соединение восстанавливается автоматически " +
                    "(${humanError(state.errorText)})"
            else -> "🟡 Подключаемся…"
        }
        return "$head · ${state.path}"
    }

    /**
     * Путь связи. Адрес сервера не показываем (просьба владельца, 2026-09-19):
     * в интерфейсе только нейтральные слова.
     */
    fun pathOf(body: String): String? {
        val mode = body.substringBefore(", ConnAck").trim()
        return when {
            mode.isEmpty() -> null
            mode.startsWith("wss") -> "через обходной канал"
            mode.contains("наш брокер") &&
                (mode.contains("переход") || mode.contains("не ответили")) ->
                "через наш сервер (прямой путь не прошёл)"
            mode.contains("наш брокер") -> "через наш сервер"
            mode.contains("SOCKS5") -> "через мост"
            mode.startsWith("tcp") -> "напрямую"
            else -> mode
        }
    }

    /** «45 с назад» / «6 мин назад» / «3 ч назад» / «2 дн назад». */
    fun humanAgo(sec: Int): String = when {
        sec < 60 -> "$sec с назад"
        sec < 3600 -> "${sec / 60} мин назад"
        sec < 86_400 -> "${sec / 3600} ч назад"
        else -> "${sec / 86_400} дн назад"
    }

    /** Частые сетевые ошибки — по-человечески; незнакомое остаётся как есть. */
    fun humanError(rawError: String): String {
        val e = rawError.lowercase()
        return when {
            e.contains("refused") -> "узел не принял связь"
            e.contains("timed out") || e.contains("timeout") -> "не дождались ответа"
            e.contains("unreachable") -> "сеть до узла не доходит"
            e.contains("reset by peer") || e.contains("connection closed") -> "связь оборвалась"
            e.contains("dns") || e.contains("name or service not known") ||
                e.contains("lookup") -> "не удалось найти адрес узла"
            rawError.isBlank() -> "сеть не ответила"
            else -> rawError
        }
    }
}
