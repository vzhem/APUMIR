package com.vladimir.messenger.data.diagnostics

/**
 * Строка присутствия для отчёта «Логи».
 *
 * Зачем отдельный файл: [DiagnosticsReport] — god-файл под храповиком строк
 * (`scripts/ci/check-god-files.py`), поэтому новый раздел живёт здесь.
 *
 * Что показывает и откуда берёт числа:
 *  * режим маяка — из `connected_peers` фактов: при большом рое ядро
 *    переходит на широковещательный маяк (`engine/core.rs SCALING_MIN_PEERS`);
 *  * подтверждённые прямые соседи — `liveDirectSwarmPeers` (подписанный APUX1,
 *    НЕ `connected_peers`: известный сосед не равен прямому каналу);
 *  * принятые и отброшенные наблюдения — счётчики сессии
 *    [com.vladimir.messenger.service.PresenceRecovery.observe]/`sweep`/`lost`.
 *    Они пишутся через `TransferDiagnostics.count` и приходят сюда в
 *    `facts.counters`: своих полей в `DiagnosticsFacts` у них намеренно нет,
 *    чтобы не раздувать god-файл.
 *
 * Чего здесь НЕТ и не выдумывается: возраста последнего `peer_discovered` и
 * счётчика «наш маяк ушёл» — эти значения ядро в факты не передаёт, и строка
 * их не придумывает.
 */
object PresenceReportStrings {

    /**
     * Порог широковещательного режима маяка. Должен совпадать с
     * `rust-core/src/engine/core.rs SCALING_MIN_PEERS`; проверяется
     * `scripts/ci/check-presence-recovery.py`.
     */
    const val SCALING_MIN_PEERS = 50L

    /** Молчание дольше этого срока снимает отметку «в сети» (PresenceRecovery.TTL_MS). */
    const val PRESENCE_TTL_SECONDS = 200L

    /** Как часто уборка проверяет молчание (PresenceRecovery.SWEEP_MS). */
    const val SWEEP_SECONDS = 60L

    // ── Ключи счётчиков присутствия ────────────────────────────────────
    // Живут в общем хранилище счётчиков сессии и попадают в отчёт через
    // facts.counters. В разделе [сессия] они не печатаются вторым разом:
    // DiagnosticsReport.appendSession исключает их по [counterKeys].

    /** Наблюдение принято: сосед помечен живым (или подтверждён живым). */
    const val PRESENCE_OBSERVED = "presence_observed"

    /** Из них сосед впервые вышел в сеть за эту сессию (тяжёлое обновление). */
    const val PRESENCE_BECAME_ONLINE = "presence_became_online"

    /** Отброшено: у события нет времени наблюдения — свежесть не доказать. */
    const val PRESENCE_DISCARDED_NO_TIMESTAMP = "presence_discarded_no_timestamp"

    /** Отброшено: событие старше TTL — оно не может включить отметку «в сети». */
    const val PRESENCE_DISCARDED_STALE = "presence_discarded_stale"

    /** Отброшено: время события сильно впереди телефона (разбег часов). */
    const val PRESENCE_DISCARDED_CLOCK_SKEW = "presence_discarded_clock_skew"

    /** Отброшено: повтор уже потерянного соседа не воскрешает его. */
    const val PRESENCE_DISCARDED_DUPLICATE = "presence_discarded_duplicate"

    /** Отброшено: список наблюдений полон, вытеснять было некого. */
    const val PRESENCE_DISCARDED_LIMIT = "presence_discarded_limit"

    /** Снято уборкой: сосед молчал дольше TTL и помечен не в сети. */
    const val PRESENCE_RETIRED_EXPIRED = "presence_retired_expired"

    /** Явный сигнал «сосед пропал» от ядра. */
    const val PRESENCE_LOST = "presence_lost"

    /**
     * Наблюдение свежее и прошло все проверки, но контакта для него нет:
     * ни по отпечатку, ни по id. Такому наблюдению некуда записаться, и
     * сосед остаётся «не в сети» в базе, хотя ядро его только что видела.
     *
     * Владелец 2026-11-15: «все телефоны включены, но показывает, что все
     * друзья вышли из сети». Это число отличает «наблюдений не приходит»
     * (ядро молчит) от «приходят, но записывать некуда» (не тот контакт) —
     * у этих двух диагнозов разное лечение.
     */
    const val PRESENCE_UNKNOWN_CONTACT = "presence_unknown_contact"

    /**
     * Сколько соседей телефон считает в сети ПРЯМО СЕЙЧАС (стрелка, не счётчик).
     *
     * Зачем. Владелец 2026-11-17: «не показывает, что друзья на связи», хотя
     * сообщение дошло. По одним счётчикам наблюдений нельзя отличить «кадры
     * приходят, а в интерфейсе пусто» от «кадров нет вовсе» — это разные
     * причины. Здесь видно и число, и когда была последняя уборка.
     */
    const val PRESENCE_ONLINE_NOW = "presence_online_now"

    /** Сколько соседей телефон вообще отслеживает (память PresenceRecovery). */
    const val PRESENCE_TRACKED_PEERS = "presence_tracked_peers"

    /** Когда проходила последняя уборка присутствия (мс эпохи, 0 — не было). */
    const val PRESENCE_LAST_SWEEP_AT_MS = "presence_last_sweep_at_ms"

    /** Все ключи раздела: чтобы [сессия] не печатала их сырым видом второй раз. */
    val counterKeys: Set<String> = setOf(
        PRESENCE_OBSERVED,
        PRESENCE_BECAME_ONLINE,
        PRESENCE_DISCARDED_NO_TIMESTAMP,
        PRESENCE_DISCARDED_STALE,
        PRESENCE_DISCARDED_CLOCK_SKEW,
        PRESENCE_DISCARDED_DUPLICATE,
        PRESENCE_DISCARDED_LIMIT,
        PRESENCE_RETIRED_EXPIRED,
        PRESENCE_LOST,
        PRESENCE_UNKNOWN_CONTACT,
        PRESENCE_ONLINE_NOW,
        PRESENCE_TRACKED_PEERS,
        PRESENCE_LAST_SWEEP_AT_MS,
    )

    private fun Map<String, Long>.value(key: String): Long = this[key] ?: 0L

    /**
     * Строки раздела `[присутствие]` без завершающего перевода строки:
     * [DiagnosticsReport] добавляет его сам.
     *
     * @param facts факты отчёта, собранные на телефоне.
     */
    fun appendPresenceLine(facts: DiagnosticsFacts): String {
        val known = facts.connectedPeers
        val mode = if (known >= SCALING_MIN_PEERS) "широкий" else "обычный"
        val counters = facts.counters
        return buildString {
            append("[присутствие]")
            append("\n  режим маяка: $mode (известных соседей $known, широкий с $SCALING_MIN_PEERS)")
            val direct = facts.liveDirectSwarmPeers
            if (direct != null) {
                append(" · подтверждённых прямых соседей $direct")
            }
            append("\n  наблюдений принято: ${counters.value(PRESENCE_OBSERVED)}")
            append(" (впервые вышли в сеть: ${counters.value(PRESENCE_BECAME_ONLINE)})")
            val discarded = counters.value(PRESENCE_DISCARDED_NO_TIMESTAMP) +
                counters.value(PRESENCE_DISCARDED_STALE) +
                counters.value(PRESENCE_DISCARDED_CLOCK_SKEW) +
                counters.value(PRESENCE_DISCARDED_DUPLICATE) +
                counters.value(PRESENCE_DISCARDED_LIMIT)
            append("\n  отброшено: $discarded")
            append(" (без времени ${counters.value(PRESENCE_DISCARDED_NO_TIMESTAMP)}")
            append(", устаревших ${counters.value(PRESENCE_DISCARDED_STALE)}")
            append(", время впереди ${counters.value(PRESENCE_DISCARDED_CLOCK_SKEW)}")
            append(", повторов потерянного ${counters.value(PRESENCE_DISCARDED_DUPLICATE)}")
            append(", предел списка ${counters.value(PRESENCE_DISCARDED_LIMIT)})")
            val unknown = counters.value(PRESENCE_UNKNOWN_CONTACT)
            if (unknown > 0L) {
                // Не ноль: значит часть соседей видна ядром, но не находится
                // среди контактов — и отметка «в сети» такому не ставится.
                append("\n  ВНИМАНИЕ: наблюдений без контакта в телефоне — $unknown " +
                    "(сосед виден ядром, но не совпал ни с одним контактом: " +
                    "отметка «в сети» такому не ставится)")
            }
            // Текущее состояние, а не накопленное: сколько соседей телефон
            // считает в сети прямо сейчас и когда последний раз убирал.
            append("\n  сейчас «в сети»: ${counters.value(PRESENCE_ONLINE_NOW)} " +
                "из ${counters.value(PRESENCE_TRACKED_PEERS)} отслеживаемых")
            val sweptAt = counters.value(PRESENCE_LAST_SWEEP_AT_MS)
            val createdAt = facts.createdAtMs
            if (sweptAt > 0L && createdAt >= sweptAt) {
                append(" · уборка ${(createdAt - sweptAt) / 1000} с назад")
            } else if (sweptAt <= 0L) {
                append(" · уборки ещё не было")
            }
            append(" (каждые ${SWEEP_SECONDS} с, порог $PRESENCE_TTL_SECONDS с)")
            append("\n  снято: молчанием свыше $PRESENCE_TTL_SECONDS с — ")
            append("${counters.value(PRESENCE_RETIRED_EXPIRED)}")
            append(", явным «пропал» — ${counters.value(PRESENCE_LOST)}")
        }
    }
}
