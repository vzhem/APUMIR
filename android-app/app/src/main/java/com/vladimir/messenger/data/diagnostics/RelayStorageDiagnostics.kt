package com.vladimir.messenger.data.diagnostics

/** Metadata only. No paths, identities, packet IDs, payloads or cryptographic material. */
data class RelayStorageDiagnostics(
    val stores: List<RelayStoreDiagnostic> = emptyList(),
    val quotaBytes: Long? = null,
    val elapsedMs: Long = 0L,
    val status: String = "not_collected",
) {
    fun render(): String = buildString {
        appendLine("[хранилище пересылки]")
        appendLine("storage_audit=${token(status)} · elapsed_ms=$elapsedMs")
        appendLine("Размер файлов SQLite/WAL/SHM — не объём ожидающих сообщений.")
        appendLine("Квота файлового буфера не ограничивает размер relay SQLite.")
        quotaBytes?.let { appendLine("file_buffer_quota_bytes=$it") }
        appendLine("Метки peer-N назначены заново для этого отчёта; между телефонами их не сопоставлять. self — личность из настроек.")
        appendLine("У encrypted/quarantine отправитель и тип тела скрыты; ключи и тела не читаются.")
        appendLine("Это ограниченная выборка, не полный список; повтор ID не доказывает повтор тела.")
        appendLine("Снимок приблизительный: ядро может записывать параллельно; WAL может содержать повторные версии страниц.")
        for (store in stores) {
            val name = if (store.legacy) "legacy" else "current"
            appendLine("store=$name · status=${token(store.status)}")
            appendLine("db_bytes=${store.dbBytes} · wal_bytes=${store.walBytes} · shm_bytes=${store.shmBytes}")
            appendLine("page_size=${store.pageSize ?: -1L} · page_count=${store.pages ?: -1L} · freelist_count=${store.freePages ?: -1L}")
            val reusable = store.pageSize?.let { size -> store.freePages?.let { size * it } }
            appendLine("reusable_page_bytes=${reusable ?: -1L} (уже внутри БД, не прибавляются к её размеру)")
            appendLine("tombstones=${store.tombstones ?: -1L} · unknown_tables=${store.unknownTables ?: -1}")
            for (table in store.tables) {
                appendLine("table=${table.kind.code} · status=${token(table.status)} · rows=${table.totalRows ?: -1L}")
                appendLine("sampled=${table.sampled} · limited=${table.limited} · sampled_bytes=${table.bytes} · largest_sampled_record_bytes=${table.largestBytes} · expired_sampled=${table.expired} · repeated_ids_sampled=${table.repeatedIds}")
                val shown = table.groups.sortedByDescending { it.bytes }.take(MAX_GROUPS)
                for (group in shown) {
                    appendLine("  from=${peer(group.sender)} -> to=${peer(group.recipient)} · rows=${group.rows} · bytes=${group.bytes} · expired=${group.expired} · oldest_age_ms=${group.oldestAgeMs ?: -1L}")
                }
                appendLine("groups_not_shown=${(table.groups.size - shown.size).coerceAtLeast(0)}")
            }
        }
        appendLine("-1 означает: значение недоступно, не ноль. Проверка ничего не удаляет и не сжимает.")
    }

    private fun token(value: String): String = value.takeIf { it in STATUSES } ?: "unavailable"
    private fun peer(value: String): String = value.takeIf {
        it in setOf("self", "encrypted", "unknown") || it.matches(Regex("peer-[1-9][0-9]{0,3}"))
    } ?: "unknown"

    companion object {
        const val MAX_GROUPS = 20
        private val STATUSES = setOf("ready", "missing", "unavailable", "timeout", "partial", "not_collected")
    }
}

enum class RelayTableKind(val code: String) { LEGACY("legacy"), ENCRYPTED("encrypted"), QUARANTINE("quarantine") }

data class RelayStoreDiagnostic(
    val legacy: Boolean = false,
    val status: String = "unavailable",
    val dbBytes: Long = 0L,
    val walBytes: Long = 0L,
    val shmBytes: Long = 0L,
    val pageSize: Long? = null,
    val pages: Long? = null,
    val freePages: Long? = null,
    val tombstones: Long? = null,
    val unknownTables: Int? = null,
    val tables: List<RelayTableDiagnostic> = emptyList(),
)

data class RelayTableDiagnostic(
    val kind: RelayTableKind,
    val totalRows: Long? = null,
    val status: String = "ready",
    val sampled: Int = 0,
    val limited: Boolean = false,
    val bytes: Long = 0L,
    val largestBytes: Long = 0L,
    val expired: Int = 0,
    val repeatedIds: Int = 0,
    val groups: List<RelayRouteDiagnostic> = emptyList(),
)

data class RelayRouteDiagnostic(
    val sender: String,
    val recipient: String,
    val rows: Int,
    val bytes: Long,
    val expired: Int,
    val oldestAgeMs: Long?,
)

/** Raw IDs live ONLY in this short-lived accumulator, never in the returned snapshot. */
internal class RelayStorageAccumulator(private val ownId: String, private val nowMs: Long) {
    private val aliases = LinkedHashMap<String, String>()
    private val seenIds = HashSet<String>()

    private fun alias(id: String?): String = when {
        id.isNullOrBlank() -> "unknown"
        ownId.isNotBlank() && id == ownId -> "self"
        else -> aliases.getOrPut(id) { "peer-${aliases.size + 1}" }
    }

    fun table(kind: RelayTableKind) = Table(kind)

    inner class Table(private val kind: RelayTableKind) {
        private val routes = LinkedHashMap<Pair<String, String>, MutableList<RelayRouteDiagnostic>>()
        private var sampled = 0
        private var bytes = 0L
        private var largest = 0L
        private var expired = 0
        private var repeatedIds = 0

        fun add(id: String?, recipient: String?, sender: String?, size: Long, createdAtMs: Long?, expiresAtMs: Long?) {
            val safeSize = size.coerceAtLeast(0L)
            val isExpired = expiresAtMs != null && expiresAtMs > 0 && expiresAtMs <= nowMs
            val age = createdAtMs?.takeIf { it in 1..nowMs }?.let { nowMs - it }
            sampled++
            bytes += safeSize
            largest = maxOf(largest, safeSize)
            if (isExpired) expired++
            if (!id.isNullOrEmpty() && !seenIds.add(id)) repeatedIds++
            val from = if (kind == RelayTableKind.LEGACY) alias(sender) else "encrypted"
            val destination = alias(recipient)
            val key = from to destination
            routes.getOrPut(key) { mutableListOf() }.add(
                RelayRouteDiagnostic(from, destination, 1, safeSize, if (isExpired) 1 else 0, age),
            )
        }

        fun finish(totalRows: Long?, limited: Boolean, status: String = "ready"): RelayTableDiagnostic = RelayTableDiagnostic(
            kind, totalRows, status, sampled, limited, bytes, largest, expired, repeatedIds,
            routes.map { (key, entries) ->
                RelayRouteDiagnostic(key.first, key.second, entries.sumOf { it.rows }, entries.sumOf { it.bytes },
                    entries.sumOf { it.expired }, entries.mapNotNull { it.oldestAgeMs }.maxOrNull())
            },
        )
    }
}
