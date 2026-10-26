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
// шифротекст, contact ID, UUID сообщений и сетевые адреса (см. TransferDiagnostics.redact).
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
    "^\\[(?:сводка|окружение|сеть|обновления|ядро|mqtt|помощники|сообщения|передачи|сессия|журнал|лог процесса)\\]",
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

/** Privacy-safe summary of release checks and update-notification delivery. */
data class UpdateDiagnosticFacts(
    val currentVersion: String = "",
    val latestVersion: String = "",
    val lastCheckAtMs: Long = 0L,
    val checkDurationMs: Long? = null,
    val trigger: String = "never",
    val source: String = "none",
    val outcome: String = "never_checked",
    /**
     * Что сделала ручная кнопка с внешним каналом:
     * `opened` - открыла (иначе строка обновления могла не появиться вовсе),
     * `already_allowed` - канал и так был открыт, `not_manual` - авто-проверка,
     * `not_attempted` - ручная, но до внешнего запроса дело не дошло.
     */
    val manualExternalWindow: String = "unknown",
    val httpStatus: Int? = null,
    val githubOutcome: String = "not_attempted",
    val githubHttpStatus: Int? = null,
    val relayOutcome: String = "not_attempted",
    val relayHttpStatus: Int? = null,
    val notificationVersion: String = "",
    val notificationKind: String = "never",
    val notificationAtMs: Long = 0L,
    val notificationResult: String = "never",
    val notificationsAllowed: Boolean? = null,
    val notificationChannelEnabled: Boolean? = null,
    val notificationChannelImportance: Int? = null,
) {
    companion object {
        val EMPTY = UpdateDiagnosticFacts()
    }
}

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
    /** Сколько получателей занято в последней сводке очереди ядра. */
    val relayQueueRecipients: Long? = null,
    /**
     * Предел своего резерва из той же сводки. «Своих=1000» при пределе 1000 —
     * это «очередь стоит на пределе», а не случайное круглое число
     * (владелец 2026-10-15: по одному числу нельзя было отличить застрявшую
     * переписку от свежего потока).
     */
    val relayQueueOwnLimit: Long? = null,
    /** Возраст старейшей своей записи, секунды: хвост суток и хвост минут — разные диагнозы. */
    val relayQueueOwnOldestSecs: Long? = null,
    /** Вытеснения своей переписки из RAM-кэша: накопительный счётчик либо выборка старых логов. */
    val relayQueueOwnEvictions: Long = 0L,
    val relayQueueOwnEvictionsAreCumulative: Boolean = false,
    /** Полные циклы STUN, в которых ни один сервер не ответил. */
    val stunFailedCycles: Long = 0L,
    /** Успешные ответы STUN в выборке системного журнала. */
    val stunSuccessfulBindings: Long = 0L,
    /** Результат последнего цикла STUN в выборке; null — цикла не было. */
    val stunLastAttemptFailed: Boolean? = null,
    /** Предупреждения W из последних строк logcat процесса приложения. */
    val logcatWarnings: Long = 0L,
    /** Ошибки E/F из последних строк logcat процесса приложения. */
    val logcatErrors: Long = 0L,
    /** Локальный opt-in принимать online-helper transit; это не разрешение отправителю. */
    val onlineHelperParticipationEnabled: Boolean? = null,
    /** Безопасные события маршрута сообщений из ограниченной выборки Rust logcat. */
    val helperRoutes: HelperRouteDiagnostics = HelperRouteDiagnostics.EMPTY,
    /** Состояние последней проверки/уведомления о версии; без URL и текста релиза. */
    val updateDiagnostics: UpdateDiagnosticFacts = UpdateDiagnosticFacts.EMPTY,
    val relayStorage: RelayStorageDiagnostics = RelayStorageDiagnostics(),
    val runtimeHealth: RuntimeHealthSnapshot = RuntimeHealthSnapshot(),
    val operationalHealth: OperationalSnapshot = OperationalSnapshot(),
    val operationalDetails: String = "",
    val networkValidated: Boolean? = null,
    val networkCaptivePortal: Boolean? = null,
    val privateDnsActive: Boolean? = null,
    val dataSaverStatus: Int? = null,
    val backgroundRestricted: Boolean? = null,
    val linkDownKbps: Int? = null,
    val linkUpKbps: Int? = null,
    /** Только безопасные статусы: без ID, открытых/секретных ключей и имён файлов. */
    val signingSeedMode: String = "unknown",
    val signingModeReason: KeyModeReason = KeyModeReason.NOT_PROBED,
    val exchangeModeReason: KeyModeReason = KeyModeReason.NOT_PROBED,
    val installedSigningMode: String = "unknown",
    val fileExchangeMode: String = "unknown",
    /** Отличают timeout при старте от позднего READY в глобальном реестре. */
    val signingInstallBeforeCore: String = "unknown",
    val fileExchangeInstallBeforeCore: String = "unknown",
    /** Только сравнение в памяти; ни один routing ID в отчёт не попадает. */
    val signingMatchesCoreRouting: Boolean? = null,
    /** Подтверждённые подписанным APUX1 прямые QUIC-соседи, НЕ connected_peers. */
    val liveDirectSwarmPeers: Int? = null,
    /** Решение Rust policy в момент снимка, не свидетельство фактического HTTP-запроса. */
    val externalTransportAllowed: Boolean? = null,
)

/** Агрегаты обычных сообщений; file chunks intentionally stay on file-transfer diagnostics. */
data class HelperRouteDiagnostics(
    val attempts: Long = 0L,
    /** Сумма выбранных helper slots (не число уникальных устройств). */
    val selectedCandidates: Long = 0L,
    val attemptsWithoutCandidates: Long = 0L,
    val routeUnavailable: Long = 0L,
    val routeUnavailableRuntime: Long = 0L,
    val routeUnavailableLocalIdentity: Long = 0L,
    val routeUnavailableRecipientIdentity: Long = 0L,
    val packetPreparationRejected: Long = 0L,
    val attemptsNotPrepared: Long = 0L,
    val primaryLegsStarted: Long = 0L,
    val reserveLegsStarted: Long = 0L,
    val legWritesSucceeded: Long = 0L,
    val legWritesFailed: Long = 0L,
    val reserveAfterPrimaryFailure: Long = 0L,
    val reserveAfterHedgeDelay: Long = 0L,
    val signedDeclinesReceived: Long = 0L,
    val recipientReceiptsIssued: Long = 0L,
    val senderReceiptsVerified: Long = 0L,
    val receiptReturnedByPrimary: Long = 0L,
    val receiptReturnedByReserve: Long = 0L,
    val receiptReturnedByUnknownLeg: Long = 0L,
    val attemptsWithoutReceipt: Long = 0L,
    val averageVerifiedReceiptMs: Long? = null,
) {
    companion object {
        val EMPTY = HelperRouteDiagnostics()
    }
}

/** Парсер безопасных машинных маркеров Rust logcat; сами строки не хранятся в фактах. */
object HelperRouteDiagnosticsParser {
    private val eventPattern = Regex("""\bONLINE_HELPER event=([a-z_]+)\b""")

    fun parse(lines: List<String>): HelperRouteDiagnostics {
        val events = lines.mapNotNull { line ->
            val name = eventPattern.find(line)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            name to line
        }
        fun count(name: String): Long = events.count { it.first == name }.toLong()
        fun count(name: String, key: String, value: String): Long =
            events.count { it.first == name && field(it.second, key) == value }.toLong()
        val verifiedReceiptTimes = events.asSequence()
            .filter { (name, line) -> name == "attempt_result" && field(line, "outcome") == "receipt_verified" }
            .mapNotNull { (_, line) -> field(line, "elapsed_ms")?.toLongOrNull() }
            .toList()
        return HelperRouteDiagnostics(
            attempts = count("attempt_started"),
            selectedCandidates = events.asSequence()
                .filter { it.first == "candidates" }
                .sumOf { (_, line) -> field(line, "selected")?.toLongOrNull() ?: 0L },
            attemptsWithoutCandidates = count("no_candidates"),
            routeUnavailable = count("route_unavailable"),
            routeUnavailableRuntime = count("route_unavailable", "reason", "runtime"),
            routeUnavailableLocalIdentity = count("route_unavailable", "reason", "identity"),
            routeUnavailableRecipientIdentity = count("route_unavailable", "reason", "recipient_identity"),
            packetPreparationRejected = count("relay_packet_rejected"),
            attemptsNotPrepared = count("attempt_result", "outcome", "not_prepared"),
            primaryLegsStarted = count("leg_started", "slot", "1"),
            reserveLegsStarted = count("leg_started", "slot", "2"),
            legWritesSucceeded = count("leg_write", "result", "success"),
            legWritesFailed = count("leg_write", "result", "failed"),
            reserveAfterPrimaryFailure = count("reserve_started", "trigger", "primary_failed"),
            reserveAfterHedgeDelay = count("reserve_started", "trigger", "hedge_delay"),
            signedDeclinesReceived = count("signed_decline_received"),
            recipientReceiptsIssued = count("recipient_receipt_issued"),
            senderReceiptsVerified = count("receipt_verified"),
            receiptReturnedByPrimary = count("receipt_verified", "return_leg", "1"),
            receiptReturnedByReserve = count("receipt_verified", "return_leg", "2"),
            receiptReturnedByUnknownLeg = count("receipt_verified", "return_leg", "unknown"),
            attemptsWithoutReceipt = count("attempt_result", "outcome", "no_receipt") +
                count("attempt_result", "outcome", "local_wait_timeout"),
            averageVerifiedReceiptMs = verifiedReceiptTimes.takeIf { it.isNotEmpty() }
                ?.let { values -> values.sum() / values.size },
        )
    }

    private fun field(line: String, key: String): String? =
        Regex("""(?:^|\s)${Regex.escape(key)}=([A-Za-z0-9_-]+)(?:\s|$)""")
            .find(line)?.groupValues?.getOrNull(1)
}

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
    /** Попытки отправить фрагмент через Rust при отсутствии прямого канала, НЕ факт enqueue. */
    const val FILE_BROKER_FALLBACK_ATTEMPTS = "file_broker_fallback_attempts"
    /** APK не имеет broker fallback для содержимого: отсутствие прямого пути. */
    const val APK_DIRECT_UNREACHABLE = "apk_direct_unreachable"
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

    /** ACK correlation: the local outgoing row was eligible for DELIVERED. */
    const val MSG_ACK_TARGET_MATCHED = "msg_ack_target_matched"
    /** ACK correlation: no local row with this transport message id. */
    const val MSG_ACK_TARGET_UNKNOWN = "msg_ack_target_unknown"
    /** ACK correlation: a row exists, but it is not owned by this phone. */
    const val MSG_ACK_TARGET_NOT_MINE = "msg_ack_target_not_mine"
    /** ACK correlation: a local row exists but is already terminal/unexpected. */
    const val MSG_ACK_TARGET_TERMINAL = "msg_ack_target_terminal"

    /** Попытки открыть sealed-пакеты: не уникальные сообщения и не доказанная потеря. */
    const val MSG_IN_NOT_OPENED = "msg_in_not_opened"

    /** Из них получены, пока подписывающая/файловая identity не была готова при старте. */
    const val MSG_IN_LOCAL_CRYPTO_UNAVAILABLE = "msg_in_local_crypto_unavailable"

    /** Конверты рядом с успешным открытием от того же узла (возможно копии в пути). */
    const val MSG_IN_NOT_OPENED_STALE = "msg_in_not_opened_stale"

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

/** Счётчики уровней Android logcat для ограниченной выборки процесса. */
data class DiagnosticsLogcatLevels(val warnings: Long, val errors: Long)

/** Накопительный счётчик вытеснений своей переписки из RAM либо legacy sample. */
data class RelayQueueEvictionDiagnostics(val count: Long, val cumulative: Boolean)

/**
 * Приватность отчёта и строк системного журнала. Живёт в чистом Kotlin
 * нарочно: правила покрыты JVM-тестами на runner (см. DiagnosticsReportTest),
 * поэтому регрессию видно до выпуска. Так была поймана ошибка первого отчёта
 * владельца (2026-10-06): правило IPv6 «две группы через двоеточие» съедало
 * время в строке logcat, и вместо `10-06 13:58:27.154` в отчёте стояло
 * `10-06 [ipv6].154`.
 */
object DiagnosticsPrivacy {
    private val shortIdentityFieldRegex = Regex("(?i)(\\b(?:sender|peer|node|recipient|from|to|msg|msgId)=)[0-9a-f]{8,31}\\b")
    private val mqttTopicRegex = Regex("\\btopic=[^\\s]+")
    private val processThreadPrefixRegex = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+)\\d+\\s+\\d+\\s+")
    private val contactRegex = Regex("pk_[0-9a-f]{32,64}")
    private val uuidRegex = Regex("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b")
    // MQTT receipt topics carry SHA-256(messageId), a stable 64-hex correlator.
    // Hide it as an identifier too; redacting only UUID and 32-hex transfer IDs
    // left these message-derived keys visible in the privacy report.
    private val messageHashRegex = Regex("(?i)\\b[0-9a-f]{64}\\b")
    private val transferHexRegex = Regex("(?i)\\b[0-9a-f]{32}\\b")
    private val threadtimeSeverityRegex = Regex(
        "^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+([A-Z])\\s",
    )
    private val fromHexRegex = Regex("(?i)(\\bfrom\\s+)[0-9a-f]{8}\\b")
    private val ipv4Regex = Regex("(?<![0-9A-Fa-f])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?")
    private val privatePathRegex = Regex(
        "(?i)(?<![A-Za-z0-9])/(?:data|storage|sdcard|mnt|tmp|cache|home|users|private|documents|download)(?:/[^\\s,;:'\"<>]+)+",
    )
    private val privatePathFieldRegex = Regex(
        "(?i)\\b(file(?:name)?|path|local_uri|uri)=(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
    )

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

    /** Не копировать в отчёт идентификаторы контактов/сообщений и сетевые адреса. */
    fun redact(text: String): String = text
        .replace(processThreadPrefixRegex) { "${it.groupValues[1]}0 0 " }
        // Whole UUID first: short-hex field masking must not leave its dashed tail.
        .replace(uuidRegex, "[id]")
        .replace(shortIdentityFieldRegex) { "${it.groupValues[1]}[id]" }
        .replace(mqttTopicRegex, "topic=[route]")
        .replace(contactRegex, "[contact]")
        .replace(messageHashRegex, "[id]")
        .replace(transferHexRegex, "[transfer]")
        .replace(fromHexRegex) { match -> "${match.groupValues[1]}[contact]" }
        .replace(ipv4Regex, "[ip]")
        .replace(ipv6CompressedRegex, "[ipv6]")
        .replace(ipv6FullRegex, "[ipv6]")
        .replace(privatePathFieldRegex) { match -> "${match.groupValues[1]}=[скрыто]" }
        .replace(privatePathRegex, "[path]")

    /** Уровень строки Android logcat threadtime (`V/D/I/W/E/F`), если есть. */
    fun logcatSeverity(line: String): String? =
        threadtimeSeverityRegex.find(line)?.groupValues?.get(1)

    /** Подсчитать Android logcat W и E/F в уже ограниченной выборке процесса. */
    fun countLogcatSeverities(lines: List<String>): DiagnosticsLogcatLevels {
        var warnings = 0L
        var errors = 0L
        lines.forEach { line ->
            when (logcatSeverity(line)) {
                "W" -> warnings++
                "E", "F" -> errors++
            }
        }
        return DiagnosticsLogcatLevels(warnings, errors)
    }

    /**
     * Берёт самый свежий queue counter из текущего запуска ядра. Startup summary
     * сбрасывает старый cumulative value после перезапуска; periodic summary
     * обновляет его. Older builds fall back to the bounded legacy warning sample.
     */
    fun parseRelayQueueOwnEvictions(lines: List<String>): RelayQueueEvictionDiagnostics {
        for (line in lines.asReversed()) {
            if (line.contains("Relay-очередь: своих=")) {
                numericValueAfter(line, "вытеснено-своих=")?.let {
                    return RelayQueueEvictionDiagnostics(it, cumulative = true)
                }
            }
            numericValueAfter(line, "own_capacity_evictions=")?.let {
                return RelayQueueEvictionDiagnostics(it, cumulative = true)
            }
        }
        val legacyWarnings = lines.count { it.contains("вытеснено самое старое своё") }.toLong()
        return RelayQueueEvictionDiagnostics(legacyWarnings, cumulative = false)
    }

    private fun numericValueAfter(line: String, marker: String): Long? {
        val start = line.indexOf(marker)
        if (start < 0) return null
        val digits = line.substring(start + marker.length).takeWhile { it.isDigit() }
        return digits.takeIf { it.isNotEmpty() }?.toLongOrNull()
    }

    /**
     * Оставить место для настоящих E/F строк: большой поток повторных W не должен
     * вытеснять ошибки из итогового отчёта. Ошибки идут последними, чтобы их было
     * видно и в конце длинного отчёта.
     */
    fun prioritizeLogcatErrors(
        lines: List<String>,
        limit: Int,
        maxErrorLines: Int = 20,
    ): List<String> {
        val boundedLimit = limit.coerceAtLeast(1)
        val (errors, ordinary) = lines.partition { line ->
            val severity = logcatSeverity(line)
            severity == "E" || severity == "F"
        }
        val errorBudget = minOf(maxErrorLines.coerceAtLeast(1), maxOf(1, boundedLimit / 3))
        val keptErrors = if (errors.size <= errorBudget) {
            errors
        } else {
            val collapsed = collapseRepeatedLogLines(errors, errorBudget)
            val actualErrors = collapsed.filter { logcatSeverity(it) == "E" || logcatSeverity(it) == "F" }
            (actualErrors.ifEmpty { errors }).takeLast(errorBudget)
        }.takeLast(errorBudget)
        val ordinaryBudget = boundedLimit - keptErrors.size
        val keptOrdinary = if (ordinaryBudget > 0) {
            collapseRepeatedLogLines(ordinary, ordinaryBudget)
        } else {
            emptyList()
        }
        return (keptOrdinary + keptErrors).takeLast(boundedLimit)
    }

    /**
     * Обрезать очень длинную строку, сохранив и начало, и хвост с итоговыми
     * счётчиками. Прежний `take(220)` скрывал конец MQTT heartbeat — как раз
     * там находятся poll errors, таймауты и backpressure.
     */
    fun clipLogcatLine(line: String, maxChars: Int = 420): String {
        val limit = maxChars.coerceAtLeast(0)
        if (line.length <= limit) return line
        val marker = " …[середина строки скрыта]… "
        val available = limit - marker.length
        if (available <= 0) return line.take(limit)
        val prefix = available / 2
        val suffix = available - prefix
        return line.take(prefix) + marker + line.takeLast(suffix)
    }

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
    const val SCHEMA = "apu-diag/5"

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
                "шифротекст, contact ID, UUID сообщений и сетевые адреса.",
        )
        appendLine()

        appendSummary(facts)
        appendEnvironment(facts)
        appendNetwork(facts)
        appendUpdateDiagnostics(facts)
        appendCore(facts)
        appendMqtt(facts)
        appendHelperRoutes(facts)
        appendMessages(facts)
        append(facts.operationalHealth.render())
        append(facts.operationalDetails)
        appendLine()
        append(facts.runtimeHealth.render())
        appendLine()
        append(facts.relayStorage.render())
        appendLine()
        appendTransfers(facts)
        appendSession(facts)
        appendJournal(journal, facts)
        appendLogcat(facts, logcat)
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

        if (facts.installedSigningMode != "unknown" || facts.fileExchangeMode != "unknown") {
            // A later read-only Keystore check cannot repair a missing identity
            // captured at engine init: display OK only after both bounded
            // installs and the running core's routing ID were verified.
            val ready = facts.coreRunning &&
                facts.installedSigningMode == "legacy+ed25519-sidecar-v1" &&
                facts.fileExchangeMode == "ready" &&
                facts.signingInstallBeforeCore == "ready" &&
                facts.fileExchangeInstallBeforeCore == "ready" &&
                facts.signingMatchesCoreRouting == true
            lines += DiagnosticsLine(
                if (ready) DiagnosticsLevel.OK else DiagnosticsLevel.WARN,
                "Локальные ключи",
                "подпись ${safeDiagnosticToken(facts.installedSigningMode)}, " +
                    "обмен ${safeDiagnosticToken(facts.fileExchangeMode)}; " +
                    "seed Keystore ${safeDiagnosticToken(facts.signingSeedMode)}; " +
                    "до ядра подпись/обмен ${safeDiagnosticToken(facts.signingInstallBeforeCore)}/" +
                    "${safeDiagnosticToken(facts.fileExchangeInstallBeforeCore)}; " +
                    "совпадение с routing ID ${facts.signingMatchesCoreRouting?.toString() ?: "unknown"}",
            )
        }
        if (facts.liveDirectSwarmPeers != null) {
            lines += DiagnosticsLine(
                if (facts.liveDirectSwarmPeers > 0) DiagnosticsLevel.OK else DiagnosticsLevel.INFO,
                "Прямой рой",
                "подтверждённых соседей ${facts.liveDirectSwarmPeers} (connected_peers=${facts.connectedPeers} не гарантирует прямой канал); " +
                    "внешний транспорт сейчас ${when (facts.externalTransportAllowed) {
                        true -> "разрешён"
                        false -> "не разрешён"
                        null -> "неизвестно"
                    }}",
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
            when {
                facts.stunLastAttemptFailed == true -> DiagnosticsLine(
                    DiagnosticsLevel.INFO,
                    "Прямой канал",
                    "последний STUN в выборке не ответил; безуспешных циклов ${facts.stunFailedCycles}; " +
                        "${if (facts.networkVpn) "VPN может блокировать UDP" else "сеть может блокировать UDP"}" +
                        (if (active.seeding > 0L) {
                            " · файл раздаётся и ждёт запроса получателя"
                        } else {
                            " · прямой путь пока не подтверждён"
                        }),
                )
                active.seeding > 0L -> DiagnosticsLine(
                    DiagnosticsLevel.INFO,
                    "Прямой канал",
                    "файл раздаётся; канал согласуется, когда получатель запросит данные",
                )
                else -> DiagnosticsLine(
                    DiagnosticsLevel.INFO,
                    "Прямой канал",
                    "не проверялся в этой сессии — согласуется при запросе передачи",
                )
            }
        } else {
            DiagnosticsLine(
                DiagnosticsLevel.OK,
                "Прямой канал",
                "исходящих сессий согласовано $negotiated, отдано $sentRanges диапазонов " +
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
        val localOpenFailures = c[Counters.MSG_IN_LOCAL_CRYPTO_UNAVAILABLE] ?: 0L
        val queued = c[Counters.MSG_QUEUED_OFFLINE] ?: 0L
        val failed = c[Counters.MSG_SEND_FAILED] ?: 0L
        val ackUnknown = c[Counters.MSG_ACK_TARGET_UNKNOWN] ?: 0L
        val ackNotMine = c[Counters.MSG_ACK_TARGET_NOT_MINE] ?: 0L
        val ackTerminal = c[Counters.MSG_ACK_TARGET_TERMINAL] ?: 0L
        val ownEvictions = facts.relayQueueOwnEvictions
        // Оговорки к основной строке: «отложено» и «не ушло» нельзя прятать —
        // иначе «отправлено 0» читается как поломка, хотя сообщение просто ждёт
        // сети. Исторические logcat-события в подсказках явно помечены как выборка,
        // чтобы не выдавать их за текущий отказ.
        val queueNotes = buildString {
            // Своя переписка ждёт получателя в последней сводке очереди; это не
            // отказ и не потеря, но человек должен видеть, что сообщение ещё не дошло.
            val waiting = facts.relayQueueOwn ?: 0L
            if (waiting > 0L) append(" · своё ждёт получателя $waiting (по последней сводке)")
            // Служебное (сигналы и пакеты передачи) — не переписка.
            val waitingSignals = facts.relayQueueOwnSignals ?: 0L
            if (waitingSignals > 0L) append(" (из них сигналов и пакетов $waitingSignals)")
            // Владелец 2026-10-15: одно число не отвечает, СТОИТ очередь или
            // течёт. Рядом с числом — предел своего резерва и возраст самой
            // старой своей записи (если ядро их уже печатает).
            if (waiting > 0L) {
                facts.relayQueueOwnLimit?.let { append(" · предел-своих $it") }
                facts.relayQueueOwnOldestSecs?.let {
                    append(" · старейшее-своё ${formatDuration(it * 1000L)}")
                }
            }
            if (ownEvictions > 0L) {
                val evictionsSource = if (facts.relayQueueOwnEvictionsAreCumulative) {
                    "с запуска ядра"
                } else {
                    "по доступным строкам logcat"
                }
                append(" · вытеснений своего из ограниченного RAM-кэша: $ownEvictions ($evictionsSource); " +
                    "само по себе не доказывает потерю; отдельная durable/outbox-копия остаётся, если запись прошла")
            }
            if (facts.relayQueueFull > 0L) {
                append(" · в выборке logcat было отказов чужой пересылки из-за лимита: ${facts.relayQueueFull}")
            }
        }
        val notes = buildString {
            if (queued > 0L) append(" · отложено до сети $queued (возможно, ждёт и ключей; это попытки)")
            if (failed > 0L) append(" · не ушло $failed")
            if (ackUnknown > 0L) append(" · ACK без локальной строки $ackUnknown")
            if (ackNotMine > 0L) append(" · ACK по чужой строке $ackNotMine")
            if (ackTerminal > 0L) append(" · ACK по уже завершённой строке $ackTerminal")
            append(queueNotes)
        }
        return when {
            localOpenFailures > 0L -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Сообщения",
                "не вскрылось $notOpened попыток (из них $localOpenFailures при неготовых " +
                    "локальных ключах); это не доказывает смену ключа собеседника" + notes,
            )
            // Only a verified HELLO with a different pinned binding proves a peer-key mismatch.
            facts.peerKeyChangedAtMs > 0L -> DiagnosticsLine(
                DiagnosticsLevel.WARN,
                "Сообщения",
                "подписанный ключ собеседника не совпал с закреплённым; " +
                    "проверьте его личность по другому каналу перед обновлением QR" +
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
                    "не вскрылось $notOpened попыток (адресат и причина не подтверждены)") + notes,
            )
            saved == 0L && sent == 0L && queued > 0L -> DiagnosticsLine(
                DiagnosticsLevel.INFO,
                "Сообщения",
                "переписки ещё не было · отправлено ничего · отложено до сети/ключей $queued " +
                    "(повторится в фоне при восстановлении условий)" + queueNotes,
            )
            saved == 0L && sent == 0L -> DiagnosticsLine(
                DiagnosticsLevel.INFO,
                "Сообщения",
                "переписки в этой сессии ещё не было" + queueNotes,
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
        appendLine("validated_by_android=${facts.networkValidated ?: "unknown"} · captive_portal=${facts.networkCaptivePortal ?: "unknown"}")
        appendLine("private_dns_active=${facts.privateDnsActive ?: "unknown"} · data_saver_status=${facts.dataSaverStatus ?: -1} (1=off, 2=whitelisted, 3=enabled)")
        appendLine("background_restricted=${facts.backgroundRestricted ?: "unknown"}")
        appendLine("link_down_kbps=${facts.linkDownKbps ?: -1} · link_up_kbps=${facts.linkUpKbps ?: -1} (оценка Android, не замер скорости)")
        appendLine("notifications=${if (facts.notificationsAllowed) "разрешены" else "запрещены"}")
        appendLine("proxy_tunnel=${if (facts.proxyTunnel) "включён" else "выключен"}")
        appendLine("swarm=${facts.swarmMode}")
        appendLine("online_helper_participation=${facts.onlineHelperParticipationEnabled?.toString() ?: "unknown"}")
        appendLine("im_the_server=${facts.serverMode}")
        appendLine("power_save=${facts.powerSave} · doze=${facts.deviceIdle} · battery_exempt=${facts.batteryExempt}")
        appendLine("battery=${facts.batteryPercent}% (${if (facts.batteryCharging) "зарядка" else "разряд"})")
        appendLine()
    }

    private fun StringBuilder.appendUpdateDiagnostics(facts: DiagnosticsFacts) {
        val update = facts.updateDiagnostics
        appendLine("[обновления]")
        appendLine("current_version=${safeDiagnosticToken(update.currentVersion.ifBlank { facts.appVersion.substringBefore(" (") })}")
        appendLine("latest_version=${safeDiagnosticToken(update.latestVersion).ifBlank { "unknown" }}")
        appendLine("last_check_trigger=${safeDiagnosticToken(update.trigger).ifBlank { "unknown" }}")
        appendLine("last_check_source=${safeDiagnosticToken(update.source).ifBlank { "unknown" }}")
        appendLine("last_check_result=${safeDiagnosticToken(update.outcome).ifBlank { "unknown" }}")
        appendLine("last_check_manual_external_window=${safeDiagnosticToken(update.manualExternalWindow).ifBlank { "unknown" }}")
        appendLine("last_check_http_status=${update.httpStatus?.takeIf { it in 100..599 }?.toString() ?: "unknown"}")
        appendLine("github_check_result=${safeDiagnosticToken(update.githubOutcome).ifBlank { "unknown" }}")
        appendLine("github_http_status=${update.githubHttpStatus?.takeIf { it in 100..599 }?.toString() ?: "unknown"}")
        appendLine("relay_check_result=${safeDiagnosticToken(update.relayOutcome).ifBlank { "unknown" }}")
        appendLine("relay_http_status=${update.relayHttpStatus?.takeIf { it in 100..599 }?.toString() ?: "unknown"}")
        if (update.lastCheckAtMs > 0L) {
            appendLine("last_check_at=${stamp(update.lastCheckAtMs)}")
            val ageSec = ((facts.createdAtMs - update.lastCheckAtMs).coerceAtLeast(0L) / 1000L).toInt()
            appendLine("last_check_ago=${MqttLinkText.humanAgo(ageSec)}")
        } else {
            appendLine("last_check_at=нет")
        }
        appendLine("last_check_duration_ms=${update.checkDurationMs?.coerceAtLeast(0L)?.toString() ?: "unknown"}")
        appendLine("last_notification_attempt_version=${safeDiagnosticToken(update.notificationVersion).ifBlank { "none" }}")
        appendLine("last_notification_attempt_kind=${safeDiagnosticToken(update.notificationKind).ifBlank { "unknown" }}")
        appendLine("last_notification_attempt_result=${safeDiagnosticToken(update.notificationResult).ifBlank { "unknown" }}")
        if (update.notificationAtMs > 0L) {
            appendLine("last_notification_attempt_at=${stamp(update.notificationAtMs)}")
        } else {
            appendLine("last_notification_attempt_at=нет")
        }
        appendLine("notifications_allowed=${update.notificationsAllowed?.toString() ?: "unknown"}")
        appendLine("update_channel_enabled=${update.notificationChannelEnabled?.toString() ?: "unknown"}")
        appendLine("update_channel_importance=${update.notificationChannelImportance?.toString() ?: "unknown"}")
        appendLine()
    }

    /** Report tokens are deliberately narrower than arbitrary strings/URLs. */
    private fun safeDiagnosticToken(value: String): String =
        value.filter { it.isLetterOrDigit() || it in ".+-_" }.take(64)

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
        appendLine("connected_peers=${facts.connectedPeers} (не число подтверждённых прямых соседей)")
        appendLine("signed_direct_swarm_peers=${facts.liveDirectSwarmPeers?.toString() ?: "unknown"} (живые подтверждённые APUX1)")
        appendLine("external_transport_allowed_now=${facts.externalTransportAllowed?.toString() ?: "unknown"} (политика на момент снимка, не факт обращения)")
        appendLine("signing_mode_reason=${facts.signingModeReason.name.lowercase()} · exchange_mode_reason=${facts.exchangeModeReason.name.lowercase()}")
        appendLine("signing_seed_keystore=${safeDiagnosticToken(facts.signingSeedMode)}")
        appendLine("installed_signing_identity=${safeDiagnosticToken(facts.installedSigningMode)}")
        appendLine("signing_install_before_core=${safeDiagnosticToken(facts.signingInstallBeforeCore)}")
        appendLine("signing_routing_matches_core=${facts.signingMatchesCoreRouting?.toString() ?: "unknown"} (без идентификаторов)")
        appendLine("file_exchange_keystore=${safeDiagnosticToken(facts.fileExchangeMode)}")
        appendLine("file_exchange_install_before_core=${safeDiagnosticToken(facts.fileExchangeInstallBeforeCore)}")
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

    /** Безопасная сводка helper-route: только тип события, номер локального слота и задержки. */
    private fun StringBuilder.appendHelperRoutes(facts: DiagnosticsFacts) {
        val helper = facts.helperRoutes
        appendLine("[помощники]")
        appendLine("scope=обычные сообщения после неудачи прямой отправки; file chunks не включены")
        appendLine("source=ограниченная выборка последних строк logcat процесса, не полный журнал")
        appendLine("local_participation_enabled=${facts.onlineHelperParticipationEnabled?.toString() ?: "unknown"}")
        appendLine("attempts_after_direct_failure=${helper.attempts}")
        appendLine("selected_helper_slots_total=${helper.selectedCandidates}")
        appendLine("attempts_without_candidates=${helper.attemptsWithoutCandidates}")
        appendLine("route_unavailable=${helper.routeUnavailable}")
        appendLine("route_unavailable_runtime=${helper.routeUnavailableRuntime}")
        appendLine("route_unavailable_local_identity=${helper.routeUnavailableLocalIdentity}")
        appendLine("route_unavailable_recipient_identity=${helper.routeUnavailableRecipientIdentity}")
        appendLine("relay_packet_rejected=${helper.packetPreparationRejected}")
        appendLine("attempts_not_prepared=${helper.attemptsNotPrepared}")
        appendLine("primary_legs_started=${helper.primaryLegsStarted}")
        appendLine("reserve_legs_started=${helper.reserveLegsStarted}")
        appendLine("helper_stream_writes_succeeded=${helper.legWritesSucceeded} (не подтверждение доставки)")
        appendLine("helper_stream_writes_failed=${helper.legWritesFailed}")
        appendLine("reserve_after_primary_failure=${helper.reserveAfterPrimaryFailure}")
        appendLine("reserve_after_hedge_delay=${helper.reserveAfterHedgeDelay}")
        appendLine("signed_declines_received=${helper.signedDeclinesReceived}")
        appendLine("recipient_signed_receipts_issued=${helper.recipientReceiptsIssued}")
        appendLine("recipient_signed_receipts_verified=${helper.senderReceiptsVerified}")
        appendLine("verified_receipt_return_leg_1=${helper.receiptReturnedByPrimary}")
        appendLine("verified_receipt_return_leg_2=${helper.receiptReturnedByReserve}")
        appendLine("verified_receipt_return_leg_unknown=${helper.receiptReturnedByUnknownLeg}")
        appendLine("attempts_without_receipt=${helper.attemptsWithoutReceipt}")
        appendLine("verified_receipt_average_elapsed_ms=${helper.averageVerifiedReceiptMs?.toString() ?: "нет данных"}")
        appendLine("Квитанция подтверждает принятие адресатом, но не личность/уникальность помощника и не полный путь пакета.")
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
        appendLine("ACK нашли ожидающую исходящую строку=${c[Counters.MSG_ACK_TARGET_MATCHED] ?: 0L}")
        appendLine("ACK без локальной строки=${c[Counters.MSG_ACK_TARGET_UNKNOWN] ?: 0L}")
        appendLine("ACK по строке не с этого телефона=${c[Counters.MSG_ACK_TARGET_NOT_MINE] ?: 0L}")
        appendLine("ACK по уже завершённой/неподходящей строке=${c[Counters.MSG_ACK_TARGET_TERMINAL] ?: 0L}")
        appendLine("отложено (ожидает связь/ключи; число попыток)=${c[Counters.MSG_QUEUED_OFFLINE] ?: 0L}")
        appendLine("не ушло (узел отказал или нет сети)=${c[Counters.MSG_SEND_FAILED] ?: 0L}")
        appendLine("последнее входящее=${lastSeenText(facts, facts.lastIncomingAtMs)}")
        appendLine("последнее исходящее=${lastSeenText(facts, facts.lastOutgoingAtMs)}")
        appendLine("последнее подтверждение=${lastSeenText(facts, facts.lastAckAtMs)}")
        appendLine("не вскрылось (попытки; адресат/причина не подтверждены)=$notOpened")
        appendLine("из них при неготовой локальной identity при старте=${c[Counters.MSG_IN_LOCAL_CRYPTO_UNAVAILABLE] ?: 0L}")
        appendLine(
            "не вскрылось рядом с успешным открытием (возможны копии в пути)=" +
                "${c[Counters.MSG_IN_NOT_OPENED_STALE] ?: 0L}",
        )
        appendLine(
            "отправитель не узел (обрывок служебной строки)=" +
                "${c[Counters.MSG_IN_BAD_SENDER] ?: 0L}",
        )
        appendLine(
            "ядро передало сигналов=${facts.coreMessageSignals} " +
                "(по журналу процесса; включает и служебные)",
        )
        appendLine("пересылка: отброшено из-за полной очереди=${facts.relayQueueFull} (в ограниченной выборке logcat)")
        appendLine(
            "очередь ядра: своё ждёт получателя=" +
                (facts.relayQueueOwn?.toString() ?: "нет (пусто)") +
                (facts.relayQueueOwnSignals?.let { " (из них сигналов и пакетов=$it)" } ?: "") +
                (facts.relayQueueOwnLimit?.let { " · предел-своих=$it" } ?: "") +
                // Возраст печатаем как везде в отчёте («1 дн 0 ч»), а не сырыми
                // секундами: «86400с» владельцу ничего не говорит.
                (facts.relayQueueOwnOldestSecs?.let {
                    " · старейшее-своё=${formatDuration(it * 1000L)}"
                } ?: "") +
                (if (facts.relayQueueOwn != null) " (последняя сводка logcat)" else ""),
        )
        appendLine(
            "очередь ядра: чужая пересылка=" +
                (facts.relayQueueForeign?.toString() ?: "нет (пусто)") +
                (if (facts.relayQueueForeign != null) " (последняя сводка logcat)" else ""),
        )
        appendLine(
            "очередь ядра: получателей=" +
                (facts.relayQueueRecipients?.toString() ?: "нет данных") +
                (if (facts.relayQueueRecipients != null) " (последняя сводка logcat)" else ""),
        )
        val ownEvictionsSource = if (facts.relayQueueOwnEvictionsAreCumulative) {
            "с запуска ядра"
        } else {
            "по доступным строкам logcat"
        }
        appendLine("вытеснений своих из RAM-кэша=${facts.relayQueueOwnEvictions} ($ownEvictionsSource; durable/outbox отдельно)")
        if (facts.relayQueueOwnEvictions > 0L) {
            appendLine("  ↳ вытеснение из RAM само по себе не доказывает потерю; при успешной durable/outbox-записи копия остаётся для досылки")
        }
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
                "  ↳ подписанный HELLO не совпал с закреплённым ключом; проверьте " +
                    "личность собеседника вне приложения перед обновлением QR",
            )
        }
        if (notOpened > 0L) {
            appendLine(
                "  ↳ это попытки, не число уникальных сообщений: неизвестно, кому " +
                    "адресованы пакеты и почему они не открылись; не сбрасывайте ключи по этому счётчику",
            )
        } else if ((c[Counters.MSG_IN_NOT_OPENED_STALE] ?: 0L) > 0L) {
            appendLine(
                "  ↳ рядом с успешным открытием были нераскрытые конверты; " +
                    "возможны чужой транзит или задержавшиеся копии",
            )
        }
        if (facts.relayQueueFull > 0L) {
            appendLine(
                "  ↳ в выборке logcat это пакеты для ДРУГИХ узлов — на момент записи чужой " +
                    "лимит на получателя был исчерпан; это не текущий статус",
            )
        }
        val ownWaiting = facts.relayQueueOwn ?: 0L
        if (ownWaiting > 0L) {
            appendLine(
                "  ↳ последняя сводка logcat показывала своё в ожидании получателя; " +
                    "своя переписка идёт первой и не делит лимит с чужой пересылкой",
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
        appendLine("file_broker_fallback_attempts=${facts.counters[Counters.FILE_BROKER_FALLBACK_ATTEMPTS] ?: 0L} (сессия; не доказательство постановки в relay)")
        appendLine("apk_direct_unreachable=${facts.counters[Counters.APK_DIRECT_UNREACHABLE] ?: 0L} (сессия; APK-пакет в брокер не отправлялся)")
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
        if ((c[Counters.FILE_STARTED_OUT] ?: 0L) > 0L) {
            appendLine("  ↳ это попытки подготовки, включая самосев APK; при ошибке строка удаляется — не число пользовательских отправок")
        }
        appendLine("приёмов начато=${c[Counters.FILE_STARTED_IN] ?: 0L}")
        appendLine("передач завершено=${c[Counters.FILE_COMPLETED] ?: 0L}")
        appendLine("ошибок передачи=${c[Counters.FILE_FAILED] ?: 0L}")
        appendLine("F4-исходящих сессий согласовано=${c[Counters.F4_NEGOTIATED] ?: 0L}")
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
            Counters.FILE_FAILED, Counters.FILE_BROKER_FALLBACK_ATTEMPTS,
            Counters.APK_DIRECT_UNREACHABLE, Counters.F4_NEGOTIATED, Counters.F4_RANGES_SENT,
            Counters.F4_RANGES_RECEIVED, Counters.F4_BYTES_SENT, Counters.F4_BYTES_RECEIVED,
            Counters.F4_RANGE_FAILURES, Counters.F4_UDP_FALLBACK, Counters.NETWORK_CHANGES,
            Counters.CORE_STARTS,
            Counters.CORE_FAILURES,
            Counters.MSG_IN,
            Counters.MSG_OUT,
            Counters.MSG_ACK,
            Counters.MSG_ACK_TARGET_MATCHED,
            Counters.MSG_ACK_TARGET_UNKNOWN,
            Counters.MSG_ACK_TARGET_NOT_MINE,
            Counters.MSG_ACK_TARGET_TERMINAL,
            Counters.MSG_IN_NOT_OPENED,
            Counters.MSG_IN_LOCAL_CRYPTO_UNAVAILABLE,
            Counters.MSG_IN_NOT_OPENED_STALE,
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
        appendLine("итого журнала событий: записей ${journal.size}, предупреждений $warns, ошибок $bads")
        appendLine("сессия началась ${clock(facts.sessionStartedAtMs)}")
        appendLine()
    }

    private fun StringBuilder.appendLogcat(facts: DiagnosticsFacts, logcat: List<String>) {
        appendLine("[лог процесса]")
        appendLine(
            "уровни в ограниченной выборке logcat: предупреждений W=${facts.logcatWarnings}, " +
                "ошибок/фатальных E/F=${facts.logcatErrors}",
        )
        if (facts.stunFailedCycles > 0L || facts.stunSuccessfulBindings > 0L) {
            val lastStun = when (facts.stunLastAttemptFailed) {
                true -> "неудача"
                false -> "ответ получен"
                null -> "неизвестно"
            }
            appendLine(
                "STUN в выборке: ответов=${facts.stunSuccessfulBindings}, " +
                    "циклов без ответа=${facts.stunFailedCycles}, последний результат=$lastStun",
            )
        }
        if (logcat.isEmpty()) {
            appendLine("Строк системного журнала нет — журнал событий выше по-прежнему годен.")
        } else {
            logcat.forEach { appendLine(DiagnosticsPrivacy.clipLogcatLine(it)) }
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
