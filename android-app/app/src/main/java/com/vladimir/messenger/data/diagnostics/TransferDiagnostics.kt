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
import java.util.concurrent.TimeUnit

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
    )

    /**
     * Собирает всё, что нужно для отчёта. Обязательно вне главного потока:
     * здесь вызовы ядра, база и отдельный процесс `logcat -d`.
     */
    suspend fun collect(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val createdAtMs = System.currentTimeMillis()
        val journalSnapshot = synchronized(lock) {
            journal.toList() to counters.toMap()
        }
        val facts = gatherFacts(
            context = context.applicationContext,
            createdAtMs = createdAtMs,
            counters = journalSnapshot.second,
        )
        val logcat = relevantLogcat()
        Snapshot(
            statusLines = DiagnosticsReport.summaryLines(facts),
            report = DiagnosticsReport.render(facts, journalSnapshot.first, logcat),
            createdAtMs = createdAtMs,
            journalSize = journalSnapshot.first.size,
            warnCount = journalSnapshot.first.count { it.level == DiagnosticsLevel.WARN },
            badCount = journalSnapshot.first.count { it.level == DiagnosticsLevel.BAD },
            uptimeMs = (createdAtMs - sessionStartedAtMs).coerceAtLeast(0L),
        )
    }

    // ── Факты с телефона ───────────────────────────────────────────────

    private fun gatherFacts(
        context: Context,
        createdAtMs: Long,
        counters: Map<String, Long>,
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
        )
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
     */
    private fun relevantLogcat(): List<String> = runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "--pid=${Process.myPid()}",
            "*:V",
        ).redirectErrorStream(true).start()
        val raw = process.inputStream.bufferedReader().useLines { sequence ->
            sequence
                .filter(::isRelevantProcessLog)
                .map { DiagnosticsPrivacy.hidePayloadBodies(redact(it)) }
                .toList()
        }
        process.waitFor(2, TimeUnit.SECONDS)
        process.destroy()
        // Свежие строки важнее старых: logcat отдаёт их по времени вперёд.
        DiagnosticsPrivacy.collapseRepeatedLogLines(raw, limit = MAX_LOGCAT_LINES)
    }.getOrDefault(emptyList())

    private val knownLogTags = listOf(
        "p2p_core",
        "FileTransfer",
        "ApuDiagnostics",
        "NetworkMonitor",
        "NetworkChange",
        "CoreServerService",
        "ProxyAutopilot",
    )

    private val relevantLogWords = listOf(
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

    private const val MEBIBYTE = 1024L * 1024L
}

/** Hilt-точка входа для чтения очереди передач в фоне (см. BotApiEntryPoint). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsEntryPoint {
    fun fileTransferDao(): FileTransferDao
}
