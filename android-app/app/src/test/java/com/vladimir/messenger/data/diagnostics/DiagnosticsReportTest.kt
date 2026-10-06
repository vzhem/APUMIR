package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат отчёта «Логи»: разделы, сводка и перевод состояний передач.
 * Тест чистый (без Android), поэтому его гоняет scripts/ci/check-chat-history.sh
 * на runner — то есть формат проверяется до выпуска, а не на телефоне.
 */
class DiagnosticsReportTest {

    private fun facts(
        networkAvailable: Boolean = true,
        networkInternet: Boolean = true,
        coreRunning: Boolean = true,
        batteryExempt: Boolean = true,
        transferStates: Map<String, Long> = emptyMap(),
        counters: Map<String, Long> = emptyMap(),
        mqttLine: String = "MQTT: tcp broker.example:1883, ConnAck 5 с назад",
        failureCodes: List<TransferErrorLine> = emptyList(),
        lastFailureAtMs: Long? = null,
    ) = DiagnosticsFacts(
        createdAtMs = 1_700_000_000_000L,
        sessionStartedAtMs = 1_700_000_000_000L - 168_000L,
        appVersion = "v11.74.182 (11074182)",
        androidRelease = "14",
        androidSdk = 34,
        device = "CUBOT KINGKONG X",
        abis = "arm64-v8a",
        locale = "ru_RU",
        memoryUsedMb = 24,
        memoryLimitMb = 128,
        storageFreeMb = 12_000,
        networkAvailable = networkAvailable,
        networkTransport = "мобильная сеть",
        networkInternet = networkInternet,
        networkMetered = true,
        networkVpn = false,
        notificationsAllowed = true,
        proxyTunnel = true,
        swarmMode = "Обычный",
        serverMode = true,
        batteryPercent = 85,
        batteryCharging = false,
        powerSave = false,
        batteryExempt = batteryExempt,
        deviceIdle = false,
        coreRunning = coreRunning,
        coreBuild = "p2p_core 0.1.0 · сборка v11.74.182 · 2 брокера",
        coreStage = "Ядро поднято",
        coreReady = true,
        coreRestarts = 1,
        networkStatus = "connected",
        connectedPeers = 1,
        pendingEvents = 0,
        relayCustody = "durable-encrypted",
        relayQuarantine = 0,
        mqttLine = mqttLine,
        transferStates = transferStates,
        failureCodes = failureCodes,
        lastFailureAtMs = lastFailureAtMs,
        custodyBytes = 0,
        counters = counters,
    )

    private fun titles(lines: List<DiagnosticsLine>): List<String> = lines.map { it.title }

    @Test
    fun summaryCoversEveryPartTheOwnerAsksAbout() {
        val lines = DiagnosticsReport.summaryLines(facts())
        assertEquals(
            listOf("Сеть", "Брокер", "Ядро", "Передачи", "Прямой канал", "Батарея"),
            titles(lines),
        )
        assertTrue(lines.all { it.value.isNotBlank() })
    }

    @Test
    fun summaryMarksProblemsInsteadOfHidingThem() {
        val offline = DiagnosticsReport.summaryLines(facts(networkAvailable = false))
        assertEquals(DiagnosticsLevel.BAD, offline.first().level)
        assertTrue(offline.first().value.contains("нет подключения"))

        val noCore = DiagnosticsReport.summaryLines(facts(coreRunning = false))
        assertEquals(DiagnosticsLevel.BAD, noCore.first { it.title == "Ядро" }.level)

        val batteryRisk = DiagnosticsReport.summaryLines(facts(batteryExempt = false))
        assertEquals(DiagnosticsLevel.WARN, batteryRisk.first { it.title == "Батарея" }.level)
        assertTrue(batteryRisk.first { it.title == "Батарея" }.value.contains("оптимизация батареи не снята"))

        val failing = DiagnosticsReport.summaryLines(
            facts(transferStates = mapOf("COMPLETE" to 4L, "FAILED" to 2L)),
        )
        assertEquals(DiagnosticsLevel.WARN, failing.first { it.title == "Передачи" }.level)
    }

    @Test
    fun f4LineShowsNegotiatedSessionsAndBytes() {
        val lines = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(
                    Counters.F4_NEGOTIATED to 2L,
                    Counters.F4_RANGES_SENT to 37L,
                    Counters.F4_BYTES_SENT to 8_808_038L,
                    Counters.F4_RANGES_RECEIVED to 12L,
                    Counters.F4_BYTES_RECEIVED to 2_345_678L,
                ),
            ),
        )
        val direct = lines.first { it.title == "Прямой канал" }
        assertTrue(direct.value.contains("сессий 2"))
        assertTrue(direct.value.contains("отдано 37 диапазонов"))
        assertTrue(direct.value.contains("8.4 МиБ"))
    }

    @Test
    fun reportHasStableSectionsForMachinesAndRussianForHumans() {
        val report = DiagnosticsReport.render(
            facts = facts(transferStates = mapOf("COMPLETE" to 4L, "FAILED" to 1L)),
            journal = listOf(
                DiagnosticsJournalEntry(1_700_000_000_000L, DiagnosticsLevel.OK, "app", "процесс приложения запущен"),
                DiagnosticsJournalEntry(1_700_000_001_000L, DiagnosticsLevel.WARN, "net", "сеть пропала"),
            ),
            logcat = listOf("10-06 12:40:00.000  1234  1234 I ApuDiagnostics: F4 прямой канал"),
        )

        listOf(
            "apu-diag/2",
            "[сводка]",
            "[окружение]",
            "[сеть]",
            "[ядро]",
            "[mqtt]",
            "[передачи]",
            "[сессия]",
            "[журнал]",
            "[лог процесса]",
        ).forEach { section ->
            assertTrue("нет раздела $section", report.contains(section))
        }
        assertTrue(report.contains("Приватность"))
        assertTrue(report.contains("app=v11.74.182 (11074182)"))
        assertTrue(report.contains("завершено=4"))
        assertTrue(report.contains("ошибок=1"))
        assertTrue(report.contains("предупреждений 1"))
        assertTrue(report.contains("ошибок 0"))
        assertFalse(report.contains("null"))
    }

    @Test
    fun transferStatesAreGroupedIntoHumanSums() {
        val sums = DiagnosticsReport.transferSums(
            mapOf(
                "TRANSFERRING" to 1L,
                "WAITING_RECIPIENT" to 2L,
                "CUSTODIED" to 3L,
                "HOLDING" to 1L,
                "COMPLETE" to 4L,
                "FAILED" to 5L,
                "CANCELLED" to 6L,
            ),
        )
        assertEquals(1L, sums.working)
        assertEquals(2L, sums.waiting)
        assertEquals(3L, sums.custodied)
        assertEquals(1L, sums.custody)
        assertEquals(4L, sums.completed)
        assertEquals(5L, sums.failed)
        assertEquals(6L, sums.cancelled)
    }

    @Test
    fun sizesAndDurationsAreReadable() {
        assertEquals("0 Б", DiagnosticsReport.formatBytes(0))
        assertEquals("512.0 КиБ", DiagnosticsReport.formatBytes(512L * 1024L))
        assertEquals("8.4 МиБ", DiagnosticsReport.formatBytes(8_808_038L))
        assertEquals("45 с", DiagnosticsReport.formatDuration(45_000L))
        assertEquals("2 мин 48 с", DiagnosticsReport.formatDuration(168_000L))
        assertEquals("1 ч 05 мин", DiagnosticsReport.formatDuration(3_900_000L))
        assertEquals("3 дн 4 ч", DiagnosticsReport.formatDuration(3L * 86_400_000L + 4L * 3_600_000L))
    }

    @Test
    fun logTimestampsSurviveRedaction() {
        // Первый отчёт владельца (2026-10-06) терял время суток: правило IPv6
        // «две группы через двоеточие» превращало 13:58:27 в [ipv6].
        val line = "10-06 13:58:27.154  6993  7082 I p2p_core: MQTT: initializing primary session"
        val redacted = DiagnosticsPrivacy.redact(line)
        assertTrue(redacted.contains("13:58:27.154"))
        assertFalse(redacted.contains("[ipv6]"))

        // Строки модулей ядра тоже не должны пострадать: «p2p_core::engine»
        // похоже на IPv6 («e::e»), но адресом не является.
        val moduleLine = DiagnosticsPrivacy.redact(
            "10-06 13:58:28.170 I p2p_core: p2p_core::engine::core: MESH durable",
        )
        assertTrue(moduleLine.contains("p2p_core::engine::core"))
        assertFalse(moduleLine.contains("[ipv6]"))

        val addresses = DiagnosticsPrivacy.redact(
            "peer 2001:db8:12::7 and fe80::1 and 2606:4700:3033::6815:2b3b, host 192.168.10.25:7777",
        )
        assertEquals(3, Regex("\\[ipv6]").findAll(addresses).count())
        assertTrue(addresses.contains("[ip]"))
        assertFalse(addresses.contains("192.168.10.25"))
    }

    @Test
    fun mqttPayloadBodiesNeverReachTheReport() {
        val line = "MQTT IN: broker=hivemq topic=p2pm2/msg/[contact] payload_len=880 " +
            "payload=\"APUSEAL1|AQD5AQsecret\""
        val hidden = DiagnosticsPrivacy.hidePayloadBodies(line)
        assertTrue(hidden.contains("payload_len=880"))
        assertTrue(hidden.contains("payload=[скрыто]"))
        assertFalse(hidden.contains("APUSEAL1"))
        assertFalse(hidden.contains("AQD5AQsecret"))
    }

    @Test
    fun repeatedLogLinesCollapseInsteadOfFillingTheReport() {
        val noisy = List(30) { i ->
            "10-06 13:58:2${i % 10}.000  6993  7092 I p2p_core: MQTT FANOUT QUEUED: " +
                "operation=mesh relay publish brokers=$i max_fanout=2"
        }
        val useful = List(4) { i ->
            "10-06 13:59:0$i.000  6993  7092 I p2p_core: MQTT: connection acknowledged by broker"
        }
        val collapsed = DiagnosticsPrivacy.collapseRepeatedLogLines(
            lines = noisy + useful,
            limit = 12,
            maxPerShape = 3,
        )
        assertTrue("строк больше лимита: ${collapsed.size}", collapsed.size <= 12)
        val real = collapsed.filterNot { it.startsWith("…") }
        assertEquals(3, real.count { it.contains("FANOUT QUEUED") })
        // Оставляем самые свежие строки формы, а не первые попавшиеся.
        assertTrue(real.any { it.contains("brokers=29") })
        assertFalse(real.any { it.contains("brokers=0 ") })
        val note = collapsed.first { it.startsWith("… ещё") }
        assertTrue(note.contains("FANOUT QUEUED"))
        // Служебная шапка logcat в счётчике не нужна: раньше было
        // «… ещё 68 строк: #-# #:#:#.# # # I p#p_core: …».
        assertFalse(note.contains("#-#"))
        assertFalse(note.contains("I p#p_core"))
        assertTrue("падеж: $note", note.contains("27 строк"))
        assertEquals(3, real.count { it.contains("connection acknowledged") })
    }

    @Test
    fun freshBrokerErrorShowsUpInTheSummary() {
        val lines = DiagnosticsReport.summaryLines(
            facts(mqttLine = "MQTT: tcp broker.example:1883, ConnAck 10 с назад, ошибка 15 с назад: Network timeout"),
        )
        val broker = lines.first { it.title == "Брокер" }
        assertEquals(DiagnosticsLevel.WARN, broker.level)
        assertTrue(broker.value.contains("на связи"))
        assertTrue(broker.value.contains("была ошибка"))
        assertTrue(broker.value.contains("не дождались ответа"))
        // В отчёте владельца (v11.74.184) было «1 мин назад назад была ошибка».
        assertFalse(broker.value.contains("назад назад"))

        val calm = DiagnosticsReport.summaryLines(
            facts(mqttLine = "MQTT: tcp broker.example:1883, ConnAck 10 с назад"),
        )
        assertEquals(DiagnosticsLevel.OK, calm.first { it.title == "Брокер" }.level)
    }

    @Test
    fun seedingIsSharingNotWorkInProgress() {
        val lines = DiagnosticsReport.summaryLines(
            facts(transferStates = mapOf("SEEDING" to 1L, "FAILED" to 14L)),
        )
        val transfers = lines.first { it.title == "Передачи" }
        assertTrue(transfers.value.contains("в работе 0"))
        assertTrue(transfers.value.contains("раздаётся 1"))
        assertTrue(transfers.value.contains("ошибок 14 за всё время"))

        val sums = DiagnosticsReport.transferSums(mapOf("SEEDING" to 1L))
        assertEquals(0L, sums.working)
        assertEquals(1L, sums.seeding)
    }

    @Test
    fun failureCodesExplainTheFailureCount() {
        val report = DiagnosticsReport.render(
            facts = facts(
                transferStates = mapOf("FAILED" to 14L),
                failureCodes = listOf(
                    TransferErrorLine("RESTORED_ELSEWHERE", 12L),
                    TransferErrorLine("NO_SPACE", 2L),
                ),
                lastFailureAtMs = 1_700_000_000_000L - 3_600_000L,
            ),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(report.contains("ошибок=14 (за всё время работы приложения)"))
        assertTrue(report.contains("ошибка.RESTORED_ELSEWHERE=12 — передача продолжилась на другом устройстве"))
        assertTrue(report.contains("ошибка.NO_SPACE=2 — на телефоне не хватило места"))
        assertTrue(report.contains("last_failure_ago=1 ч 00 мин"))
    }

    @Test
    fun coreAndNetworkStatesAreExplainedInRussian() {
        val ready = DiagnosticsReport.render(
            facts = facts(coreReady = true, networkStatus = "connecting"),
            journal = emptyList(),
            logcat = emptyList(),
        )
        // «Поднимаем ядро и сеть… → готово» выглядело противоречием.
        assertTrue(ready.contains("stage=готово (ядро поднято)"))
        assertTrue(ready.contains("network=подключается"))
        assertFalse(ready.contains("→ ещё поднимается"))

        val starting = DiagnosticsReport.render(
            facts = facts(coreReady = false, coreStage = "Поднимаем ядро и сеть…"),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(starting.contains("stage=Поднимаем ядро и сеть… → ещё поднимается"))
    }
}
