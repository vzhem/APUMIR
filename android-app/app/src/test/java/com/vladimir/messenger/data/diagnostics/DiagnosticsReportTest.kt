package com.vladimir.messenger.data.diagnostics

import com.vladimir.messenger.service.resolveUpdateCheckOutcome
import com.vladimir.messenger.service.shouldTryRelayFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        networkVpn: Boolean = false,
        coreRunning: Boolean = true,
        coreReady: Boolean = true,
        coreStage: String = "Ядро поднято",
        networkStatus: String = "connected",
        batteryExempt: Boolean = true,
        transferStates: Map<String, Long> = emptyMap(),
        counters: Map<String, Long> = emptyMap(),
        mqttLine: String = "MQTT: tcp broker.example:1883, ConnAck 5 с назад",
        failureCodes: List<TransferErrorLine> = emptyList(),
        lastFailureAtMs: Long? = null,
        lastIncomingAtMs: Long = 0L,
        peerKeyChangedAtMs: Long = 0L,
        coreMessageSignals: Long = 0L,
        relayQueueFull: Long = 0L,
        relayQueueOwn: Long? = null,
        relayQueueForeign: Long? = null,
        relayQueueOwnSignals: Long? = null,
        relayQueueRecipients: Long? = null,
        relayQueueOwnEvictions: Long = 0L,
        relayQueueOwnEvictionsAreCumulative: Boolean = false,
        stunFailedCycles: Long = 0L,
        stunSuccessfulBindings: Long = 0L,
        stunLastAttemptFailed: Boolean? = null,
        logcatWarnings: Long = 0L,
        logcatErrors: Long = 0L,
        onlineHelperParticipationEnabled: Boolean? = null,
        helperRoutes: HelperRouteDiagnostics = HelperRouteDiagnostics.EMPTY,
        updateDiagnostics: UpdateDiagnosticFacts = UpdateDiagnosticFacts.EMPTY,
        signingSeedMode: String = "unknown",
        installedSigningMode: String = "unknown",
        fileExchangeMode: String = "unknown",
        signingInstallBeforeCore: String = "unknown",
        fileExchangeInstallBeforeCore: String = "unknown",
        signingMatchesCoreRouting: Boolean? = null,
        liveDirectSwarmPeers: Int? = null,
        externalTransportAllowed: Boolean? = null,
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
        networkVpn = networkVpn,
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
        coreStage = coreStage,
        coreReady = coreReady,
        coreRestarts = 1,
        networkStatus = networkStatus,
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
        lastIncomingAtMs = lastIncomingAtMs,
        peerKeyChangedAtMs = peerKeyChangedAtMs,
        coreMessageSignals = coreMessageSignals,
        relayQueueFull = relayQueueFull,
        relayQueueOwn = relayQueueOwn,
        relayQueueForeign = relayQueueForeign,
        relayQueueOwnSignals = relayQueueOwnSignals,
        relayQueueRecipients = relayQueueRecipients,
        relayQueueOwnEvictions = relayQueueOwnEvictions,
        relayQueueOwnEvictionsAreCumulative = relayQueueOwnEvictionsAreCumulative,
        stunFailedCycles = stunFailedCycles,
        stunSuccessfulBindings = stunSuccessfulBindings,
        stunLastAttemptFailed = stunLastAttemptFailed,
        logcatWarnings = logcatWarnings,
        logcatErrors = logcatErrors,
        onlineHelperParticipationEnabled = onlineHelperParticipationEnabled,
        helperRoutes = helperRoutes,
        updateDiagnostics = updateDiagnostics,
        signingSeedMode = signingSeedMode,
        installedSigningMode = installedSigningMode,
        fileExchangeMode = fileExchangeMode,
        signingInstallBeforeCore = signingInstallBeforeCore,
        fileExchangeInstallBeforeCore = fileExchangeInstallBeforeCore,
        signingMatchesCoreRouting = signingMatchesCoreRouting,
        liveDirectSwarmPeers = liveDirectSwarmPeers,
        externalTransportAllowed = externalTransportAllowed,
    )

    private fun titles(lines: List<DiagnosticsLine>): List<String> = lines.map { it.title }

    @Test
    fun summaryCoversEveryPartTheOwnerAsksAbout() {
        val lines = DiagnosticsReport.summaryLines(facts())
        assertEquals(
            listOf("Сеть", "Брокер", "Ядро", "Сообщения", "Передачи", "Прямой канал", "Батарея"),
            titles(lines),
        )
        assertTrue(lines.all { it.value.isNotBlank() })
    }

    @Test
    fun helperRouteParserTracksSelectionFallbackDeclinesAndSignedReceiptWithoutIds() {
        val events = listOf(
            "10-12 10:00:00.001 1000 1000 I p2p_core: ONLINE_HELPER event=attempt_started cause=direct_send_failed",
            "10-12 10:00:00.010 1000 1000 I p2p_core: ONLINE_HELPER event=candidates selected=2",
            "10-12 10:00:00.020 1000 1000 I p2p_core: ONLINE_HELPER event=leg_started slot=1",
            "10-12 10:00:00.030 1000 1000 I p2p_core: ONLINE_HELPER event=leg_write slot=1 result=success",
            "10-12 10:00:00.040 1000 1000 I p2p_core: ONLINE_HELPER event=signed_decline_received slot=1",
            "10-12 10:00:00.050 1000 1000 I p2p_core: ONLINE_HELPER event=reserve_started trigger=primary_failed",
            "10-12 10:00:00.060 1000 1000 I p2p_core: ONLINE_HELPER event=leg_started slot=2",
            "10-12 10:00:00.070 1000 1000 I p2p_core: ONLINE_HELPER event=leg_write slot=2 result=success",
            "10-12 10:00:00.230 1000 1000 I p2p_core: ONLINE_HELPER event=recipient_receipt_issued",
            "10-12 10:00:00.240 1000 1000 I p2p_core: ONLINE_HELPER event=receipt_verified return_leg=2",
            "10-12 10:00:00.241 1000 1000 I p2p_core: ONLINE_HELPER event=attempt_result outcome=receipt_verified elapsed_ms=240",
            "10-12 10:00:01.001 1000 1000 I p2p_core: ONLINE_HELPER event=route_unavailable reason=runtime",
            "10-12 10:00:01.002 1000 1000 I p2p_core: ONLINE_HELPER event=route_unavailable reason=identity",
            "10-12 10:00:01.003 1000 1000 I p2p_core: ONLINE_HELPER event=route_unavailable reason=recipient_identity recipient_id=private-value",
            "10-12 10:00:01.004 1000 1000 I p2p_core: ONLINE_HELPER event=attempt_result outcome=not_prepared elapsed_ms=1",
            "unrelated log line must not affect helper counters",
        )

        val parsed = HelperRouteDiagnosticsParser.parse(events)
        assertEquals(1L, parsed.attempts)
        assertEquals(3L, parsed.routeUnavailable)
        assertEquals(1L, parsed.routeUnavailableRuntime)
        assertEquals(1L, parsed.routeUnavailableLocalIdentity)
        assertEquals(1L, parsed.routeUnavailableRecipientIdentity)
        assertEquals(1L, parsed.attemptsNotPrepared)
        assertEquals(2L, parsed.selectedCandidates)
        assertEquals(1L, parsed.primaryLegsStarted)
        assertEquals(1L, parsed.reserveLegsStarted)
        assertEquals(2L, parsed.legWritesSucceeded)
        assertEquals(1L, parsed.signedDeclinesReceived)
        assertEquals(1L, parsed.reserveAfterPrimaryFailure)
        assertEquals(1L, parsed.recipientReceiptsIssued)
        assertEquals(1L, parsed.senderReceiptsVerified)
        assertEquals(1L, parsed.receiptReturnedByReserve)
        assertEquals(0L, parsed.receiptReturnedByPrimary)
        assertEquals(240L, parsed.averageVerifiedReceiptMs ?: -1L)
    }

    @Test
    fun helperReportLabelsStreamWritesAndSignedReceiptsSeparately() {
        val report = DiagnosticsReport.render(
            facts = facts(
                onlineHelperParticipationEnabled = false,
                helperRoutes = HelperRouteDiagnostics(
                    attempts = 2L,
                    selectedCandidates = 3L,
                    attemptsWithoutCandidates = 1L,
                    routeUnavailable = 3L,
                    routeUnavailableRuntime = 1L,
                    routeUnavailableLocalIdentity = 1L,
                    routeUnavailableRecipientIdentity = 1L,
                    primaryLegsStarted = 2L,
                    reserveLegsStarted = 1L,
                    legWritesSucceeded = 2L,
                    legWritesFailed = 1L,
                    reserveAfterPrimaryFailure = 1L,
                    signedDeclinesReceived = 1L,
                    recipientReceiptsIssued = 1L,
                    senderReceiptsVerified = 1L,
                    receiptReturnedByReserve = 1L,
                    attemptsWithoutReceipt = 1L,
                    averageVerifiedReceiptMs = 240L,
                ),
            ),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(report.contains("local_participation_enabled=false"))
        assertTrue(report.contains("attempts_after_direct_failure=2"))
        assertTrue(report.contains("route_unavailable=3"))
        assertTrue(report.contains("route_unavailable_runtime=1"))
        assertTrue(report.contains("route_unavailable_local_identity=1"))
        assertTrue(report.contains("route_unavailable_recipient_identity=1"))
        assertTrue(report.contains("selected_helper_slots_total=3"))
        assertFalse(report.contains("private-value"))
        assertTrue(report.contains("reserve_after_primary_failure=1"))
        assertTrue(report.contains("helper_stream_writes_succeeded=2 (не подтверждение доставки)"))
        assertTrue(report.contains("recipient_signed_receipts_verified=1"))
        assertTrue(report.contains("verified_receipt_return_leg_2=1"))
        assertTrue(report.contains("verified_receipt_average_elapsed_ms=240"))
        assertTrue(report.contains("не личность/уникальность помощника"))
    }

    @Test
    fun cubotReportShowsLocalCryptoAndRealSwarmWithoutDiagnosingPeerFromFailedOpens() {
        val facts = facts(
            counters = mapOf(
                Counters.MSG_OUT to 2L,
                Counters.MSG_IN_NOT_OPENED to 1271L,
                Counters.MSG_IN_LOCAL_CRYPTO_UNAVAILABLE to 1271L,
                Counters.FILE_STARTED_OUT to 16L,
            ),
            relayQueueOwn = 94L,
            signingSeedMode = "unavailable",
            installedSigningMode = "legacy-only",
            fileExchangeMode = "unavailable",
            signingInstallBeforeCore = "timeout",
            fileExchangeInstallBeforeCore = "skipped_no_signing",
            liveDirectSwarmPeers = 0,
            externalTransportAllowed = true,
        )
        val summary = DiagnosticsReport.summaryLines(facts)
        assertEquals(DiagnosticsLevel.WARN, summary.first { it.title == "Локальные ключи" }.level)
        assertTrue(summary.first { it.title == "Прямой рой" }.value.contains("подтверждённых соседей 0"))
        val message = summary.first { it.title == "Сообщения" }
        assertTrue(message.value.contains("1271 попыток"))
        assertFalse(message.value.contains("устаревший ключ"))
        val report = DiagnosticsReport.render(facts, emptyList(), emptyList())
        assertTrue(report.contains("signing_seed_keystore=unavailable"))
        assertTrue(report.contains("installed_signing_identity=legacy-only"))
        assertTrue(report.contains("signing_install_before_core=timeout"))
        assertTrue(report.contains("file_exchange_keystore=unavailable"))
        assertTrue(report.contains("file_exchange_install_before_core=skipped_no_signing"))
        assertTrue(report.contains("signed_direct_swarm_peers=0"))
        assertTrue(report.contains("external_transport_allowed_now=true (политика на момент снимка, не факт обращения)"))
        assertTrue(report.contains("из них при неготовой локальной identity при старте=1271"))
        assertTrue(report.contains("передач начато (отправка)=16"))
        assertTrue(report.contains("включая самосев APK"))
        assertFalse(report.contains("устаревшая копия ключа"))
    }

    @Test
    fun confirmedDirectNeighborReportsExternalPolicyWithoutClaimingActualRequests() {
        val reportFacts = facts(
            signingSeedMode = "ready",
            installedSigningMode = "legacy+ed25519-sidecar-v1",
            fileExchangeMode = "ready",
            signingInstallBeforeCore = "ready",
            fileExchangeInstallBeforeCore = "ready",
            signingMatchesCoreRouting = true,
            liveDirectSwarmPeers = 1,
            externalTransportAllowed = false,
        )
        val lines = DiagnosticsReport.summaryLines(reportFacts)
        assertEquals(DiagnosticsLevel.OK, lines.first { it.title == "Локальные ключи" }.level)
        val direct = lines.first { it.title == "Прямой рой" }
        assertEquals(DiagnosticsLevel.OK, direct.level)
        assertTrue(direct.value.contains("подтверждённых соседей 1"))
        assertTrue(direct.value.contains("внешний транспорт сейчас не разрешён"))
        val report = DiagnosticsReport.render(reportFacts, emptyList(), emptyList())
        assertTrue(report.contains("external_transport_allowed_now=false (политика на момент снимка, не факт обращения)"))
        assertTrue(report.contains("signing_routing_matches_core=true (без идентификаторов)"))
    }

    @Test
    fun lateReadyKeyModesWithoutVerifiedPreEngineInstallAreNotGreen() {
        val line = DiagnosticsReport.summaryLines(facts(
            signingSeedMode = "ready",
            installedSigningMode = "legacy+ed25519-sidecar-v1",
            fileExchangeMode = "ready",
            signingInstallBeforeCore = "timeout",
            fileExchangeInstallBeforeCore = "skipped_no_signing",
        )).first { it.title == "Локальные ключи" }
        assertEquals(DiagnosticsLevel.WARN, line.level)
        assertTrue(line.value.contains("до ядра подпись/обмен timeout/skipped_no_signing"))
    }

    @Test
    fun readySeedWithDifferentCoreRoutingIsNotReportedAsWorkingSigningIdentity() {
        val facts = facts(
            signingSeedMode = "ready",
            installedSigningMode = "legacy+ed25519-sidecar-v1",
            fileExchangeMode = "ready",
            signingInstallBeforeCore = "ready",
            fileExchangeInstallBeforeCore = "ready",
            signingMatchesCoreRouting = false,
        )
        val line = DiagnosticsReport.summaryLines(facts).first { it.title == "Локальные ключи" }
        assertEquals(DiagnosticsLevel.WARN, line.level)
        assertTrue(line.value.contains("совпадение с routing ID false"))
        assertTrue(DiagnosticsReport.render(facts, emptyList(), emptyList())
            .contains("signing_routing_matches_core=false (без идентификаторов)"))
    }

    /**
     * Главная жалоба владельца 2026-10-07: «мои сообщения до него доходят, а от
     * него ко мне не приходят». В сводке это должно быть предупреждением, а не
     * строкой «всё хорошо»: свои уходят и подтверждаются, входящих нет.
     */
    @Test
    fun summarySpotsOutgoingWithoutIncoming() {
        val lines = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(
                    Counters.MSG_OUT to 5L,
                    Counters.MSG_ACK to 5L,
                    Counters.MSG_IN_NOT_OPENED to 3L,
                ),
            ),
        )
        val message = lines.first { it.title == "Сообщения" }
        assertEquals(DiagnosticsLevel.WARN, message.level)
        assertTrue(message.value.contains("входящих нет"))
        assertTrue(message.value.contains("не вскрылось 3"))
    }

    /** Не вскрывшийся конверт — потеря настоящего сообщения, и её видно. */
    @Test
    fun messageSectionShowsDirectionsAndReasons() {
        val report = DiagnosticsReport.render(
            facts = facts(
                counters = mapOf(
                    Counters.MSG_IN to 4L,
                    Counters.MSG_OUT to 7L,
                    Counters.MSG_ACK to 6L,
                    Counters.MSG_IN_NOT_OPENED to 2L,
                ),
                lastIncomingAtMs = 1_700_000_000_000L - 120_000L,
                coreMessageSignals = 42L,
                relayQueueFull = 9L,
            ),
            journal = emptyList(),
            logcat = emptyList(),
        )
        for (marker in listOf(
            "[сообщения]",
            "входящих показано=4",
            "исходящих отправлено=7",
            "подтверждений доставки=6",
            "отложено (ожидает связь/ключи; число попыток)=0",
            "не ушло (узел отказал или нет сети)=0",
            "последнее входящее=",
            "не вскрылось (попытки; адресат/причина не подтверждены)=2",
            "ядро передало сигналов=42",
            "пересылка: отброшено из-за полной очереди=9",
            "очередь ядра: своё ждёт получателя=нет (пусто)",
            "очередь ядра: чужая пересылка=нет (пусто)",
        )) {
            assertTrue("в отчёте нет строки: $marker", report.contains(marker))
        }
        // Журнал процесса показывал «EMITTED to EventBus», а показано было 4:
        // разница между этими числами и есть предмет разбора.
        assertTrue(report.contains("по журналу процесса"))
        assertTrue(report.contains("не сбрасывайте ключи по этому счётчику"))
        assertFalse(report.contains("устаревшая копия ключа"))
    }

    /** Тихий вечер без переписки не должен выглядеть поломкой. */
    @Test
    fun quietSessionIsNotAWarning() {
        val message = DiagnosticsReport.summaryLines(facts())
            .first { it.title == "Сообщения" }
        assertEquals(DiagnosticsLevel.INFO, message.level)
        assertTrue(message.value.contains("ещё не было"))
    }

    /** Обычный живой обмен: принято, отправлено, подтверждено. */
    @Test
    fun healthyExchangeReadsAsOk() {
        val message = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(
                    Counters.MSG_IN to 9L,
                    Counters.MSG_OUT to 9L,
                    Counters.MSG_ACK to 8L,
                ),
                lastIncomingAtMs = 1_700_000_000_000L - 60_000L,
            ),
        ).first { it.title == "Сообщения" }
        assertEquals(DiagnosticsLevel.OK, message.level)
        assertTrue(message.value.contains("принято 9"))
        assertTrue(message.value.contains("отправлено 9"))
        assertTrue(message.value.contains("последнее входящее"))
    }

    /** Only a verified signed HELLO pin mismatch is evidence of a changed peer binding. */
    @Test
    fun peerKeyChangeExplainsSilentChat() {
        val lines = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(Counters.MSG_OUT to 3L, Counters.MSG_ACK to 3L),
                peerKeyChangedAtMs = 1_700_000_000_000L - 300_000L,
            ),
        )
        val message = lines.first { it.title == "Сообщения" }
        assertEquals(DiagnosticsLevel.WARN, message.level)
        assertTrue(message.value.contains("подписанный ключ"))
        assertTrue(message.value.contains("проверьте его личность"))
        val report = DiagnosticsReport.render(
            facts = facts(
                counters = mapOf(Counters.MSG_PEER_KEY_CHANGED to 1L),
                peerKeyChangedAtMs = 1_700_000_000_000L - 300_000L,
            ),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(report.contains("смена ключа у собеседника (раз)=1"))
        assertTrue(report.contains("проверьте личность собеседника"))
        assertFalse(report.contains("отсканируйте его QR-код заново"))
    }

    /**
     * Владелец 2026-10-07: «нужно разделить свои и чужие, свои всегда
     * первостепенны». В отчёте своя переписка и чужая пересылка — разные
     * строки, и «своё ждёт получателя» не прячется за общим числом.
     */
    @Test
    fun queueIsSplitBetweenOwnAndForeign() {
        val report = DiagnosticsReport.render(
            facts = facts(relayQueueOwn = 2L, relayQueueForeign = 340L),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(report.contains("очередь ядра: своё ждёт получателя=2"))
        assertTrue(report.contains("очередь ядра: чужая пересылка=340"))
        assertTrue(report.contains("не делит лимит с чужой пересылкой"))

        // И в сводке: своё ждёт получателя — это оговорка, а не «всё хорошо».
        val message = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(Counters.MSG_IN to 3L, Counters.MSG_OUT to 4L),
                lastIncomingAtMs = 1_700_000_000_000L - 60_000L,
                relayQueueOwn = 2L,
            ),
        ).first { it.title == "Сообщения" }
        assertTrue(message.value.contains("своё ждёт получателя 2"))
    }

    @Test
    fun ownQueueEvictionCounterResetsAtTheNewestCoreStartAndUsesLegacySamples() {
        val previousCoreSummary =
            "Relay-очередь: своих=3 сигналов=0 чужих=0 всего=3 получателей=1 снято-по-ACK=0 вытеснено-своих=9"
        val restartedCore =
            "MESH durable: restored 0/0 relay record(s) after startup (своих 0, чужих 0, own_capacity_evictions=0)"
        val reset = DiagnosticsPrivacy.parseRelayQueueOwnEvictions(
            listOf(previousCoreSummary, restartedCore),
        )
        assertEquals(0L, reset.count)
        assertTrue(reset.cumulative)

        val afterNewEviction = DiagnosticsPrivacy.parseRelayQueueOwnEvictions(
            listOf(previousCoreSummary, restartedCore, previousCoreSummary.replace("=9", "=2")),
        )
        assertEquals(2L, afterNewEviction.count)
        assertTrue(afterNewEviction.cumulative)

        val legacy = DiagnosticsPrivacy.parseRelayQueueOwnEvictions(
            listOf("Relay-очередь: запас своих для получателя исчерпан, вытеснено самое старое своё pk_0123456789abcdef"),
        )
        assertEquals(1L, legacy.count)
        assertFalse(legacy.cumulative)
    }

    @Test
    fun ownQueueEvictionsAreReportedAsCumulativeRamCachePressure() {
        val facts = facts(
            relayQueueOwn = 1_000L,
            relayQueueForeign = 3_292L,
            relayQueueRecipients = 18L,
            relayQueueOwnEvictions = 3L,
            relayQueueOwnEvictionsAreCumulative = true,
        )
        val message = DiagnosticsReport.summaryLines(facts).first { it.title == "Сообщения" }
        assertEquals(DiagnosticsLevel.INFO, message.level)
        assertTrue(message.value.contains("своё ждёт получателя 1000"))
        assertTrue(message.value.contains("вытеснений своего из ограниченного RAM-кэша: 3 (с запуска ядра)"))
        assertTrue(message.value.contains("само по себе не доказывает потерю"))

        val report = DiagnosticsReport.render(facts, emptyList(), emptyList())
        assertTrue(report.contains("очередь ядра: получателей=18"))
        assertTrue(report.contains("вытеснений своих из RAM-кэша=3 (с запуска ядра"))
        assertTrue(report.contains("при успешной durable/outbox-записи копия остаётся для досылки"))
    }

    /** Чужая очередь сама по себе — не повод пугать: она для других узлов. */
    @Test
    fun foreignQueueAloneIsNotAWarning() {
        val message = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(Counters.MSG_IN to 1L),
                relayQueueForeign = 480L,
            ),
        ).first { it.title == "Сообщения" }
        assertEquals(DiagnosticsLevel.OK, message.level)
        assertFalse(message.value.contains("ждёт получателя"))
    }

    /** «Отложено до сети» — это не молчание и не поломка, так и должно звучать. */
    @Test
    fun offlineQueueIsNotMistakenForSilence() {
        val message = DiagnosticsReport.summaryLines(
            facts(counters = mapOf(Counters.MSG_QUEUED_OFFLINE to 4L)),
        ).first { it.title == "Сообщения" }
        assertEquals(DiagnosticsLevel.INFO, message.level)
        assertTrue(message.value.contains("отложено до сети/ключей 4"))
    }

    /** Сбой отправки обязан быть назван, а не спрятан в «всё хорошо». */
    @Test
    fun refusedSendIsNamedInTheSummary() {
        val message = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(
                    Counters.MSG_IN to 2L,
                    Counters.MSG_OUT to 5L,
                    Counters.MSG_ACK to 5L,
                    Counters.MSG_SEND_FAILED to 3L,
                ),
                lastIncomingAtMs = 1_700_000_000_000L - 60_000L,
            ),
        ).first { it.title == "Сообщения" }
        assertTrue(message.value.contains("не ушло 3"))
    }

    /** Ядро отдало приложению сигналы, а показано ноль — это и есть разбор. */
    @Test
    fun relayQueueOverflowIsExplainedNotJustCounted() {
        val report = DiagnosticsReport.render(
            facts = facts(relayQueueFull = 14L, coreMessageSignals = 30L),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(report.contains("пересылка: отброшено из-за полной очереди=14"))
        // 2026-10-07: текст уточнён — отказано ЧУЖОМУ, чужая очередь на получателя.
        assertTrue(report.contains("это пакеты для ДРУГИХ узлов"))
        assertTrue(report.contains("чужой лимит на получателя"))
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
        assertTrue(direct.value.contains("исходящих сессий согласовано 2"))
        assertTrue(direct.value.contains("отдано 37 диапазонов"))
        assertTrue(direct.value.contains("8.4 МиБ"))

        // Inbound ranges do not increment the locally negotiated outbound-session counter.
        val inboundOnly = DiagnosticsReport.summaryLines(
            facts(
                counters = mapOf(
                    Counters.F4_RANGES_RECEIVED to 46L,
                    Counters.F4_BYTES_RECEIVED to 5_866_672L,
                ),
            ),
        ).first { it.title == "Прямой канал" }
        assertTrue(inboundOnly.value.contains("исходящих сессий согласовано 0"))
        assertTrue(inboundOnly.value.contains("принято 46"))
    }

    @Test
    fun seedingWaitsForAReceiverAndHistoricalStunFailureIsExplained() {
        val waiting = DiagnosticsReport.summaryLines(
            facts(transferStates = mapOf("SEEDING" to 1L)),
        ).first { it.title == "Прямой канал" }
        assertEquals(DiagnosticsLevel.INFO, waiting.level)
        assertTrue(waiting.value.contains("файл раздаётся"))
        assertTrue(waiting.value.contains("когда получатель запросит данные"))
        assertFalse(waiting.value.contains("запустите передачу файла"))

        val blockedUdp = DiagnosticsReport.summaryLines(
            facts(
                networkVpn = true,
                transferStates = mapOf("SEEDING" to 1L),
                stunFailedCycles = 2L,
                stunLastAttemptFailed = true,
            ),
        ).first { it.title == "Прямой канал" }
        assertEquals(DiagnosticsLevel.INFO, blockedUdp.level)
        assertTrue(blockedUdp.value.contains("последний STUN в выборке не ответил; безуспешных циклов 2"))
        assertTrue(blockedUdp.value.contains("VPN может блокировать UDP"))
        assertTrue(blockedUdp.value.contains("ждёт запроса получателя"))

        // Исторические W/E в выборке logcat не должны маскироваться под ошибку текущего состояния.
        val historicalOnly = DiagnosticsReport.summaryLines(
            facts(logcatWarnings = 5L, logcatErrors = 1L),
        )
        assertFalse(historicalOnly.any { it.level == DiagnosticsLevel.WARN || it.level == DiagnosticsLevel.BAD })

        val report = DiagnosticsReport.render(
            facts = facts(
                stunFailedCycles = 2L,
                stunLastAttemptFailed = true,
                logcatWarnings = 5L,
                logcatErrors = 1L,
            ),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(report.contains("уровни в ограниченной выборке logcat: предупреждений W=5"))
        assertTrue(report.contains("ошибок/фатальных E/F=1"))
        assertTrue(report.contains("STUN в выборке: ответов=0, циклов без ответа=2, последний результат=неудача"))
    }

    @Test
    fun updatePolicyFallsBackOnlyWhenTheDirectCheckDidNotConfirmCurrentVersion() {
        assertTrue(shouldTryRelayFallback("network_error"))
        assertTrue(shouldTryRelayFallback("http_error"))
        assertTrue(shouldTryRelayFallback("invalid_response"))
        assertFalse(shouldTryRelayFallback("up_to_date"))
        assertFalse(shouldTryRelayFallback("update_available"))
        assertEquals(
            "fallback_not_due",
            resolveUpdateCheckOutcome(false, "network_error", "not_due"),
        )
        assertEquals(
            "unavailable",
            resolveUpdateCheckOutcome(false, "http_error", "network_error"),
        )
        assertEquals(
            "up_to_date",
            resolveUpdateCheckOutcome(false, "http_error", "up_to_date"),
        )
        assertEquals(
            "unavailable",
            resolveUpdateCheckOutcome(false, "asset_missing", "up_to_date"),
        )
    }

    @Test
    fun updateSectionExplainsCheckSourceAndNotificationWithoutUrls() {
        val report = DiagnosticsReport.render(
            facts = facts(
                updateDiagnostics = UpdateDiagnosticFacts(
                    currentVersion = "v11.74.340",
                    latestVersion = "v11.74.341",
                    lastCheckAtMs = 1_700_000_000_000L - 60_000L,
                    checkDurationMs = 842L,
                    trigger = "manual",
                    source = "github",
                    outcome = "update_available",
                    httpStatus = 200,
                    githubOutcome = "update_available",
                    githubHttpStatus = 200,
                    relayOutcome = "not_attempted",
                    notificationVersion = "v11.74.341",
                    notificationKind = "official",
                    notificationAtMs = 1_700_000_000_000L - 30_000L,
                    notificationResult = "posted",
                    notificationsAllowed = true,
                    notificationChannelEnabled = true,
                    notificationChannelImportance = 3,
                ),
            ),
            journal = emptyList(),
            logcat = emptyList(),
        )

        assertTrue(report.contains("current_version=v11.74.340"))
        assertTrue(report.contains("latest_version=v11.74.341"))
        assertTrue(report.contains("last_check_trigger=manual"))
        assertTrue(report.contains("last_check_source=github"))
        assertTrue(report.contains("last_check_result=update_available"))
        assertTrue(report.contains("github_http_status=200"))
        assertTrue(report.contains("relay_check_result=not_attempted"))
        assertTrue(report.contains("last_check_duration_ms=842"))
        assertTrue(report.contains("last_notification_attempt_kind=official"))
        assertTrue(report.contains("last_notification_attempt_result=posted"))
        assertTrue(report.contains("notifications_allowed=true"))
        assertTrue(report.contains("update_channel_enabled=true"))
        assertTrue(report.contains("update_channel_importance=3"))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("downloadUrl"))
        assertFalse(report.contains("releaseNotes"))
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
            "apu-diag/5",
            "[сводка]",
            "[окружение]",
            "[сеть]",
            "[обновления]",
            "[ядро]",
            "[mqtt]",
            "[помощники]",
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
    fun fileFallbackDiagnosticsSeparateAttemptsFromActualQueueAdmission() {
        val text = DiagnosticsReport.render(
            facts(counters = mapOf(
                Counters.FILE_BROKER_FALLBACK_ATTEMPTS to 12L,
                Counters.APK_DIRECT_UNREACHABLE to 3L,
            )),
            emptyList(),
            emptyList(),
        )
        assertTrue(text.contains("file_broker_fallback_attempts=12 (сессия; не доказательство постановки в relay)"))
        assertTrue(text.contains("apk_direct_unreachable=3 (сессия; APK-пакет в брокер не отправлялся)"))
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
    fun messageUuidsAreRedactedFromJournalAndLogcat() {
        val id = "11111111-2222-4333-8444-555555555555"
        val safe = DiagnosticsPrivacy.redact("direct delivery ACK: msgId=$id")
        assertTrue(safe.contains("msgId=[id]"))
        assertFalse(safe.contains(id))

        val report = DiagnosticsReport.render(
            facts = facts(),
            journal = listOf(
                DiagnosticsJournalEntry(1_700_000_000_000L, DiagnosticsLevel.INFO, "msg", safe),
            ),
            logcat = listOf(safe),
        )
        assertFalse(report.contains(id))
        assertTrue(report.contains("UUID сообщений"))
    }

    @Test
    fun messageDerivedSha256ReceiptKeysAreRedactedFromMqttTopics() {
        val messageHash = "0123456789abcdef".repeat(4)
        val line = "MQTT IN: topic=p2pm2/msg/[contact]/receipt/$messageHash " +
            "cleanup=p2pm2/receipt/cleanup/$messageHash payload=[скрыто]"

        val safe = DiagnosticsPrivacy.redact(line)

        assertTrue(safe.contains("p2pm2/msg/[contact]/receipt/[id]"))
        assertTrue(safe.contains("p2pm2/receipt/cleanup/[id]"))
        assertFalse(safe.contains(messageHash))
    }

    @Test
    fun logcatCountsWarningsAndErrorsAndKeepsHeartbeatTail() {
        val levels = DiagnosticsPrivacy.countLogcatSeverities(
            listOf(
                "10-08 08:32:14.915 17568 23168 W p2p_core: STUN failed",
                "10-08 08:32:15.915 17568 23168 E p2p_core: request failed",
                "10-08 08:32:16.915 17568 23168 I p2p_core: connected",
            ),
        )
        assertEquals(DiagnosticsLogcatLevels(warnings = 1L, errors = 1L), levels)

        val longLine = "MQTT LIVENESS HEARTBEAT: " + "prefix-field ".repeat(30) + "request_errors=7"
        val clipped = DiagnosticsPrivacy.clipLogcatLine(longLine, maxChars = 100)
        assertEquals(100, clipped.length)
        assertTrue(clipped.startsWith("MQTT LIVENESS HEARTBEAT:"))
        assertTrue(clipped.endsWith("request_errors=7"))
        assertTrue(clipped.contains("середина строки скрыта"))
    }

    @Test
    fun absolutePathsAndNamedFileFieldsAreRedacted() {
        val safe = DiagnosticsPrivacy.redact(
            "open failed path=/storage/emulated/0/Download/private-report.pdf " +
                "uri=file:///data/user/0/com.apu/files/account.db",
        )
        assertFalse(safe.contains("/storage"))
        assertFalse(safe.contains("private-report.pdf"))
        assertFalse(safe.contains("/data/user"))
        assertFalse(safe.contains("account.db"))
        assertTrue(safe.contains("path=[скрыто]"))
        assertTrue(safe.contains("uri=[скрыто]"))
    }

    @Test
    fun logcatErrorPriorityKeepsEAndFLinesWithinTheReportLimit() {
        val lines = buildList {
            repeat(20) { index ->
                add("10-08 08:32:${index.toString().padStart(2, '0')}.000 17568 23168 W p2p_core: repeated warning $index")
            }
            add("10-08 08:33:00.000 17568 23168 E p2p_core: distinctive error that must survive")
            add("10-08 08:33:01.000 17568 23168 F p2p_core: fatal error that must survive")
            repeat(20) { index ->
                add("10-08 08:34:${index.toString().padStart(2, '0')}.000 17568 23168 I p2p_core: routine info $index")
            }
        }

        val selected = DiagnosticsPrivacy.prioritizeLogcatErrors(lines, limit = 10, maxErrorLines = 4)
        assertTrue("строк больше лимита: ${selected.size}", selected.size <= 10)
        assertTrue(selected.any { it.contains("distinctive error that must survive") })
        assertTrue(selected.any { it.contains("fatal error that must survive") })
        assertEquals(2L, DiagnosticsPrivacy.countLogcatSeverities(selected).errors)
        assertEquals(1L, DiagnosticsPrivacy.countLogcatSeverities(selected).warnings)

        val oneSlot = DiagnosticsPrivacy.prioritizeLogcatErrors(
            lines = List(12) { index ->
                "10-08 08:35:${index.toString().padStart(2, '0')}.000 17568 23168 E p2p_core: unique failure $index"
            },
            limit = 1,
            maxErrorLines = 1,
        )
        assertEquals(1, oneSlot.size)
        assertTrue(oneSlot.single().contains("E p2p_core:"))
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

    /**
     * Владелец 2026-10-07: «нужно сделать в нашем красивом стиле». Окно
     * «Логи» подсвечивает разделы, ошибки и предупреждения — цвет выбирает
     * UI, а само правило живёт здесь, чтобы его проверял JVM-тест.
     */
    /**
     * Владелец 2026-10-07: «У нас нет такого что некоторые потеряшки живут в
     * очереди вечно?» — у незавершённых передач строку никто не удалял. Теперь
     * просроченное убирает FileTransferRouter.purgeExpiredTransfers, а отчёт
     * показывает и правило сроков, и что именно убрано.
     */
    @Test
    fun expiredTransfersAreSweptAndTheRuleIsVisible() {
        val quiet = DiagnosticsReport.render(
            facts = facts(),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(quiet.contains("срок хранения: тяжёлое 24 ч · текст и малое 7 сут"))
        assertTrue(quiet.contains("убрано просроченного=0 (копий на диске освобождено=0)"))

        val swept = DiagnosticsReport.render(
            facts = facts(
                counters = mapOf(
                    Counters.TRANSFER_EXPIRED_ROWS to 3L,
                    Counters.TRANSFER_EXPIRED_COPIES to 5L,
                ),
            ),
            journal = emptyList(),
            logcat = emptyList(),
        )
        assertTrue(swept.contains("убрано просроченного=3 (копий на диске освобождено=5)"))
    }

    @Test
    fun reportHighlightMarksSectionsFailuresAndWarnings() {
        assertEquals(DiagnosticsLineTone.SECTION, diagnosticsLineTone("[ядро]"))
        assertEquals(DiagnosticsLineTone.SECTION, diagnosticsLineTone("[обновления]"))
        assertEquals(
            DiagnosticsLineTone.SECTION,
            diagnosticsLineTone("[журнал] (последние события этой сессии)"),
        )
        assertEquals(DiagnosticsLineTone.BAD, diagnosticsLineTone("✖ Сеть: нет связи"))
        assertEquals(DiagnosticsLineTone.WARN, diagnosticsLineTone("! Передачи: 2 ошибки"))
        assertEquals(
            DiagnosticsLineTone.BAD,
            diagnosticsLineTone("12:41:58 ✖ net: MESH origin: cannot retain offline relay"),
        )
        assertEquals(
            DiagnosticsLineTone.WARN,
            diagnosticsLineTone("12:41:58 ! mqtt: соединение оборвалось"),
        )

        // Обычный текст остаётся обычным: порядок, значения и метки приватности
        // не должны превращаться в «ошибку» из-за одной квадратной скобки.
        assertNull(diagnosticsLineTone("✔ Сеть: на связи"))
        assertNull(diagnosticsLineTone("running=true"))
        assertNull(diagnosticsLineTone("  payload=[скрыто]"))
        assertNull(diagnosticsLineTone("payload=[contact]"))
        assertNull(diagnosticsLineTone("итого: записей 12, предупреждений 3, ошибок 1"))
        assertNull(diagnosticsLineTone(""))
    }

    @Test
    fun reportHighlightKeepsEveryCharacterOfTheShownText() {
        // Подсветка — только краски: разбиение на строки и обратная сборка
        // обязаны вернуть ровно тот же текст, иначе копия разойдётся с экраном.
        val report = DiagnosticsReport.render(
            facts = facts(relayQueueOwn = 1L, relayQueueForeign = 2L),
            journal = listOf(
                DiagnosticsJournalEntry(1_700_000_001_000L, DiagnosticsLevel.WARN, "net", "сеть пропала"),
            ),
            logcat = listOf("10-06 12:40:00.000  1234  1234 I ApuDiagnostics: F4 прямой канал"),
        )
        val rebuilt = buildString {
            report.split('\n').forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
        }
        assertEquals(report, rebuilt)
    }
    @Test fun storageAuditAppearsInTheSharedReportWithoutPayloads() {
        val report = DiagnosticsReport.render(facts().copy(relayStorage = RelayStorageDiagnostics(status = "ready")), emptyList(), emptyList())
        assertTrue(report.contains("[хранилище пересылки]"))
        assertTrue(report.contains("storage_audit=ready"))
    }

}
