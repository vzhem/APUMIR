package com.vladimir.messenger.data.diagnostics

import com.vladimir.messenger.service.OutgoingPiggybackPolicy

/**
 * Раздел `[расход энергии по узлам]` для окна «Логи» — без зависимостей от
 * `android.*`, чтобы форматирование и разбор сводки ядра проверялись чистыми
 * JVM-тестами.
 *
 * Показывает на человеческом языке и в машинных полях, **на что именно**
 * тратятся CPU, радиомодуль (QUIC / MQTT / STUN / mDNS / PEX) и попутное
 * 15-секундное окно после отправки сообщения.
 */
data class PowerActivitySnapshot(
    val nativePresent: Boolean = false,
    val nativeWindowActive: Boolean = false,
    val nativeWindows: Long = 0,
    val stunProbes: Long = 0,
    val mdnsScans: Long = 0,
    val mdnsPublishes: Long = 0,
    val pexRounds: Long = 0,
    val directPresenceRounds: Long = 0,
    val mqttPresencePublishes: Long = 0,
    val mqttGossipPublishes: Long = 0,
    val mqttRelayPublishes: Long = 0,
    val mqttInboundEvents: Long = 0,
    val quicConnectsOut: Long = 0,
    val quicAcceptsIn: Long = 0,
    val quicFramesOut: Long = 0,
    val quicFramesIn: Long = 0,
    val quicIdleClosed: Long = 0,
) {
    fun render(
        piggyback: OutgoingPiggybackPolicy.Stats,
        operations: List<OperationHealth>,
        chargeCounterUah: Long? = null,
        currentNowUa: Long? = null,
        rxBytesUid: Long? = null,
        txBytesUid: Long? = null,
    ): String = buildString {
        appendLine("[расход энергии по узлам]")
        if (chargeCounterUah != null || currentNowUa != null || rxBytesUid != null || txBytesUid != null) {
            appendLine(
                "battery_charge_uah=${chargeCounterUah ?: -1} · battery_current_now_ua=${currentNowUa ?: -1} · " +
                    "app_uid_rx_bytes=${rxBytesUid ?: -1} · app_uid_tx_bytes=${txBytesUid ?: -1}",
            )
            appendLine("Счётчики Android с момента загрузки телефона (ток со знаком прошивки); не точный ваттметр.")
        }
        appendLine(
            "piggyback_window_15s: active=${piggyback.active} · remaining_ms=${piggyback.remainingMs} · " +
                "opened=${piggyback.windowsOpened} · flushes=${piggyback.flushesScheduled} · " +
                "last_flush_ago_ms=${piggyback.lastFlushAgoMs} · dirty=${piggyback.dirty}",
        )
        if (nativePresent) {
            appendLine(
                "радио ядра (с запуска движка): " +
                    "окна_отправки=$nativeWindows · STUN=$stunProbes · mDNS_поиск=$mdnsScans · mDNS_анонс=$mdnsPublishes · " +
                    "PEX=$pexRounds · прямой_пульс=$directPresenceRounds",
            )
            appendLine(
                "брокер и QUIC (с запуска движка): " +
                    "MQTT_маяк=$mqttPresencePublishes · MQTT_слухи=$mqttGossipPublishes · MQTT_пересылка_исх=$mqttRelayPublishes · " +
                    "MQTT_входящие=$mqttInboundEvents · QUIC_исх_соед=$quicConnectsOut · QUIC_вх_соед=$quicAcceptsIn · " +
                    "QUIC_исх_кадров=$quicFramesOut · QUIC_вх_кадров=$quicFramesIn · QUIC_закрыто_по_простою=$quicIdleClosed",
            )
        } else {
            appendLine("радио ядра: сводка APU_POWER ещё не попала в выборку logcat (движок только запущен или в режиме зеркала).")
        }
        val topOps = operations
            .filter { it.completed > 0 || it.inFlight > 0 }
            .sortedByDescending { it.totalMs }
            .take(6)
        if (topOps.isNotEmpty()) {
            val summary = topOps.joinToString(", ") { op ->
                "${humanOpName(op.operation)}=${op.totalMs}мс (${op.completed} раз, макс ${op.maxMs}мс)"
            }
            appendLine("топ фоновых задач (стеновое время с IO): $summary")
        }
        appendLine("В тихом покое растут только MQTT_маяк (~1/мин в малой сети) и входящие кадры; STUN/PEX/прямой_пульс/слухи должны расти только при смене сети или в 15-секундном окне отправки.")
    }

    companion object {
        private const val MARKER = "APU_POWER:"
        private val fieldRegex = Regex("""([a-z_]+)=([A-Za-z0-9]+)""")

        fun parseLatest(lines: List<String>): PowerActivitySnapshot {
            for (line in lines.asReversed()) {
                val idx = line.indexOf(MARKER)
                if (idx < 0) continue
                val map = HashMap<String, String>()
                for (match in fieldRegex.findAll(line.substring(idx + MARKER.length))) {
                    map[match.groupValues[1]] = match.groupValues[2]
                }
                if (map.isEmpty()) continue
                fun num(key: String): Long = map[key]?.toLongOrNull() ?: 0L
                return PowerActivitySnapshot(
                    nativePresent = true,
                    nativeWindowActive = map["window_active"] == "true",
                    nativeWindows = num("windows"),
                    stunProbes = num("stun"),
                    mdnsScans = num("mdns_scan"),
                    mdnsPublishes = num("mdns_pub"),
                    pexRounds = num("pex"),
                    directPresenceRounds = num("ppres"),
                    mqttPresencePublishes = num("mqtt_pres"),
                    mqttGossipPublishes = num("mqtt_gossip"),
                    mqttRelayPublishes = num("mqtt_relay_out"),
                    mqttInboundEvents = num("mqtt_in"),
                    quicConnectsOut = num("quic_conn_out"),
                    quicAcceptsIn = num("quic_conn_in"),
                    quicFramesOut = num("quic_out"),
                    quicFramesIn = num("quic_in"),
                    quicIdleClosed = num("quic_idle_closed"),
                )
            }
            return PowerActivitySnapshot()
        }

        private fun humanOpName(op: DiagnosticOperation): String = when (op) {
            DiagnosticOperation.CORE_EVENTS -> "события_ядра"
            DiagnosticOperation.FILE_PUMP -> "файлы"
            DiagnosticOperation.GROUP_FILE_PUMP -> "файлы_групп"
            DiagnosticOperation.APK_PUMP -> "раздача_APK"
            DiagnosticOperation.DIRECT_DELETE_PUMP -> "удаления_личн"
            DiagnosticOperation.GROUP_DELETE_PUMP -> "удаления_групп"
            DiagnosticOperation.CHAT_RETRY_PUMP -> "повтор_чата"
            DiagnosticOperation.STORY_PUMP -> "истории"
            DiagnosticOperation.REPORT_FACTS -> "сбор_отчёта"
            DiagnosticOperation.LOGCAT_READ -> "чтение_logcat"
            DiagnosticOperation.STORAGE_AUDIT -> "аудит_SQLite"
            DiagnosticOperation.KEY_MODE_PROBE -> "проверка_ключей"
            DiagnosticOperation.FEATURE_PACK_PUMP -> "пакеты_функций"
            DiagnosticOperation.OUTBOX_PUMP -> "очередь_исходящих"
            DiagnosticOperation.UNCONFIRMED_OUTBOX_PUMP -> "ждущие_ACK"
            DiagnosticOperation.CUSTODY_RECONCILE -> "сверка_кастодии"
            DiagnosticOperation.DEFERRED_DELIVERY_PUMP -> "отложенные_письма"
            DiagnosticOperation.PIGGYBACK_FLUSH -> "попутное_окно_15с"
        }
    }
}
