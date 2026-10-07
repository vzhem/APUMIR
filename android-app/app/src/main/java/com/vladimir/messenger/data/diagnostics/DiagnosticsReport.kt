package com.vladimir.messenger.data.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.vladimir.messenger.data.file.FileTransferRetention

// =============================================================================
// DIAGNOSTICSREPORT.KT — текст отчёта «Логи» без Android
// =============================================================================
// Разделение нарочно такое:
//   * ЭТОТ файл — только чистые данные и строки (никаких Context, JNI, Room),
//     поэтому его компилирует и гоняет scripts/ci/check-chat-history.sh на
//     runner вместе с обычными JVM-тестами. Так формат отчёта проверяется
//     ДО выпуска, а не на телефоне владельца;
//   * TransferDiagnostics.kt — сбор фактов из Android/ядра и журнал событий.
//
// Правило отчёта: всё, что видит человек, — по-русски; служебные ключи внутри
// разделов оставлены латиницей, чтобы отчёт можно было разобрать машиной.
// В отчёт НИКОГДА не попадают: текст переписки, имена файлов, ключи,
// шифротекст, contact ID и адреса (см. TransferDiagnostics.redact).
// =============================================================================

/** Уровень строки сводки или записи журнала. */
enum class DiagnosticsLevel {
    /** Всё в порядке. */
    OK,

    /** Нейтральная справка. */
    INFO,

    /** Внимание: само по себе не поломка, но объясняет поведение. */
    WARN,

    /** Поломка: передача/связь/ядро не работают. */
    BAD,
}

/** Тон строки готового отчёта: что подсветить цветом в окне «Логи». */
enum class DiagnosticsLineTone {
    /** Заголовок раздела: «[ядро]», «[журнал] (последние события этой сессии)». */
    SECTION,

    /** Поломка: строка сводки с «✖» или запись журнала с «✖». */
    BAD,

    /** Внимание: строка сводки с «!» или запись журнала с «!». */
    WARN,
}

// Заголовки разделов перечислены явно: под этот шаблон не попадут ни метки
// приватности вида [contact], ни скрытые тела сообщений.
private val toneSectionRegex = Regex(
    "^\\[(?:сводка|окружение|сеть|ядро|mqtt|сообщения|передачи|сессия|журнал|лог процесса)\\]",
)
private val toneBadSummaryRegex = Regex("^✖")
private val toneWarnSummaryRegex = Regex("^!")
private val toneBadJournalRegex = Regex("^\\d{2}:\\d{2}:\\d{2} ✖")
private val toneWarnJournalRegex = Regex("^\\d{2}:\\d{2}:\\d{2} !")

/**
 * Тон строки отчёта: раздел, ошибка, внимание — или null для обычного текста.
 * Функция чистая и без Android: цвет выбирает окно «Логи», а само правило
 * проверяется JVM-тестами. Раскраска при этом не меняет ни одного символа —
 * текст копируется и уезжает разработчику ровно таким, как собран.
 */
fun diagnosticsLineTone(line: String): DiagnosticsLineTone? {
    val trimmed = line.trimStart()
    return when {
        toneSectionRegex.containsMatchIn(trimmed) -> DiagnosticsLineTone.SECTION
        toneBadSummaryRegex.containsMatchIn(trimmed) ||
            toneBadJournalRegex.containsMatchIn(trimmed) -> DiagnosticsLineTone.BAD
        toneWarnSummaryRegex.containsMatchIn(trimmed) ||
            toneWarnJournalRegex.containsMatchIn(trimmed) -> DiagnosticsLineTone.WARN
        else -> null
    }
}

/** Готовая строка сводки: «Сеть — мобильная, интернет есть». */
data class DiagnosticsLine(
    val level: DiagnosticsLevel,
    val title: String,
    val value: String,
)

/** Запись журнала процесса: время, уровень, область (app/net/core/F4/…) и факт без секретов. */
data class DiagnosticsJournalEntry(
    val atMs: Long,
    val level: DiagnosticsLevel,
    val area: String,
    val detail: String,
)

/**
 * Факты отчёта, собранные на телефоне. Обычные значения: ни Android, ни ядра
 * здесь уже нет — только то, что человеку нужно прочитать.
 */
data class DiagnosticsFacts(
    val createdAtMs: Long,
    val sessionStartedAtMs: Long,
    val appVersion: String,
    val androidRelease: String,
    val androidSdk: Int,
    val device: String,
    val abis: String,
    val locale: String,
    val memoryUsedMb: Long,
    val memoryLimitMb: Long,
    val storageFreeMb: Long,
    val networkAvailable: Boolean,
    val networkTransport: String,
    val networkInternet: Boolean,
    val networkMetered: Boolean,
    val networkVpn: Boolean,
    val notificationsAllowed: Boolean,
    val proxyTunnel: Boolean,
    val swarmMode: String,
    val serverMode: Boolean,
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val powerSave: Boolean,
    /** true = оптимизация батареи снята, приложение может жить в фоне. */
    val batteryExempt: Boolean,
    val deviceIdle: Boolean,
    val coreRunning: Boolean,
    val coreBuild: String,
    val coreStage: String,
    val coreReady: Boolean,
    val coreRestarts: Long,
    val networkStatus: String,
    val connectedPeers: Long,
    val pendingEvents: Long,
    val relayCustody: String,
    val relayQuarantine: Long,
    /** Сырая строка MQTT из ядра («MQTT: <режим>, ConnAck N с назад…»). */
    val mqttLine: String,
    /** Число передач по состояниям из базы (COMPLETE, FAILED, …). */
    val transferStates: Map<String, Long>,
    /** Коды ошибок передач (без имён файлов): объясняют «ошибок N». */
    val failureCodes: List<TransferErrorLine>,
    /** Когда последняя передача упала (null — не падала); для «N назад». */
    val lastFailureAtMs: Long?,
    /** Сколько чужих байт держим для получателей не в сети. */
    val custodyBytes: Long,
    /** Счётчики сессии (см. [Counters]). */
    val counters: Map<String, Long>,
    /** Когда последнее входящее легло в переписку (0 — в этой сессии не было). */
    val lastIncomingAtMs: Long = 0L,
    /** Когда последнее исходящее ушло с этого телефона (0 — не было). */
    val lastOutgoingAtMs: Long = 0L,
    /** Когда пришло последнее подтверждение доставки (0 — не было). */
    val lastAckAtMs: Long = 0L,
    /**
     * Когда собеседник присылал новый ключ шифрования (0 — не было). Это
     * самая частая причина «от него не приходят»: он переустановил приложение,
     * мы намеренно не снимаем закреплённый ключ, и переписка молчит.
     */
    val peerKeyChangedAtMs: Long = 0L,
    /**
     * Сколько сигналов о сообщениях ядро передало приложению — по строкам
     * журнала процесса. Если это число растёт, а «входящих показано» нет,
     * потеря происходит у нас, а не в сети.
     */
    val coreMessageSignals: Long = 0L,
    /**
     * Сколько раз пересылка отбросила пакет: очередь получателя переполнена.
     * Это пакеты для ДРУГИХ узлов — телефон-хранитель не успевает их раздать.
     */
    val relayQueueFull: Long = 0L,
    /**
     * Сколько СВОИХ сообщений ждёт получателя в очереди ядра (null — ядро
     * ещё не писало сводку, значит очередь пуста). Своя переписка живёт
     * отдельно от чужой пересылки и никогда ею не вытесняется.
     */
    val relayQueueOwn: Long? = null,
    /** Сколько ЧУЖИХ сообщений (пересылка для других узлов) лежит в очереди. */
    val relayQueueForeign: Long? = null,
    /**
     * Сколько из «своих» — служебное: сигналы транспорта (NAT-пробивание,
     * «печатает…») и пакеты файловой передачи. Владелец 2026-10-07 прислал
     * отчёт со строкой «своё ждёт получателя=1000»: по такому числу нельзя
     * было понять, что очередь занята служебным, а не перепиской.
     */
    val relayQueueOwnSignals: Long? = null,
)

/** Имена счётчиков сессии: один список для записи (хуки) и для отчёта. */
object Counters {
    const val F4_NEGOTIATED = "f4_negotiated"
    const val F4_RANGES_SENT = "f4_ranges_sent"
    const val F4_RANGES_RECEIVED = "f4_ranges_received"
    const val F4_BYTES_SENT = "f4_bytes_sent"
    const val F4_BYTES_RECEIVED = "f4_bytes_received"
    const val F4_RANGE_FAILURES = "f4_range_failures"
    /** FCAP v2 пришёл по UDP: прямой бинарный канал не включаем (совместимость). */
    const val F4_UDP_FALLBACK = "f4_udp_fallback"
    const val FILE_STARTED_OUT = "file_started_out"
    const val FILE_STARTED_IN = "file_started_in"
    const val FILE_COMPLETED = "file_completed"
    const val FILE_FAILED = "file_failed"
    const val NETWORK_CHANGES = "network_changes"
    const val CORE_STARTS = "core_starts"
    const val CORE_FAILURES = "core_failures"

    // ── Переписка: направления и потери ─────────────────────────────────
    // Владелец 2026-10-07: «мои сообщения до него доходят, а от него ко мне
    // нет». По одним передачам файлов такого не увидеть: нужны числа по
    // сообщениям и, главное, ПРИЧИНЫ, по которым входящее не дошло.

    /** Входящие сообщения переписки: легли в чат и видны человеку. */
    const val MSG_IN = "msg_in"

    /** Исходящие сообщения: человек отправил их с этого телефона. */
    const val MSG_OUT = "msg_out"

    /** Подтверждения доставки наших сообщений — «вторая галочка». */
    const val MSG_ACK = "msg_ack"

    /**
     * Входящие конверты, которые не вскрылись: чужая переписка (мы лишь
     * ретранслятор) либо собеседник запечатал для нашего прежнего ключа.
     */
    const val MSG_IN_NOT_OPENED = "msg_in_not_opened"

    /** Пакеты с отправителем не-узлом: обрывок служебной строки, отброшен. */
    const val MSG_IN_BAD_SENDER = "msg_in_bad_sender"

    /** Собеседник прислал новый ключ шифрования (переустановил приложение). */
    const val MSG_PEER_KEY_CHANGED = "msg_peer_key_changed"

    /** Исходящие, которые не ушли с этого телефона (узел отказал, нет сети). */
    const val MSG_SEND_FAILED = "msg_send_failed"

    /** Исходящие в очереди «до сети»: ещё не отправлены, ждут связи. */
    const val MSG_QUEUED_OFFLINE = "msg_queued_offline"

    // ── Потеряшки: просроченное не живёт вечно ─────────────────────────
    // Владелец 2026-10-07: «У нас нет такого что некоторые потеряшки живут в
    // очереди вечно?» — у незавершённых передач строку никто не удалял.

    /** Строк просроченных передач убрано уборкой. */
    const val TRANSFER_EXPIRED_ROWS = "transfer_expired_rows"

    /** Копий на диске освобождено уборкой (осиротевшие и просроченные). */
    const val TRANSFER_EXPIRED_COPIES = "transfer_expired_copies"
}

/** Код ошибки передачи и число таких записей: объясняет «ошибок N» без имён файлов. */
data class TransferErrorLine(val code: String, val count: Long)

/** Человеческие причины известных кодов ошибок передач (остальное — как есть). */
object TransferErrorText {
    fun explain(code: String): String = when (code) {
        "RESTORED_ELSEWHERE" -> "передача продолжилась на другом устройстве (восстановление профиля)"
        "VERIFY_FAILED" -> "скачанный файл не сошёлся по контрольной сумме"
        "NO_SPACE" -> "на телефоне не хватило места"
        "DECLINED" -> "получатель отказался от файла"
        "SUPERSEDED" -> "передачу заменила более новая"
        else -> code
    }
}

/**
 * Приватность отчёта и строк системного журнала. Живёт в чистом Kotlin
 * нарочно: правила покрыты JVM-тестами на runner (см. DiagnosticsReportTest),
 * поэтому регрессию видно до выпуска. Так была поймана ошибка первого отчёта
 * владельца (2026-10-06): правило IPv6 «две группы через двоеточие» съедало
 * время в строке logcat, и вместо `10-06 13:58:27.154` в отчёте стояло
 * `10-06 [ipv6].154`.
 */
object DiagnosticsPrivacy {
    private val contactRegex = Regex("pk_[0-9a-f]{32,64}")
    private val transferHexRegex = Regex("(?i)\\b[0-9a-f]{32}\\b")
    private val fromHexRegex = Regex("(?i)(\\bfrom\\s+)[0-9a-f]{8}\\b")
    private val ipv4Regex = Regex("(?<![0-9A-Fa-f])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?")

    /**
     * IPv6 бывает сжатым («fe80::1», «2001:db8:12::7») или полным (8 групп).
     * Двух двоеточий для правила мало: иначе под него попадает время суток.
     */
    // Границы «не внутри слова»: иначе шаблон слова «p2p_core::engine» видит
    // в «e::e» IPv6-адрес и портит строки лога.
    private val ipv6CompressedRegex = Regex(
        "(?i)(?<![0-9A-Za-z_:])(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?::" +
            "(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?(?![0-9A-Za-z_])",
    )
    private val ipv6FullRegex = Regex(
        "(?i)(?<![0-9A-Za-z_:])(?:[0-9a-f]{1,4}:){5,7}[0-9a-f]{1,4}(?![0-9A-Za-z_])",
    )
    private val payloadQuotedRegex = Regex("\\bpayload=\"[^\"]*\"")
    // Без якоря конца строки: закрытые payload уже вырезаны шаблоном выше,
    // поэтому здесь остаётся только оборванное тело до конца строки.
    private val payloadOpenRegex = Regex("\\bpayload=\"[^\"]*")
    private val payloadBareRegex = Regex("\\bpayload=[^\"\\s]\\S*")
    private val shapeNumbersRegex = Regex("[0-9]+")
    private val shapeQuotesRegex = Regex("\"[^\"]*\"")

    /** Не копировать в отчёт долговременные contact ID и сетевые адреса. */
    fun redact(text: String): String = text
        .replace(contactRegex, "[contact]")
        .replace(transferHexRegex, "[transfer]")
        .replace(fromHexRegex) { match -> "${match.groupValues[1]}[contact]" }
        .replace(ipv4Regex, "[ip]")
        .replace(ipv6CompressedRegex, "[ipv6]")
        .replace(ipv6FullRegex, "[ipv6]")

    /**
     * Тело MQTT-сообщения — это шифротекст. В отчёте остаются топик и длина
     * (`payload_len`), сама нагрузка прячется: обещание «шифротекста в отчёте
     * нет» должно быть правдой.
     */
    fun hidePayloadBodies(line: String): String = line
        .replace(payloadQuotedRegex, "payload=[скрыто]")
        .replace(payloadOpenRegex, "payload=[скрыто]")
        .replace(payloadBareRegex, "payload=[скрыто]")

    /**
     * Одинаковые строки журнала («MQTT FANOUT QUEUED…», «MQTT IN…») не должны
     * вытеснять полезные: одной формы оставляем не больше [maxPerShape] самых
     * свежих строк, а вместо пропущенных пишем одну строку-счётчик.
     */
    fun collapseRepeatedLogLines(
        lines: List<String>,
        limit: Int,
        maxPerShape: Int = 5,
    ): List<String> {
        if (lines.size <= limit) return lines
        val seen = HashMap<String, Int>()
        val keep = BooleanArray(lines.size)
        val hidden = LinkedHashMap<String, Int>()
        for (i in lines.indices.reversed()) {
            val shape = shapeOf(lines[i])
            val n = (seen[shape] ?: 0) + 1
            seen[shape] = n
            if (n <= maxPerShape) keep[i] = true else hidden[shape] = (hidden[shape] ?: 0) + 1
        }
        val notes = hidden.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { (shape, count) -> "… ещё ${pluralLines(count)}: $shape" }
        val kept = lines.filterIndexed { i, _ -> keep[i] }
        return kept.takeLast((limit - notes.size).coerceAtLeast(1)) + notes
    }

    /**
     * Форма строки для строки-счётчика: числа заменены на `#`, кавычки — на
     * `"…"`, а служебная шапка logcat («10-06 14:56:17.230 20100 20205 I
     * p2p_core: p2p_core::network::mqtt_transport: ») убрана. Без этого
     * счётчик выглядел как `#-# #:#:#.# # # I p#p_core: …`.
     */
    private fun shapeOf(line: String): String {
        var rest = threadtimePrefixRegex.replace(line, "")
        var guard = 0
        while (guard++ < 4) {
            val stripped = tagPrefixRegex.replace(rest, "")
            if (stripped == rest) break
            rest = stripped
        }
        return shapeNumbersRegex.replace(shapeQuotesRegex.replace(rest, "\"…\""), "#").take(96)
    }

    /** «10-06 14:56:17.230 20100 20205 I » — шапка строки logcat. */
    private val threadtimePrefixRegex = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+[A-Z]\\s+")

    /** «p2p_core: » и «p2p_core::network::mqtt_transport: » — служебные теги. */
    private val tagPrefixRegex = Regex("^(?:[A-Za-z0-9_]{2,}(?:::[A-Za-z0-9_]+)*:\\s+)")

    /** «ещё 1 строка», «ещё 31 строка», «ещё 68 строк». */
    private fun pluralLines(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "строка"
            mod10 in 2..4 && mod100 !in 12..14 -> "строки"
            else -> "строк"
        }
        return "$count $word"
    }
}

/** Сборка текста отчёта и строк сводки. Ничего не читает сама — только форматирует. */
object DiagnosticsReport {
    /** Версия формата отчёта: по ней видно, что файл разобран теми же правилами. */
    const val SCHEMA = "apu-diag/2"

    private const val MAX_JOURNAL_LINES = 240

    /** Ошибка брокера свежая — её показываем в сводке, даже если связь уже есть. */
    private const val FRESH_ERROR_SEC = 300

    /** Текст, который владелец копирует и присылает разработчику. */
    fun render(
        facts: DiagnosticsFacts,
        journal: List<DiagnosticsJournalEntry>,
        logcat: List<String>,
    ): String = buildString {
        appendLine("APU · Логи программы ($SCHEMA)")
        appendLine("Создан: ${stamp(facts.createdAtMs)}")
        appendLine(
            "Сессия программы: с ${clock(facts.sessionStartedAtMs)} " +
                "(${formatDuration((facts.createdAtMs - facts.sessionStartedAtMs).coerceAtLeast(0L))})",
        )
        appendLine(
            "Приватность: в отчёт не попадают текст переписки, имена файлов, ключи, " +
                "шифротекст, contact ID и адреса.",
        )
        appendLine()

        appendSummary(facts)
        appendEnvironment(facts)
        appendNetwork(facts)
        appendCore(facts)
        appendMqtt(facts)
        appendMessages(facts)
        appendTransfers(facts)
        appendSession(facts)
        appendJournal(journal, facts)
        appendLogcat(logcat)
    }

    /** Строки «что происходит сейчас» для окна «Логи» и для раздела [сводка]. */
    fun summaryLines(facts: DiagnosticsFacts): List<DiagnosticsLine> {
        val lines = mutableListOf<DiagnosticsLine>()

        // ── Сеть ────────────────────────────────────────────────────────────
        val network = when {
            !facts.networkAvailable -> DiagnosticsLine(
                DiagnosticsLevel.BAD,
                "Сеть",
                "нет подключения — передача и сообщения ждут связи",
            )
            !facts.networkInternet -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Сеть",
                "${facts.networkTransport} без выхода в интернет",
            )
            else -> DiagnosticsLine(
                DiagnosticsLevel.OK,
                "Сеть",
                "${facts.networkTransport}, интернет есть" +
                    if (facts.networkMetered) ", тарифицируется" else "",
            )
        }
        lines += network

        // ── Брокер сообщений ────────────────────────────────────────────────
        val mqtt = MqttLinkText.parse(facts.mqttLine)
        lines += when {
            mqtt == null -> DiagnosticsLine(
                DiagnosticsLevel.INFO,
                "Брокер",
                "ядро ещё не выбирало путь связи",
            )
            mqtt.connected && mqtt.errorAgoSec != null && mqtt.errorAgoSec <= FRESH_ERROR_SEC ->
                DiagnosticsLine(
                    DiagnosticsLevel.WARN,
                    "Брокер",
                    "на связи, подтверждена ${MqttLinkText.humanAgo(mqtt.connAckAgoSec ?: 0)} · ${mqtt.path}; " +
                        "${MqttLinkText.humanAgo(mqtt.errorAgoSec)} была ошибка: " +
                        MqttLinkText.humanError(mqtt.errorText),
                )
            mqtt.connected -> DiagnosticsLine(
                DiagnosticsLevel.OK,
                "Брокер",
                "на связи, подтверждена ${MqttLinkText.humanAgo(mqtt.connAckAgoSec ?: 0)} · ${mqtt.path}",
            )
            mqtt.connAckAgoSec != null -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Брокер",
                "был перебой ${MqttLinkText.humanAgo(mqtt.errorAgoSec ?: 0)} — подключаемся сами · ${mqtt.path}",
            )
            mqtt.errorAgoSec != null -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Брокер",
                "нет ответа ${MqttLinkText.humanAgo(mqtt.errorAgoSec)} " +
                    "(${MqttLinkText.humanError(mqtt.errorText)}) · ${mqtt.path}",
            )
            else -> DiagnosticsLine(DiagnosticsLevel.INFO, "Брокер", "подключаемся… · ${mqtt.path}")
        }

        // ── Ядро ────────────────────────────────────────────────────────────
        lines += if (facts.coreRunning) {
            DiagnosticsLine(
                DiagnosticsLevel.OK,
                "Ядро",
                "работает, пиров ${facts.connectedPeers}, очередь событий ${facts.pendingEvents}",
            )
        } else {
            DiagnosticsLine(
                DiagnosticsLevel.BAD,
                "Ядро",
                "не запущено (${facts.coreStage.ifBlank { "нет этапа" }})",
            )
        }

        // ── Переписка ───────────────────────────────────────────────────────
        lines += messageLine(facts)

        // ── Передачи файлов ─────────────────────────────────────────────────
        val active = transferSums(facts.transferStates)
        val sessionFailed = facts.counters[Counters.FILE_FAILED] ?: 0L
        val transfersText = buildString {
            append("в работе ${active.working}")
            if (active.seeding > 0L) append(", раздаётся ${active.seeding}")
            append(", ждут получателя ${active.waiting}, завершено ${active.completed}")
            append(", ошибок ${active.failed} за всё время")
            if (sessionFailed > 0L) append(" (в этой сессии $sessionFailed)")
        }
        lines += DiagnosticsLine(
            level = if (active.failed > 0L) DiagnosticsLevel.WARN else DiagnosticsLevel.INFO,
            title = "Передачи",
            value = transfersText,
        )

        // ── F4: прямой канал ────────────────────────────────────────────────
        val negotiated = facts.counters[Counters.F4_NEGOTIATED] ?: 0L
        val sentRanges = facts.counters[Counters.F4_RANGES_SENT] ?: 0L
        val receivedRanges = facts.counters[Counters.F4_RANGES_RECEIVED] ?: 0L
        lines += if (negotiated == 0L && sentRanges == 0L && receivedRanges == 0L) {
            DiagnosticsLine(
                DiagnosticsLevel.INFO,
                "Прямой канал",
                "ещё не согласовывался — запустите передачу файла",
            )
        } else {
            DiagnosticsLine(
                DiagnosticsLevel.OK,
                "Прямой канал",
                "сессий $negotiated, отдано $sentRanges диапазонов " +
                    "(${formatBytes(facts.counters[Counters.F4_BYTES_SENT] ?: 0L)}), " +
                    "принято $receivedRanges " +
                    "(${formatBytes(facts.counters[Counters.F4_BYTES_RECEIVED] ?: 0L)})",
            )
        }

        // ── Батарея и фон ───────────────────────────────────────────────────
        val battery = buildString {
            append("${facts.batteryPercent}%")
            if (facts.batteryCharging) append(", на зарядке")
            if (facts.powerSave) append(", включена экономия")
            if (!facts.batteryExempt) append(", оптимизация батареи не снята")
            if (facts.batteryExempt) append(", оптимизация батареи снята")
        }
        lines += DiagnosticsLine(
            level = if (facts.batteryExempt) DiagnosticsLevel.INFO else DiagnosticsLevel.WARN,
            title = "Батарея",
            value = battery,
        )

        return lines
    }

    /**
     * Сообщения по направлениям. Жалоба «мои доходят, а от него нет» должна
     * читаться в сводке одной строкой, а не выискиваться в журнале. Поэтому у
     * строки не один благополучный вид, а все различимые исходы: всё идёт,
     * входящих нет вовсе, часть не вскрылась, сменился ключ, отправка ждёт
     * сети или не удалась.
     */
    private fun messageLine(facts: DiagnosticsFacts): DiagnosticsLine {
        val c = facts.counters
        val saved = c[Counters.MSG_IN] ?: 0L
        val sent = c[Counters.MSG_OUT] ?: 0L
        val acked = c[Counters.MSG_ACK] ?: 0L
        val notOpened = c[Counters.MSG_IN_NOT_OPENED] ?: 0L
        val queued = c[Counters.MSG_QUEUED_OFFLINE] ?: 0L
        val failed = c[Counters.MSG_SEND_FAILED] ?: 0L
        // Оговорки к основной строке: «отложено» и «не ушло» нельзя прятать —
        // иначе «отправлено 0» читается как поломка, хотя сообщение просто ждёт
        // сети, а сбои, наоборот, выглядят благополучно.
        val notes = buildString {
            if (queued > 0L) append(" · отложено до сети $queued")
            if (failed > 0L) append(" · не ушло $failed")
            // Своя переписка ждёт получателя в очереди ядра: это не отказ и не
            // потеря, но человек должен видеть, что сообщение ещё не дошло.
            val waiting = facts.relayQueueOwn ?: 0L
            if (waiting > 0L) append(" · своё ждёт получателя $waiting")
            // Служебное (сигналы и пакеты передачи) — не переписка: без этой
            // оговорки «своё ждёт получателя 1000» пугает зря.
            val waitingSignals = facts.relayQueueOwnSignals ?: 0L
            if (waitingSignals > 0L) append(" (из них сигналов и пакетов $waitingSignals)")
        }
        return when {
            // Смена ключа перевешивает остальные исходы: пока не отсканируют
            // QR заново, переписка не восстановится сама, и любой другой текст
            // в этой строке увёл бы человека не туда.
            facts.peerKeyChangedAtMs > 0L -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Сообщения",
                "у собеседника новый ключ шифрования (переустановка приложения) — " +
                    "пока не отсканируете его QR-код заново, переписка не вскроется" +
                    ageSuffix(facts, facts.peerKeyChangedAtMs, "замечено"),
            )
            // Самая дорогая для владельца картина: свои сообщения уходят и
            // подтверждаются, а входящих нет ни одного.
            saved == 0L && (sent > 0L || acked > 0L) -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Сообщения",
                ("входящих нет, а отправлено $sent и подтверждено $acked" +
                    if (notOpened > 0L) " · не вскрылось $notOpened" else
                        " · смотрите раздел «сообщения»") + notes,
            )
            notOpened > 0L -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Сообщения",
                ("принято $saved, отправлено $sent, подтверждено $acked · " +
                    "не вскрылось $notOpened (чужая переписка или устаревший ключ)") + notes,
            )
            saved == 0L && sent == 0L && queued > 0L -> DiagnosticsLine(
                DiagnosticsLevel.INFO,
                "Сообщения",
                "переписки ещё не было · отправлено ничего · отложено до сети $queued " +
                    "(уйдут сами, когда появится связь)",
            )
            saved == 0L && sent == 0L -> DiagnosticsLine(
                DiagnosticsLevel.INFO,
                "Сообщения",
                "переписки в этой сессии ещё не было",
            )
            else -> DiagnosticsLine(
                DiagnosticsLevel.OK,
                "Сообщения",
                "принято $saved, отправлено $sent, подтверждено $acked" +
                    ageSuffix(facts, facts.lastIncomingAtMs, "последнее входящее") + notes,
            )
        }
    }

    /** « · последнее входящее 2 мин назад»; пусто, если события не было. */
    private fun ageSuffix(facts: DiagnosticsFacts, atMs: Long, label: String): String {
        if (atMs <= 0L) return ""
        val sec = ((facts.createdAtMs - atMs).coerceAtLeast(0L) / 1000L).toInt()
        return " · $label ${MqttLinkText.humanAgo(sec)}"
    }

    // ------------------------------------------------------------------
    // Разделы отчёта
    // ------------------------------------------------------------------

    private fun StringBuilder.appendSummary(facts: DiagnosticsFacts) {
        appendLine("[сводка]")
        summaryLines(facts).forEach { line ->
            appendLine("${marker(line.level)} ${line.title}: ${line.value}")
        }
        appendLine()
    }

    private fun StringBuilder.appendEnvironment(facts: DiagnosticsFacts) {
        appendLine("[окружение]")
        appendLine("app=${facts.appVersion}")
        appendLine("android=${facts.androidRelease} (SDK ${facts.androidSdk})")
        appendLine("device=${facts.device}")
        appendLine("abi=${facts.abis}")
        appendLine("locale=${facts.locale} · tz=${TimeZone.getDefault().id}")
        appendLine("memory=${facts.memoryUsedMb}/${facts.memoryLimitMb} МиБ (куча приложения: занято/лимит)")
        appendLine("storage_free=${formatBytes(facts.storageFreeMb * 1024L * 1024L)}")
        appendLine(
            "session_uptime=${formatDuration((facts.createdAtMs - facts.sessionStartedAtMs).coerceAtLeast(0L))}",
        )
        appendLine()
    }

    private fun StringBuilder.appendNetwork(facts: DiagnosticsFacts) {
        appendLine("[сеть]")
        appendLine("available=${facts.networkAvailable}")
        appendLine("transport=${facts.networkTransport}")
        appendLine("internet=${facts.networkInternet}")
        appendLine("metered=${facts.networkMetered}")
        appendLine("vpn=${facts.networkVpn}")
        appendLine("notifications=${if (facts.notificationsAllowed) "разрешены" else "запрещены"}")
        appendLine("proxy_tunnel=${if (facts.proxyTunnel) "включён" else "выключен"}")
        appendLine("swarm=${facts.swarmMode}")
        appendLine("im_the_server=${facts.serverMode}")
        appendLine("power_save=${facts.powerSave} · doze=${facts.deviceIdle} · battery_exempt=${facts.batteryExempt}")
        appendLine("battery=${facts.batteryPercent}% (${if (facts.batteryCharging) "зарядка" else "разряд"})")
        appendLine()
    }

    private fun StringBuilder.appendCore(facts: DiagnosticsFacts) {
        appendLine("[ядро]")
        appendLine("running=${facts.coreRunning}")
        appendLine("build=${facts.coreBuild}")
        // Ядро рапортует стадию «Поднимаем ядро и сеть…» и одновременно
        // ready=true — в отчёте это выглядело противоречием. Когда ядро готово,
        // так и пишем, иначе показываем стадию и что она ещё идёт.
        if (facts.coreReady) {
            appendLine("stage=готово (ядро поднято)")
        } else {
            appendLine("stage=${facts.coreStage.ifBlank { "нет данных" }} → ещё поднимается")
        }
        appendLine("ready=${facts.coreReady}")
        appendLine("network=${networkText(facts.networkStatus)}")
        appendLine("connected_peers=${facts.connectedPeers}")
        appendLine("pending_events=${facts.pendingEvents}")
        appendLine("relay_custody=${facts.relayCustody} · quarantine=${facts.relayQuarantine}")
        appendLine("core_starts=${facts.coreRestarts}")
        appendLine()
    }

    private fun StringBuilder.appendMqtt(facts: DiagnosticsFacts) {
        appendLine("[mqtt]")
        val parsed = MqttLinkText.parse(facts.mqttLine)
        if (parsed == null) {
            appendLine("raw=${facts.mqttLine.ifBlank { "нет данных: ядро ещё не выбирало брокера" }}")
        } else {
            appendLine("path=${parsed.path}")
            appendLine("connected=${parsed.connected}")
            appendLine("conn_ack_ago=${parsed.connAckAgoSec?.let { "$it с" } ?: "не было"}")
            appendLine("last_error_ago=${parsed.errorAgoSec?.let { "$it с" } ?: "нет"}")
            if (parsed.errorText.isNotBlank()) {
                appendLine("last_error=${MqttLinkText.humanError(parsed.errorText)} (${parsed.errorText.take(120)})")
            }
            appendLine("raw=${facts.mqttLine}")
        }
        appendLine()
    }

    /**
     * Переписка глазами отчёта: сколько сообщений дошло до человека, сколько
     * ушло и где теряется встречное направление. Ни текста, ни имён, ни
     * идентификаторов — только числа, время и причины отбрасывания.
     */
    private fun StringBuilder.appendMessages(facts: DiagnosticsFacts) {
        appendLine("[сообщения]")
        val c = facts.counters
        val notOpened = c[Counters.MSG_IN_NOT_OPENED] ?: 0L
        appendLine("входящих показано=${c[Counters.MSG_IN] ?: 0L}")
        appendLine("исходящих отправлено=${c[Counters.MSG_OUT] ?: 0L}")
        appendLine("подтверждений доставки=${c[Counters.MSG_ACK] ?: 0L}")
        appendLine("отложено до сети (уйдёт само)=${c[Counters.MSG_QUEUED_OFFLINE] ?: 0L}")
        appendLine("не ушло (узел отказал или нет сети)=${c[Counters.MSG_SEND_FAILED] ?: 0L}")
        appendLine("последнее входящее=${lastSeenText(facts, facts.lastIncomingAtMs)}")
        appendLine("последнее исходящее=${lastSeenText(facts, facts.lastOutgoingAtMs)}")
        appendLine("последнее подтверждение=${lastSeenText(facts, facts.lastAckAtMs)}")
        appendLine("не вскрылось (чужая переписка или устаревший ключ)=$notOpened")
        appendLine(
            "отправитель не узел (обрывок служебной строки)=" +
                "${c[Counters.MSG_IN_BAD_SENDER] ?: 0L}",
        )
        appendLine(
            "ядро передало сигналов=${facts.coreMessageSignals} " +
                "(по журналу процесса; включает и служебные)",
        )
        appendLine("пересылка: отброшено из-за полной очереди=${facts.relayQueueFull}")
        appendLine(
            "очередь ядра: своё ждёт получателя=" +
                (facts.relayQueueOwn?.toString() ?: "нет (пусто)") +
                (facts.relayQueueOwnSignals?.let { " (из них сигналов и пакетов=$it)" } ?: ""),
        )
        appendLine(
            "очередь ядра: чужая пересылка=" +
                (facts.relayQueueForeign?.toString() ?: "нет (пусто)"),
        )
        appendLine(
            "смена ключа у собеседника (раз)=" + (c[Counters.MSG_PEER_KEY_CHANGED] ?: 0L),
        )
        appendLine(
            "последняя смена ключа=" +
                if (facts.peerKeyChangedAtMs > 0L) {
                    lastSeenText(facts, facts.peerKeyChangedAtMs)
                } else {
                    "не было"
                },
        )
        if (facts.peerKeyChangedAtMs > 0L) {
            appendLine(
                "  ↳ собеседник прислал новый ключ, а закреплён прежний — так и выглядит " +
                    "«от него не приходят»: отсканируйте его QR-код заново",
            )
        }
        if (notOpened > 0L) {
            appendLine(
                "  ↳ входящие не вскрываются: собеседник запечатал не нашим ключом — " +
                    "у него устаревшая копия ключа (переустановка, восстановление профиля)",
            )
        }
        if (facts.relayQueueFull > 0L) {
            appendLine(
                "  ↳ пересылка: это пакеты для ДРУГИХ узлов — получатель давно не в сети, " +
                    "чужой лимит на получателя исчерпан",
            )
        }
        val ownWaiting = facts.relayQueueOwn ?: 0L
        if (ownWaiting > 0L) {
            appendLine(
                "  ↳ своё ждёт получателя: он не в сети; своя переписка идёт первой и не " +
                    "делит лимит с чужой пересылкой",
            )
        }
        val ownWaitingSignals = facts.relayQueueOwnSignals ?: 0L
        if (ownWaitingSignals > 0L) {
            appendLine(
                "  ↳ сигналы и пакеты передачи — служебное: живут минуты, места переписки " +
                    "не занимают с v11.74.198",
            )
        }
        appendLine()
    }

    /** «08:31:06 (2 мин назад)» либо «в этой сессии не было». */
    private fun lastSeenText(facts: DiagnosticsFacts, atMs: Long): String {
        if (atMs <= 0L) return "в этой сессии не было"
        val sec = ((facts.createdAtMs - atMs).coerceAtLeast(0L) / 1000L).toInt()
        return "${clock(atMs)} (${MqttLinkText.humanAgo(sec)})"
    }

    private fun StringBuilder.appendTransfers(facts: DiagnosticsFacts) {
        appendLine("[передачи]")
        val sums = transferSums(facts.transferStates)
        appendLine("в работе=${sums.working}")
        appendLine("раздаётся=${sums.seeding}")
        appendLine("ждут получателя=${sums.waiting}")
        appendLine("у хранителя=${sums.custodied}")
        appendLine("на моём хранении=${sums.custody}")
        appendLine("завершено=${sums.completed}")
        appendLine("ошибок=${sums.failed} (за всё время работы приложения)")
        appendLine("отменено=${sums.cancelled}")
        facts.failureCodes.forEach { failure ->
            appendLine("ошибка.${failure.code}=${failure.count} — ${TransferErrorText.explain(failure.code)}")
        }
        facts.lastFailureAtMs?.let { at ->
            appendLine("last_failure_at=${stamp(at)}")
            appendLine("last_failure_ago=${formatDuration((facts.createdAtMs - at).coerceAtLeast(0L))}")
        }
        appendLine("held_custody_bytes=${facts.custodyBytes}")
        // Владелец 2026-10-07: тяжёлое хранится сутки, текст и малое — неделю.
        // Правило печатаем рядом с числами: иначе сроки приходится угадывать.
        appendLine(FileTransferRetention.describe())
        val expiredRows = facts.counters[Counters.TRANSFER_EXPIRED_ROWS] ?: 0L
        val expiredCopies = facts.counters[Counters.TRANSFER_EXPIRED_COPIES] ?: 0L
        appendLine(
            "убрано просроченного=${expiredRows} " +
                "(копий на диске освобождено=${expiredCopies})",
        )
        facts.transferStates.entries
            .sortedBy { it.key }
            .forEach { (state, count) -> appendLine("state.$state=$count") }
        appendLine()
    }

    /** Счётчики сессии человеческими строками; незнакомые ключи — как есть. */
    private fun StringBuilder.appendSession(facts: DiagnosticsFacts) {
        appendLine("[сессия]")
        val c = facts.counters
        appendLine("передач начато (отправка)=${c[Counters.FILE_STARTED_OUT] ?: 0L}")
        appendLine("приёмов начато=${c[Counters.FILE_STARTED_IN] ?: 0L}")
        appendLine("передач завершено=${c[Counters.FILE_COMPLETED] ?: 0L}")
        appendLine("ошибок передачи=${c[Counters.FILE_FAILED] ?: 0L}")
        appendLine("F4-сессий согласовано=${c[Counters.F4_NEGOTIATED] ?: 0L}")
        appendLine("F4-диапазонов отправлено=${c[Counters.F4_RANGES_SENT] ?: 0L}")
        appendLine("F4-диапазонов принято=${c[Counters.F4_RANGES_RECEIVED] ?: 0L}")
        appendLine("F4-байт отправлено=${c[Counters.F4_BYTES_SENT] ?: 0L}")
        appendLine("F4-байт принято=${c[Counters.F4_BYTES_RECEIVED] ?: 0L}")
        appendLine("F4-отказов диапазона=${c[Counters.F4_RANGE_FAILURES] ?: 0L}")
        appendLine("F4-по UDP без бинарного канала=${c[Counters.F4_UDP_FALLBACK] ?: 0L}")
        appendLine("событий сети (появилась/пропала)=${c[Counters.NETWORK_CHANGES] ?: 0L}")
        appendLine("запусков ядра=${c[Counters.CORE_STARTS] ?: 0L}")
        appendLine("отказов старта ядра=${c[Counters.CORE_FAILURES] ?: 0L}")
        val known = setOf(
            Counters.FILE_STARTED_OUT, Counters.FILE_STARTED_IN, Counters.FILE_COMPLETED,
            Counters.FILE_FAILED, Counters.F4_NEGOTIATED, Counters.F4_RANGES_SENT,
            Counters.F4_RANGES_RECEIVED, Counters.F4_BYTES_SENT, Counters.F4_BYTES_RECEIVED,
            Counters.F4_RANGE_FAILURES, Counters.F4_UDP_FALLBACK, Counters.NETWORK_CHANGES,
            Counters.CORE_STARTS,
            Counters.CORE_FAILURES,
            Counters.MSG_IN,
            Counters.MSG_OUT,
            Counters.MSG_ACK,
            Counters.MSG_IN_NOT_OPENED,
            Counters.MSG_IN_BAD_SENDER,
            Counters.MSG_PEER_KEY_CHANGED,
            Counters.MSG_SEND_FAILED,
            Counters.MSG_QUEUED_OFFLINE,
            Counters.TRANSFER_EXPIRED_ROWS,
            Counters.TRANSFER_EXPIRED_COPIES,
        )
        c.entries.filter { it.key !in known }.sortedBy { it.key }
            .forEach { (key, value) -> appendLine("$key=$value") }
        appendLine()
    }

    private fun StringBuilder.appendJournal(
        journal: List<DiagnosticsJournalEntry>,
        facts: DiagnosticsFacts,
    ) {
        appendLine("[журнал] (последние события этой сессии)")
        val shown = journal.takeLast(MAX_JOURNAL_LINES)
        if (shown.isEmpty()) {
            appendLine("Событий пока нет: запустите передачу файла и откройте «Логи» снова.")
        } else {
            if (shown.size < journal.size) {
                appendLine("… раньше в сессии было ещё ${journal.size - shown.size} записей")
            }
            shown.forEach { entry ->
                appendLine(
                    "${clock(entry.atMs)} ${marker(entry.level)} ${entry.area}: ${entry.detail.take(200)}",
                )
            }
        }
        val warns = journal.count { it.level == DiagnosticsLevel.WARN }
        val bads = journal.count { it.level == DiagnosticsLevel.BAD }
        appendLine("итого: записей ${journal.size}, предупреждений $warns, ошибок $bads")
        appendLine("сессия началась ${clock(facts.sessionStartedAtMs)}")
        appendLine()
    }

    private fun StringBuilder.appendLogcat(logcat: List<String>) {
        appendLine("[лог процесса]")
        if (logcat.isEmpty()) {
            appendLine("Строк системного журнала нет — журнал событий выше по-прежнему годен.")
        } else {
            logcat.forEach { appendLine(it.take(220)) }
        }
    }

    // ------------------------------------------------------------------
    // Служебное форматирование
    // ------------------------------------------------------------------

    private fun marker(level: DiagnosticsLevel): String = when (level) {
        DiagnosticsLevel.OK -> "✔"
        DiagnosticsLevel.INFO -> "•"
        DiagnosticsLevel.WARN -> "!"
        DiagnosticsLevel.BAD -> "✖"
    }

    /** Состояние сети ядра по-русски; незнакомое значение остаётся как есть. */
    private fun networkText(status: String): String = when (status) {
        "connected" -> "на связи"
        "connecting" -> "подключается"
        "disconnected" -> "нет связи"
        else -> status
    }

    /** «2026-10-06 12:41:58 GMT+03:00». */
    fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(ms))

    /** «12:41:58» — для строк журнала. */
    fun clock(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    /** «2 мин 48 с», «45 с», «1 ч 05 мин», «3 дн 4 ч». */
    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val days = totalSec / 86_400L
        val hours = (totalSec % 86_400L) / 3_600L
        val minutes = (totalSec % 3_600L) / 60L
        val seconds = totalSec % 60L
        return when {
            days > 0L -> "$days дн $hours ч"
            hours > 0L -> "$hours ч %02d мин".format(Locale.US, minutes)
            minutes > 0L -> "$minutes мин $seconds с"
            else -> "$seconds с"
        }
    }

    /** «8.4 МиБ», «512 КиБ», «1.20 ГиБ», «0 Б». */
    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe Б"
            safe < 1024L * 1024L -> "%.1f КиБ".format(Locale.US, safe / 1024.0)
            safe < 1024L * 1024L * 1024L -> "%.1f МиБ".format(Locale.US, safe / (1024.0 * 1024.0))
            else -> "%.2f ГиБ".format(Locale.US, safe / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /** Суммы по состояниям передач: работа/ожидание/хранение/готово/ошибки. */
    fun transferSums(states: Map<String, Long>): TransferSums {
        fun sumOf(vararg names: String): Long =
            names.sumOf { states[it] ?: 0L }
        return TransferSums(
            working = sumOf("PREPARED", "TRANSFERRING", "OFFERED", "SENT"),
            // «SEEDING» — файл уже отправлен автором и раздаётся рою: это не
            // «в работе» (иначе отчёт владельца говорил «в работе 1, завершено 0»).
            seeding = sumOf("SEEDING"),
            waiting = sumOf("WAITING_RECIPIENT"),
            custodied = sumOf("CUSTODIED"),
            custody = sumOf("HOLDING", "FORWARDING"),
            completed = sumOf("COMPLETE"),
            failed = sumOf("FAILED"),
            cancelled = sumOf("CANCELLED"),
        )
    }
}

/** Суммы состояний передач для сводки и раздела [передачи]. */
data class TransferSums(
    val working: Long,
    /** Файл отправлен и раздаётся соседям (SEEDING). */
    val seeding: Long,
    val waiting: Long,
    val custodied: Long,
    val custody: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
)
