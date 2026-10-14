package com.vladimir.messenger.data.diagnostics

/** Parsed only from bounded current-process logcat; never added to lifetime counters on refresh. */
data class RelayCleanupWindow(val expiredRemoved: Long = 0, val quarantineRemoved: Long = 0,
    val failures: Long = 0, val latestRamAckRemoved: Long? = null,
    val tombstonesRemoved: Long = 0, val compactions: Long = 0) {
    fun render(): String = "[очистка пересылки]\nsource=bounded_current_process_logcat; не полный журнал\n" +
        "durable_expired_removed_in_window=$expiredRemoved · quarantine_removed_in_window=$quarantineRemoved · cleanup_failures_in_window=$failures\n" +
        "old_markers_removed_in_window=$tombstonesRemoved · compactions_in_window=$compactions\n" +
        "ram_ack_removed_latest=${latestRamAckRemoved ?: -1} (последняя RAM-сводка, не число удалённых строк SQLite)\n" +
        "Если custody=ram-only, работа durable cleanup не подтверждена. Нулевые события в выборке не доказывают отсутствия очистки.\n"
    companion object {
        fun parse(lines: List<String>): RelayCleanupWindow {
            val purge = Regex("MESH (?:durable|gossip): purged ([0-9]+) expired (?:durable relay|relay record)\\(s\\) \\(\\+([0-9]+) quarantined\\)")
            val maintenance = Regex("MESH maintenance: expired=([0-9]+) quarantine=([0-9]+) tombstones=([0-9]+) compacted=(true|false)")
            var markers = 0L; var compactions = 0L
            val ack = Regex("снято-по-ACK=([0-9]+)")
            var removed = 0L; var quarantine = 0L; var failures = 0L; var latestAck: Long? = null
            for (line in lines.takeLast(20000)) {
                purge.find(line)?.let { removed += it.groupValues[1].toLongOrNull() ?: 0; quarantine += it.groupValues[2].toLongOrNull() ?: 0 }
                maintenance.find(line)?.let {
                    removed += it.groupValues[1].toLongOrNull() ?: 0
                    quarantine += it.groupValues[2].toLongOrNull() ?: 0
                    markers += it.groupValues[3].toLongOrNull() ?: 0
                    if (it.groupValues[4] == "true") compactions++
                }
                if (line.contains("MESH maintenance: deferred or interrupted")) failures++
                if (line.contains("MESH durable: purge_expired failed") || line.contains("MESH gossip: durable purge failed")) failures++
                if (line.contains("Relay-очередь:")) ack.find(line)?.let { latestAck = it.groupValues[1].toLongOrNull() }
            }
            return RelayCleanupWindow(removed, quarantine, failures, latestAck, markers, compactions)
        }
    }
}
