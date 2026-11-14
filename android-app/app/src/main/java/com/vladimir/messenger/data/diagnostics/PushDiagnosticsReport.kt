package com.vladimir.messenger.data.diagnostics

/**
 * Раздел [push] отчёта «Логи». Вынесен рядом с `DiagnosticsReport.kt`, чтобы не
 * растить god-файл (контракт `check-god-files.py`).
 *
 * Зачем раздел нужен: провал теста на двух телефонах (v11.74.512) дал отчёт, в
 * котором о push не было ни строки, поэтому «приём выключен» выглядел как
 * «тихое приложение». Раздел показывает, на каком из трёх шагов оборвалось:
 * приём включён → маршрут зарегистрирован → сигнал пришёл и был обработан.
 *
 * Приватность: счётчики, перечисления, отметки времени — без токена,
 * отпечатка, маршрута и идентификаторов.
 *
 * Файл чистый (без Android), поэтому компилируется тем же JVM-прогоном, что и
 * `DiagnosticsReport.kt`, и раздел проверяется до выпуска, а не на телефоне.
 */
/**
 * Грубая фаза регистрации в push-службе: только эти значения, без адресов и кодов.
 */
enum class PushPhase {
    UNKNOWN, DISABLED, NOT_CONFIGURED, NO_PLAY_SERVICES, REGISTERED, PARTIAL, FAILED,
    /** Регистрировать было некого: ни одного собеседника с закреплением. */
    NO_TARGETS,
}

/** Итог последней попытки разбудить собеседника. */
enum class PushWakeOutcome { NONE, ACCEPTED, WITHIN_BUDGET, NOT_REGISTERED, UNAVAILABLE, SKIPPED }

/**
 * Снимок состояния push для отчёта. Чистый JVM-тип: ни Android, ни Firebase, ни
 * секретов. Значения собирает `PushDiagnostics` (Android), печатает [appendPush].
 */
data class PushSnapshot(
    val gateOpen: Boolean,
    /** Принимает ли сборка входящие сигналы вообще. */
    val receiveEnabled: Boolean,
    /** Записан ли отпечаток токена (сам токен сюда не попадает). */
    val tokenRecorded: Boolean,
    /**
     * Задан ли адрес службы в этой сборке.
     *
     * Факт сборки, а не состояние: если он ложен, гейт открыт быть не может, и
     * никакая регистрация не состоится. Отдельная строка нужна потому, что
     * «гейт закрыт» и «адрес не задан» — разные причины, и обе выглядят как
     * пустой раздел.
     */
    val endpointConfigured: Boolean,
    /** Сколько маршрутов в индексе; null — индекс не читается. */
    val registeredRoutes: Int?,
    val phase: PushPhase,
    val lastRegisterAtMs: Long,
    val signalsReceived: Long,
    val signalsRejected: Long,
    val lastSignalAtMs: Long,
    val wakeAttempts: Long,
    val lastWake: PushWakeOutcome,
    val lastWakeAtMs: Long,
    /**
     * Когда входящий сигнал нашёл ЖИВОЙ процесс и ядро подтолкнули на месте.
     *
     * Отдельно от [lastWakeAtMs] намеренно: «пробуждение» в этом разделе — это
     * запрос к службе ради СОБЕСЕДНИКА (исходящее), а здесь — вход на своём
     * телефоне. Ноль значит «не случалось».
     */
    val inProcessWakeAtMs: Long = 0L,
    /** Когда вместо этого пришлось ставить фоновую задачу (ядра не было). */
    val workerWakeAtMs: Long = 0L,
    /**
     * Код последней неудачи с токеном провайдера, если она была.
     *
     * Только фиксированное имя (`SERVICE_NOT_AVAILABLE`, `unknown`, …): причина
     * в виде текста исключения унесла бы в отчёт идентификаторы проекта.
     */
    val tokenError: String? = null,
    val inboxPending: String,
    /** null — проверка доступности службы не проводилась. */
    val endpointReachable: Boolean?,
    val lastError: String,
)

/**
 * `DiagnosticsFacts.push`: единственный раздел, где видно, дошёл ли сигнал.
 *
 * `0 сигналов` при открытом гейте и `off` приёме — это и есть диагноз.
 */
internal fun StringBuilder.appendPush(facts: DiagnosticsFacts) {
    val push = facts.push
    appendLine("[push]")
    if (push == null) {
        appendLine("состояние=нет данных: раздел не собран в этом отчёте")
        appendLine()
        return
    }
    appendLine("гейт=${if (push.gateOpen) "открыт" else "закрыт"}")
    appendLine("приём=${if (push.receiveEnabled) "on" else "off"}")
    appendLine("адрес_службы=${if (push.endpointConfigured) "задан" else "НЕ ЗАДАН"}")
    appendLine("токен=${if (push.tokenRecorded) "записан" else "нет"}")
    if (!push.tokenRecorded && !push.tokenError.isNullOrBlank()) {
        appendLine("причина_отказа_токена=${push.tokenError}")
    }
    appendLine("маршрутов=${push.registeredRoutes ?: "не читается"}")
    appendLine("фаза=${push.phase.name.lowercase()}")
    // Одна подсказка на самую частую путаницу: «failed» без пояснения читается
    // и как «сервер отказал», и как «регистрировать было некого».
    when (push.phase) {
        PushPhase.NO_TARGETS -> appendLine(
            "  ↳ регистрировать некого: ни у одного собеседника нет закрепления",
        )
        PushPhase.FAILED -> appendLine(
            "  ↳ сервер не принял ни одного маршрута: сеть, отказ или неготовый ключ обмена",
        )
        else -> Unit
    }
    appendLine("регистрация_назад=${agoOrNever(facts.createdAtMs, push.lastRegisterAtMs)}")
    appendLine("сигналов=${push.signalsReceived} · отклонено=${push.signalsRejected}")
    appendLine("сигнал_назад=${agoOrNever(facts.createdAtMs, push.lastSignalAtMs)}")
    // Прямой ответ на вопрос «разбудили или нет»: раньше этот факт нигде не
    // печатался, и по отчёту нельзя было отличить «сигнала не было» от
    // «сигнал пришёл, но разбудить не удалось».
    appendLine(
        "разбудили_на_месте=" + when (push.inProcessWakeAtMs) {
            0L -> "нет (ядра не было — ставили фоновую задачу)"
            else -> "да (${agoOrNever(facts.createdAtMs, push.inProcessWakeAtMs)})"
        },
    )
    if (push.workerWakeAtMs > 0L) {
        appendLine("фоновая_задача_пробуждения=${agoOrNever(facts.createdAtMs, push.workerWakeAtMs)} назад")
    }
    appendLine(
        "пробуждения=${push.wakeAttempts} · последнее=${push.lastWake.name.lowercase()} " +
            "(${agoOrNever(facts.createdAtMs, push.lastWakeAtMs)})",
    )
    appendLine(
        "служба=" + when (push.endpointReachable) {
            true -> "доступна (/health ответил)"
            false -> "недоступна (сеть или фильтрация)"
            null -> "не проверялась"
        },
    )
    appendLine("ящик_пробуждения=${push.inboxPending}")
    appendLine("последняя_ошибка=${push.lastError}")
    appendLine()
}

/** «5 мин назад» или «не было»: отсутствие события — тоже факт. */
internal fun agoOrNever(nowMs: Long, atMs: Long): String =
    if (atMs <= 0L) "не было" else "${DiagnosticsReport.formatDuration((nowMs - atMs).coerceAtLeast(0L))} назад"

/**
 * «Своих снято-по-ACK=N» из самой свежей сводки очереди; null — сводки нет.
 *
 * Зеркалит `numericValueAfter` отчёта, потому что это поле читается там же, где
 * печатается, а сам парсер живёт в отдельном файле из-за потолка god-файла.
 */
/** Порог «старейшая своя запись старая»: сутки. */
private const val SLOW_DRAIN_SECS = 24L * 60L * 60L

internal fun relayQueueOwnDrained(lines: List<String>): Long? {
    for (line in lines.asReversed()) {
        if (!line.contains("Relay-очередь: своих=")) continue
        val marker = "снято-по-ACK="
        val at = line.indexOf(marker).takeIf { it >= 0 } ?: continue
        val digits = line.substring(at + marker.length).takeWhile { it.isDigit() }
        digits.toLongOrNull()?.let { return it }
    }
    return null
}

/**
 * Одна строка о разгрузке своей очереди ядра — рядом с push, потому что это тот
 * же вопрос «дойдёт ли до спящего телефона».
 *
 * «Своих=предел» при «снято=0» означает застрявшую очередь, а не медленную.
 */
internal fun StringBuilder.appendOwnQueueDrain(facts: DiagnosticsFacts) {
    val drained = facts.relayQueueOwnDrained ?: return
    val oldest = facts.relayQueueOwnOldestSecs
    // Три разных состояния, которые раньше читались одинаково:
    //  * снято=0 при пределе — не разгружается ни одна запись;
    //  * снято>0, но старейшее сутками — разгружается медленнее, чем прибывает;
    //  * снято>0 и старейшее свежее — очередь живая.
    val stalled = facts.relayQueueOwn != null && facts.relayQueueOwn == facts.relayQueueOwnLimit && drained == 0L
    val slowerThanArrival = !stalled && oldest != null && oldest >= SLOW_DRAIN_SECS
    appendLine(
        "очередь ядра: снято по подтверждению=$drained " +
            when {
                stalled -> "(не разгружается: собеседник не подтверждает ни одной записи)"
                slowerThanArrival ->
                    "(разгружается медленнее, чем прибывает: старейшая своя запись " +
                        "${DiagnosticsReport.formatDuration(oldest * 1000L)})"
                else -> "(подтверждение снимает запись; это не прочтение)"
            },
    )
}
