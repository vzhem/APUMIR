package com.vladimir.messenger.data.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.swarm.ServerMode
import com.vladimir.messenger.data.swarm.SwarmSettings
import com.vladimir.messenger.service.CoreStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale

/**
 * Безопасный журнал событий и отчёт для проверки на телефоне.
 *
 * Зачем: Android не даёт обычному приложению читать весь logcat, а владельцу
 * нужно не «голословно», а по делу показать, что происходит внутри. Поэтому
 * важные решения (ядро, сеть, F4-переходы, передачи файлов) пишутся в
 * ограниченный журнал этого процесса, а по кнопке собирается отчёт: состояние
 * сети/ядра/брокера/очереди передач + журнал + строки системного журнала.
 *
 * Чего в отчёте НЕТ и не будет: текста переписки, имён файлов, ключей,
 * шифротекста, contact ID и адресов — всё прогоняется через [redact], а
 * вызывающие обязаны передавать только служебные факты.
 *
 * Текст отчёта собирает [DiagnosticsReport] (чистый Kotlin, проверяется
 * JVM-тестами на runner), здесь — только сбор фактов с телефона.
 */
object TransferDiagnostics {
    private const val TAG = "ApuDiagnostics"

    /** Сколько записей журнала держим в памяти: хватает на несколько передач. */
    private const val MAX_EVENTS = 400

    /** Сколько строк системного журнала добавляем в отчёт. */
    private const val MAX_LOGCAT_LINES = 120

    /** Сколько строк журнала процесса читать (последние, самые свежие). */
    private const val LOGCAT_LINE_LIMIT = 20_000

    /** Сколько ждать чтение журнала процесса, прежде чем собрать отчёт без него. */
    private const val LOGCAT_BUDGET_MS = 2_500L

    /**
     * Строка вместо раздела журнала процесса, пока он читается: в быстрой
     * части отчёта честнее сказать «читается», чем «строк нет».
     */
    private const val LOGCAT_PENDING_LINE =
        "журнал процесса читается — раздел обновится сам (секунда-две)"

    /** Сколько записей журнала показывать списком в окне «Логи» (свежие сверху). */
    private const val EVENT_ROWS = 40

    /** Этапы сбора — их словами показывает окно «Логи». */
    const val STAGE_DEVICE = "читаю состояние телефона…"
    const val STAGE_LOGCAT = "читаю журнал процесса…"
    const val STAGE_REPORT = "собираю текст отчёта…"

    private val lock = Any()
    private val journal = ArrayDeque<DiagnosticsJournalEntry>(MAX_EVENTS)

    /** Счётчики сессии: растут от событий, никогда не спамят журнал. */
    private val counters = LinkedHashMap<String, Long>()

    /** Когда процесс приложения запустился: по этой отметке считается сессия. */
    private val sessionStartedAtMs = System.currentTimeMillis()

    // ── Публичные операции ─────────────────────────────────────────────

    /** Обычное событие (успех/ход процесса). Только короткий служебный факт! */
    fun record(area: String, detail: String) = append(DiagnosticsLevel.INFO, area, detail)

    /** Внимание: объясняет поведение, но ещё не поломка. */
    fun recordWarning(area: String, detail: String) = append(DiagnosticsLevel.WARN, area, detail)

    /** Поломка: передача/связь/ядро не работают. */
    fun recordFailure(area: String, detail: String) = append(DiagnosticsLevel.BAD, area, detail)

    /** Успешный итог: удобно видеть в журнале отдельно от «просто событий». */
    fun recordSuccess(area: String, detail: String) = append(DiagnosticsLevel.OK, area, detail)

    /** Увеличить счётчик сессии (в журнал не пишется). */
    fun count(key: String, delta: Long = 1L) {
        if (delta == 0L) return
        synchronized(lock) { counters[key] = (counters[key] ?: 0L) + delta }
    }

    /** Увеличить счётчик и узнать новое значение — для «первое и каждое N-е». */
    private fun bump(key: String, delta: Long = 1L): Long {
        synchronized(lock) {
            val next = (counters[key] ?: 0L) + delta
            counters[key] = next
            return next
        }
    }

    // ── Переписка: направления, подтверждения и потери ──────────────────
    // Отдельно от счётчиков держим «когда это было»: по свежести входящих
    // видно, идут ли сообщения от собеседника, — владельцу не нужно
    // сопоставлять числа со временем в журнале.
    //
    // Владелец 2026-10-07: «мои сообщения до него доходят, а от него ко мне
    // нет». Тихую потерю входящего (конверт не вскрылся) раньше в отчёте не
    // было видно вовсе — теперь это предупреждение в журнале и число в
    // разделе [сообщения].

    @Volatile private var lastIncomingAtMs = 0L
    @Volatile private var lastOutgoingAtMs = 0L
    @Volatile private var lastAckAtMs = 0L

    /** Когда собеседник в последний раз присылал новый ключ (переустановка). */
    @Volatile private var peerKeyChangedAtMs = 0L

    /** Входящее сообщение легло в переписку. */
    fun noteMessageIncoming() {
        lastIncomingAtMs = System.currentTimeMillis()
        val total = bump(Counters.MSG_IN)
        // В журнал — первое и каждое двадцатое: иначе долгая переписка
        // вытеснила бы из журнала всё остальное.
        if (total == 1L || total % 20L == 0L) {
            append(DiagnosticsLevel.OK, "msg", "входящие доходят: в переписку легло $total-е сообщение")
        }
    }

    /** Исходящее сообщение отправлено с этого телефона. */
    fun noteMessageOutgoing() {
        lastOutgoingAtMs = System.currentTimeMillis()
        val total = bump(Counters.MSG_OUT)
        if (total == 1L || total % 20L == 0L) {
            append(DiagnosticsLevel.INFO, "msg", "исходящее отправлено: с этого телефона ушло $total")
        }
    }

    /**
     * Отправка не удалась. Передаём только ВИД ошибки (имя класса исключения),
     * а не её текст: в тексте бывают идентификаторы узла, а отчёт должен
     * оставаться без чужих данных. Вид ошибки достаточно, чтобы отличить «нет
     * сети» от «узел отказал».
     */
    fun noteMessageSendFailed(kind: String) {
        val total = bump(Counters.MSG_SEND_FAILED)
        if (total == 1L || total % 5L == 0L) {
            append(
                DiagnosticsLevel.WARN,
                "msg",
                "исходящее не ушло ($total-е): " + kind.take(40),
            )
        }
    }

    /**
     * Сообщение легло в очередь и уйдёт само, когда появится сеть. Это НЕ
     * отправка: подтверждения доставки на такое сообщение ещё не было и быть
     * не может. Отдельный счётчик нужен, чтобы «отправлено» не путалось с
     * «отложено» — иначе отчёт выглядел бы бодрее, чем переписка.
     */
    fun noteMessageQueuedOffline() {
        val total = bump(Counters.MSG_QUEUED_OFFLINE)
        if (total == 1L || total % 10L == 0L) {
            append(
                DiagnosticsLevel.INFO,
                "msg",
                "исходящее отложено до сети ($total-е): уйдёт само",
            )
        }
    }

    /** Подтверждение доставки: «вторая галочка» на своём сообщении. */
    fun noteDeliveryAck() {
        lastAckAtMs = System.currentTimeMillis()
        bump(Counters.MSG_ACK)
        // В журнал не пишем: подтверждений много, а числа есть в отчёте.
    }

    /**
     * Входящий конверт не вскрылся — это потеря НАСТОЯЩЕГО сообщения, а не
     * служебный шум. Поэтому предупреждение и счётчик, а [senderKeyKnown]
     * отделяет две разные причины: «не нам» и «у собеседника устаревший наш
     * ключ» — советы в этих случаях разные.
     */
    fun noteSealedNotOpened(senderKeyKnown: Boolean, staleKeyCopy: Boolean = false) {
        if (staleKeyCopy) {
            // Копия, отправленная до обмена ключами: ключ уже рабочий, новая
            // переписка открывается. Это НЕ потеря и не повод для тревоги —
            // считаем отдельным числом, чтобы «не вскрылось» не росло из-за
            // хвостов, и в журнал не пишем (владелец 2026-10-07: «плашка
            // появилась опять, хотя переписка работает»).
            bump(Counters.MSG_IN_NOT_OPENED_STALE)
            return
        }
        val total = bump(Counters.MSG_IN_NOT_OPENED)
        if (total == 1L || total % 5L == 0L) {
            append(
                DiagnosticsLevel.WARN,
                "msg",
                if (senderKeyKnown) {
                    "входящее не вскрылось ($total-е): собеседник запечатал для нашего " +
                        "прежнего ключа — у него устаревшая копия"
                } else {
                    "входящее не вскрылось ($total-е): конверт не нам — ключами с " +
                        "отправителем не обменивались"
                },
            )
        }
    }

    /**
     * Собеседник прислал новый ключ обмена, а у нас закреплён старый (он
     * переустановил приложение или восстановил профиль). Переписка с ним
     * ломается в обе стороны — и именно так выглядит жалоба «от него ко мне не
     * приходят», хотя внешне ничего не сломано. Пишем предупреждением один раз
     * и каждое пятое: подсказка «отсканируйте QR заново» должна быть на виду.
     */
    fun notePeerKeyChanged() {
        peerKeyChangedAtMs = System.currentTimeMillis()
        val total = bump(Counters.MSG_PEER_KEY_CHANGED)
        if (total == 1L || total % 5L == 0L) {
            append(
                DiagnosticsLevel.WARN,
                "msg",
                "у собеседника сменился ключ шифрования (переустановка приложения?) — " +
                    "сообщения не вскрываются, пока не отсканируете его QR-код заново",
            )
        }
    }

    /** Пакет без узла-отправителя: обрывок служебной строки, в переписку нельзя. */
    fun noteBadSender() {
        val total = bump(Counters.MSG_IN_BAD_SENDER)
        if (total == 1L || total % 5L == 0L) {
            append(DiagnosticsLevel.WARN, "msg", "пакет без узла-отправителя отброшен ($total-й)")
        }
    }

    /**
     * Ход длинного процесса: счётчик растёт всегда, а в журнал попадает лишь
     * каждое [everyN]-е событие (и самое первое) — иначе передача файла
     * вытеснила бы из журнала всё остальное.
     */
    fun recordProgress(key: String, everyN: Int, area: String, detail: () -> String) {
        val total = synchronized(lock) {
            val next = (counters[key] ?: 0L) + 1L
            counters[key] = next
            next
        }
        if (total == 1L || everyN <= 1 || total % everyN == 0L) {
            append(DiagnosticsLevel.INFO, area, detail())
        }
    }

    fun sessionUptimeMs(): Long = System.currentTimeMillis() - sessionStartedAtMs

    // ── Сбор отчёта ────────────────────────────────────────────────────

    /** Готовый отчёт и строки сводки для окна «Логи». */
    data class Snapshot(
        val statusLines: List<DiagnosticsLine>,
        val report: String,
        val createdAtMs: Long,
        val journalSize: Int,
        val warnCount: Int,
        val badCount: Int,
        val uptimeMs: Long,
        /** Версия приложения для шапки окна «Логи» («v11.74.194 (11074194)»). */
        val appVersion: String = "",
        /**
         * Последние записи журнала — свежие сверху. Владелец 2026-10-07:
         * «нет логов списка вообще» — список событий теперь виден в окне
         * отдельным блоком, а не только внутри текста отчёта.
         */
        val events: List<DiagnosticsJournalEntry> = emptyList(),
    )

    /**
     * Собирает всё, что нужно для отчёта. Обязательно вне главного потока:
     * здесь вызовы ядра, база и отдельный процесс `logcat -d`.
     *
     * Сбор идёт в два захода, и это принципиально (владелец 2026-10-07:
     * «кнопки не работают и нет логов списка вообще, всё на паузе»):
     *  * сначала быстрая часть — телефон, сеть, база, ядро; её итог сразу
     *    отдаётся в окно через [onPartial], поэтому окно никогда не пустует;
     *  * затем журнал процесса — самая долгая часть; она ограничена по числу
     *    строк и по времени (см. [scanLogcat]), и её результат уточняет отчёт.
     *
     * [onStage] называет текущий шаг словами — по нему видно, что сбор идёт,
     * а не «всё на паузе».
     */
    suspend fun collect(
        context: Context,
        onStage: ((String) -> Unit)? = null,
        onPartial: ((Snapshot) -> Unit)? = null,
    ): Snapshot = withContext(Dispatchers.IO) {
        val createdAtMs = System.currentTimeMillis()
        val journalSnapshot = synchronized(lock) {
            journal.toList() to counters.toMap()
        }
        onStage?.invoke(STAGE_DEVICE)
        val quickFacts = gatherFacts(
            context = context.applicationContext,
            createdAtMs = createdAtMs,
            counters = journalSnapshot.second,
            logcat = LogcatScan.EMPTY,
        )
        onPartial?.invoke(
            snapshotOf(quickFacts, journalSnapshot.first, listOf(LOGCAT_PENDING_LINE), createdAtMs),
        )
        onStage?.invoke(STAGE_LOGCAT)
        val logcat = scanLogcat()
        // Из журнала процесса в отчёт идут ровно четыре числа: сигналы ядра,
        // отказы пересылки и раздельная очередь «свои/чужие». Всё остальное
        // собрано выше — второй раз телефон не опрашиваем.
        val facts = if (logcat.isEmpty) {
            quickFacts
        } else {
            quickFacts.copy(
                coreMessageSignals = logcat.coreMessageSignals,
                relayQueueFull = logcat.relayQueueFull,
                relayQueueOwn = logcat.queueOwn,
                relayQueueForeign = logcat.queueForeign,
                relayQueueOwnSignals = logcat.queueOwnSignals,
            )
        }
        onStage?.invoke(STAGE_REPORT)
        snapshotOf(facts, journalSnapshot.first, logcat.lines, createdAtMs)
    }

    private fun snapshotOf(
        facts: DiagnosticsFacts,
        entries: List<DiagnosticsJournalEntry>,
        logcatLines: List<String>,
        createdAtMs: Long,
    ): Snapshot = Snapshot(
        statusLines = DiagnosticsReport.summaryLines(facts),
        report = DiagnosticsReport.render(facts, entries, logcatLines),
        createdAtMs = createdAtMs,
        journalSize = entries.size,
        warnCount = entries.count { it.level == DiagnosticsLevel.WARN },
        badCount = entries.count { it.level == DiagnosticsLevel.BAD },
        uptimeMs = (createdAtMs - sessionStartedAtMs).coerceAtLeast(0L),
        appVersion = facts.appVersion,
        events = entries.asReversed().take(EVENT_ROWS),
    )

    // ── Факты с телефона ───────────────────────────────────────────────

    private fun gatherFacts(
        context: Context,
        createdAtMs: Long,
        counters: Map<String, Long>,
        logcat: LogcatScan,
    ): DiagnosticsFacts {
        val battery = batteryFacts(context)
        val network = networkFacts(context)
        val database = databaseFacts(context)
        val coreBuild = safeNative { RustBridge.coreBuildInfo() }
        val mqttSeparator = " · MQTT: "
        val mqttAt = coreBuild.indexOf(mqttSeparator)
        val coreLine = if (mqttAt >= 0) coreBuild.take(mqttAt) else coreBuild
        val mqttLine = if (mqttAt >= 0) {
            "MQTT: " + coreBuild.substring(mqttAt + mqttSeparator.length)
        } else {
            ""
        }
        // Полное имя java.lang.Runtime: иначе песочница, где нет JDK, видит
        // «Runtime» как имя без объявления (ложное срабатывание проверки).
        val runtime = java.lang.Runtime.getRuntime()
        return DiagnosticsFacts(
            createdAtMs = createdAtMs,
            sessionStartedAtMs = sessionStartedAtMs,
            appVersion = appVersion(context),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            androidSdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(40)}".trim(),
            abis = Build.SUPPORTED_ABIS.take(3).joinToString(","),
            locale = Locale.getDefault().toString(),
            memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MEBIBYTE,
            memoryLimitMb = runtime.maxMemory() / MEBIBYTE,
            storageFreeMb = runCatching {
                StatFs(context.filesDir.absolutePath).availableBytes / MEBIBYTE
            }.getOrDefault(-1L),
            networkAvailable = network.available,
            networkTransport = network.transport,
            networkInternet = network.internet,
            networkMetered = network.metered,
            networkVpn = network.vpn,
            notificationsAllowed = notificationsAllowed(context),
            proxyTunnel = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .getBoolean("proxy_tunnel_enabled", true),
            swarmMode = runCatching {
                SwarmSettings.init(context)
                SwarmSettings.mode.value.title
            }.getOrDefault("неизвестно"),
            serverMode = runCatching { ServerMode.isEnabled(context) }.getOrDefault(true),
            batteryPercent = battery.percent,
            batteryCharging = battery.charging,
            powerSave = battery.powerSave,
            batteryExempt = battery.exempt,
            deviceIdle = battery.idle,
            coreRunning = runCatching { RustBridge.isRunning() }.getOrDefault(false),
            coreBuild = coreLine.ifBlank { "недоступно" },
            coreStage = runCatching { CoreStatus.stage.value }.getOrDefault(""),
            coreReady = runCatching { CoreStatus.ready.value }.getOrDefault(false),
            coreRestarts = counters[Counters.CORE_STARTS] ?: 0L,
            networkStatus = safeNative { RustBridge.networkStatus() },
            connectedPeers = safeNative { RustBridge.connectedPeers().toString() }.toLongOrNull() ?: 0L,
            pendingEvents = safeNative { RustBridge.pendingEvents().toString() }.toLongOrNull() ?: 0L,
            relayCustody = safeNative { RustBridge.relayCustodyMode() },
            relayQuarantine = safeNative { RustBridge.relayQuarantineCount().toString() }.toLongOrNull() ?: 0L,
            mqttLine = mqttLine,
            transferStates = database.states,
            failureCodes = database.failureCodes,
            lastFailureAtMs = database.lastFailureAtMs,
            custodyBytes = database.custodyBytes,
            counters = counters,
            lastIncomingAtMs = lastIncomingAtMs,
            lastOutgoingAtMs = lastOutgoingAtMs,
            lastAckAtMs = lastAckAtMs,
            peerKeyChangedAtMs = peerKeyChangedAtMs,
            coreMessageSignals = logcat.coreMessageSignals,
            relayQueueFull = logcat.relayQueueFull,
            relayQueueOwn = logcat.queueOwn,
            relayQueueForeign = logcat.queueForeign,
            relayQueueOwnSignals = logcat.queueOwnSignals,
        )
    }

    /**
     * Итог чтения системного журнала: строки для отчёта и два числа, которые
     * в Kotlin не посчитать. Первое — сколько сигналов о сообщениях ядро
     * отдало приложению (по нему видно, дошло ли входящее до нас вообще).
     * Второе — сколько раз пересылка отбросила пакет из-за полной очереди
     * получателя: это признак перегруженного телефона-хранителя.
     */
    private data class LogcatScan(
        val lines: List<String>,
        val coreMessageSignals: Long,
        val relayQueueFull: Long,
        /**
         * Последняя сводка очереди из журнала ядра: «Relay-очередь: своих=N
         * чужих=M». null — ядро такой строки ещё не писало (очередь пуста).
         * Владелец 2026-10-07: «нужно разделить свои и чужие».
         */
        val queueOwn: Long?,
        val queueForeign: Long?,
        /**
         * Сколько из «своих» — служебное: сигналы транспорта (NAT-пробивание,
         * «печатает…») и пакеты файловой передачи. Владелец 2026-10-07: в отчёте
         * было «своё ждёт получателя=1000», и по нему нельзя было понять, что
         * внутри сплошь сигналы, вытеснявшие переписку. Теперь это видно.
         */
        val queueOwnSignals: Long?,
    ) {
        /** Журнал процесса ещё не читали или прочитать не удалось. */
        val isEmpty: Boolean
            get() = lines.isEmpty() && coreMessageSignals == 0L && relayQueueFull == 0L &&
                queueOwn == null && queueForeign == null && queueOwnSignals == null

        companion object {
            val EMPTY = LogcatScan(emptyList(), 0L, 0L, null, null, null)
        }
    }

    private data class BatteryFacts(
        val percent: Int,
        val charging: Boolean,
        val powerSave: Boolean,
        val exempt: Boolean,
        val idle: Boolean,
    )

    private fun batteryFacts(context: Context): BatteryFacts {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull()
        return BatteryFacts(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            powerSave = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false),
            exempt = runCatching {
                power?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false),
            idle = runCatching { power?.isDeviceIdleMode == true }.getOrDefault(false),
        )
    }

    private data class NetworkFacts(
        val available: Boolean,
        val transport: String,
        val internet: Boolean,
        val metered: Boolean,
        val vpn: Boolean,
    )

    private fun networkFacts(context: Context): NetworkFacts {
        val manager = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return NetworkFacts(false, "неизвестно", false, false, false)
        val network = manager.activeNetwork
            ?: return NetworkFacts(false, "нет сети", false, false, false)
        val caps = manager.getNetworkCapabilities(network)
            ?: return NetworkFacts(true, "неизвестно", false, false, false)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "другая"
        }
        return NetworkFacts(
            available = true,
            transport = transport,
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    private fun notificationsAllowed(context: Context): Boolean = runCatching {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(true)

    private data class DatabaseFacts(
        val states: Map<String, Long>,
        val custodyBytes: Long,
        val failureCodes: List<TransferErrorLine>,
        val lastFailureAtMs: Long?,
    )

    /**
     * Очередь передач из базы: сколько в работе, что ждёт получателя, что
     * лежит на хранении. Читаем через Hilt-точку входа; любая ошибка (например,
     * база ещё закрыта) не должна ломать отчёт — вернём пустые значения.
     */
    private fun databaseFacts(context: Context): DatabaseFacts = runCatching {
        val dao = EntryPointAccessors
            .fromApplication(context, DiagnosticsEntryPoint::class.java)
            .fileTransferDao()
        val states = runBlocking { dao.transferStateCounts() }
            .associate { it.state to it.total }
        val custody = runBlocking { dao.custodyHeldBytes() }
        val failures = runBlocking { dao.transferErrorCounts() }
            .mapNotNull { row ->
                val code = row.errorCode?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TransferErrorLine(code = code, count = row.total.toLong())
            }
        val lastFailure = runBlocking { dao.lastFailureAtMs() }
        DatabaseFacts(
            states = states,
            custodyBytes = custody,
            failureCodes = failures,
            lastFailureAtMs = lastFailure,
        )
    }.getOrDefault(DatabaseFacts(emptyMap(), 0L, emptyList(), null))

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName ?: "unknown"} ($versionCode)"
    }.getOrDefault("unknown")

    private fun safeNative(value: () -> String): String = redact(runCatching(value).getOrDefault("unavailable"))

    // ── Журнал ─────────────────────────────────────────────────────────

    private fun append(level: DiagnosticsLevel, area: String, detail: String) {
        val safeArea = area.replace(AREA_SANITIZER, "_").take(24)
        val safeDetail = redact(detail).replace('\n', ' ').take(220)
        val entry = DiagnosticsJournalEntry(
            atMs = System.currentTimeMillis(),
            level = level,
            area = safeArea,
            detail = safeDetail,
        )
        synchronized(lock) {
            while (journal.size >= MAX_EVENTS) journal.removeFirst()
            journal.addLast(entry)
        }
        Log.i(TAG, "${DiagnosticsReport.clock(entry.atMs)} ${level.name} $safeArea: $safeDetail")
    }

    /**
     * `--pid` делает выборку ограниченной своим процессом. Часть старых
     * Android такой аргумент не понимает: пустой раздел безопаснее, чем
     * неограниченный logcat со всем телефоном.
     *
     * Чтение журнала процесса — самая долгая часть сбора, поэтому оно
     * ограничено с двух сторон (владелец 2026-10-07: «кнопки не работают…
     * всё на паузе»):
     *
     *  * `-t` — берём только последние [LOGCAT_LINE_LIMIT] строк. Прежде
     *    читался весь буфер телефона (десятки тысяч строк), и разбор каждой
     *    строки регэкспами занимал минуты: окно «Логи» всё это время стояло
     *    пустым, а кнопки выглядели мёртвыми;
     *  * [LOGCAT_BUDGET_MS] — сколько ждём читателя, и `destroy()` в любом
     *    случае: даже зависший `logcat` больше не держит окно.
     *
     * Приватность та же: в отчёт попадают только отобранные строки после
     * [redact] и [DiagnosticsPrivacy.hidePayloadBodies], а числа (сигналы ядра,
     * отказы пересылки) считаются по необработанному списку — это быстрее и
     * ничего лишнего в отчёт не выносит.
     */
    private fun scanLogcat(budgetMs: Long = LOGCAT_BUDGET_MS): LogcatScan = runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-t", LOGCAT_LINE_LIMIT.toString(),
            "-v",
            "threadtime",
            "--pid=${Process.myPid()}",
            "*:V",
        ).redirectErrorStream(true).start()
        val raw = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader = Thread {
            runCatching {
                // take(), а не break: break внутри лямбды поддерживают не все
                // версии Kotlin, а этот файл обязан собираться как есть.
                process.inputStream.bufferedReader().useLines { sequence ->
                    sequence.take(LOGCAT_LINE_LIMIT).forEach { line -> raw.add(line) }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(budgetMs)
        if (reader.isAlive) {
            Log.w(TAG, "logcat: чтение не уложилось в ${budgetMs}мс, отчёт собран из прочитанного")
        }
        process.destroy()
        val all = synchronized(raw) { raw.toList() }
        LogcatScan(
            // Свежие строки важнее старых: logcat отдаёт их по времени вперёд.
            lines = DiagnosticsPrivacy.collapseRepeatedLogLines(
                all.filter(::isRelevantProcessLog)
                    .map { DiagnosticsPrivacy.hidePayloadBodies(redact(it)) },
                limit = MAX_LOGCAT_LINES,
            ),
            coreMessageSignals = all.count { it.contains(CORE_MESSAGE_SIGNAL) }.toLong(),
            relayQueueFull = all.count { it.contains(RELAY_QUEUE_FULL) }.toLong(),
            queueOwn = queueStat(all, true),
            queueForeign = queueStat(all, false),
            queueOwnSignals = queueStat(all, true, marker = "сигналов="),
        )
    }.getOrDefault(LogcatScan.EMPTY)

    /**
     * Число из последней строки «Relay-очередь: своих=N чужих=M». Берём именно
     * последнюю: она самая свежая, а старые строки остаются в журнале после
     * того, как очередь уже разошлась.
     */
    private fun queueStat(
        lines: List<String>,
        own: Boolean,
        marker: String = if (own) "своих=" else "чужих=",
    ): Long? {
        for (line in lines.asReversed()) {
            val at = line.indexOf(RELAY_QUEUE_STATS)
            if (at < 0) continue
            val start = line.indexOf(marker, at)
            if (start < 0) continue
            val digits = line.substring(start + marker.length)
                .takeWhile { it.isDigit() }
            if (digits.isNotEmpty()) return digits.toLongOrNull()
        }
        return null
    }

    private val knownLogTags = listOf(
        "p2p_core",
        "FileTransfer",
        "ApuDiagnostics",
        "NetworkMonitor",
        "NetworkChange",
        "CoreServerService",
        "ProxyAutopilot",
        // Обмен ключами и отказы по ключу: без этого тега строки «File HELLO …
        // REJECTED: exchange key changed» в отчёт не попадали бы, а это ровно
        // та причина, по которой переписка молчит после переустановки.
        "FileTransferReceiver",
        "FileTransferRouter",
        "File HELLO",
    )

    /**
     * Приметы строк о переписке: их раньше в отчёт не пускал ни один фильтр,
     * а именно они объясняют «сообщения не доходят».
     */
    private val relevantLogWords = listOf(
        "Saved incoming message",
        "Sealed envelope NOT opened",
        "CF sealed envelope not opened",
        "Dropped message with non-node sender",
        "MessageReceived EMITTED",
        "Delivery ACK sent",
        "delivery ACK",
        "Relay-очередь получателя переполнена",
        "Relay-очередь: своих=",
        "запас своих",
        "СВОЁ сообщение не удержано",
        "File HELLO",
        "exchange key changed",
        // Присутствие собеседника: если он «выходит в сеть» каждые 10–30 с,
        // связь с ним рвётся, и это объясняет пропажу встречных сообщений.
        "peer online",
        "CROSS-BROKER DUPLICATE DROPPED",
        "F4",
        "FCAP",
        "APUF",
        "file_chunk_received",
        "MQTT",
        "Engine",
        "мертв", // «движок мёртв» — от ядра о потере связи
        "error",
        "Error",
        "fail",
        "Fail",
        "timeout",
        "Timeout",
        "reconnect",
    )

    private fun isRelevantProcessLog(line: String): Boolean {
        if (knownLogTags.none { line.contains(it) }) return false
        return relevantLogWords.any { line.contains(it) }
    }

    // ── Приватность ────────────────────────────────────────────────────

    private val AREA_SANITIZER = Regex("[^A-Za-z0-9_-]")

    /**
     * Не копировать в отчёт долговременные contact ID и сетевые адреса.
     * Сами правила — в `DiagnosticsPrivacy` (чистый Kotlin): их проверяют
     * JVM-тесты на runner, поэтому регрессию видно до выпуска, а не на
     * телефоне владельца.
     */
    internal fun redact(text: String): String = DiagnosticsPrivacy.redact(text)

    /** Строка ядра: сообщение дошло до EventBus, то есть передано приложению. */
    private const val CORE_MESSAGE_SIGNAL = "MessageReceived EMITTED"

    /** Строка ядра: пересылка не смогла сохранить пакет — очередь получателя полна. */
    private const val RELAY_QUEUE_FULL = "Relay-очередь получателя переполнена"

    /** Минутная сводка ядра: «Relay-очередь: своих=N чужих=M всего=T». */
    private const val RELAY_QUEUE_STATS = "Relay-очередь: своих="

    private const val MEBIBYTE = 1024L * 1024L
}

/** Hilt-точка входа для чтения очереди передач в фоне (см. BotApiEntryPoint). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsEntryPoint {
    fun fileTransferDao(): FileTransferDao
}
