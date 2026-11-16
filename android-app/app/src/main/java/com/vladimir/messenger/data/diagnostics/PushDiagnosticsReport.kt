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
enum class PushWakeOutcome {
    NONE, ACCEPTED, WITHIN_BUDGET, NOT_REGISTERED, UNAVAILABLE, SKIPPED,
    /**
     * Собеседник ещё не присылал свою эпоху, поэтому адресовать пробуждение
     * нечем. Отдельно от [NOT_REGISTERED]: там не готова своя регистрация,
     * здесь — нечего адресовать на его стороне.
     */
    NO_PEER_EPOCH,
}

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
    /**
     * Why the last registration pass ended where it did.
     *
     * Separate from [phase] on purpose: `failed` used to name a service
     * refusal, an unreachable network and an unprepared exchange key at once,
     * so the hint below it listed all three and answered none. This is the one
     * line that says which of them actually happened.
     */
    val registrationReason: PushRegisterReason = PushRegisterReason.NONE,
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
    /**
     * Сколько эпох собеседников лежит в защищённом журнале; null — не читается.
     *
     * Это вторая половина адреса: без эпохи собеседника пробуждение некуда
     * отправить, и ноль объясняет «зарегистрирован, а сигналов нет» лучше
     * любого предположения.
     */
    val peerEpochs: Int? = null,
    /** Когда последний раз проверяли /health; 0 — не проверяли. */
    val endpointCheckedAtMs: Long = 0L,
    /** Когда служба последний раз ПРИНИМАЛА запрос на пробуждение; 0 — не было. */
    val lastWakeAcceptedAtMs: Long = 0L,
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
        PushPhase.FAILED -> if (push.registrationReason == PushRegisterReason.NONE) appendLine(
            "  ↳ регистрация не завершена; точный шаг не записан",
        )
        else -> Unit
    }
    appendLine("регистрация_назад=${agoOrNever(facts.createdAtMs, push.lastRegisterAtMs)}")
    // Причина печатается рядом с фазой, а не вместо неё: фаза — что вышло, причина — на каком шаге.
    // Раньше "failed" одновременно обозначал и отказ службы, и недоступную сеть, и неготовый ключ обмена,
    // поэтому подсказка выше перечисляла все три сразу — то есть ни одну.
    if (push.registrationReason != PushRegisterReason.NONE) {
        appendLine(
            "причина=${push.registrationReason.name.lowercase()} " +
                "(${push.registrationReason.hint})",
        )
    }
    appendLine("сигналов=${push.signalsReceived} · отклонено=${push.signalsRejected}")
    appendLine("сигнал_назад=${agoOrNever(facts.createdAtMs, push.lastSignalAtMs)}")
    // «Готов принимать» и «меня никто не будил» — разные состояния, и раньше
    // они выглядели одинаково: ноль сигналов при успешной регистрации.
    if (push.phase == PushPhase.REGISTERED && push.signalsReceived == 0L) appendLine(
        "  ↳ этот телефон готов принимать, но ни один сигнал не приходил: его ещё никто не будил",
    )
    // Прямой ответ на вопрос «разбудили или нет»: раньше этот факт нигде не
    // печатался, и по отчёту нельзя было отличить «сигнала не было» от
    // «сигнал пришёл, но разбудить не удалось».
    appendLine(
        "разбудили_на_месте=" + when (push.inProcessWakeAtMs) {
            0L -> "нет подтверждённого пробуждения на месте"
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
    // Отдельно от «пробуждения»: там попытки, здесь — сколько из них служба
    // приняла. Без этой строки «30 попыток» читается и как «30 запросов
    // ушло», и как «30 раз не смогли даже начать».
    appendLine(
        "принято_службой=${agoOrNever(facts.createdAtMs, push.lastWakeAcceptedAtMs)} " +
            "(принятие заявки — не доставка)",
    )
    val serviceState = when (push.endpointReachable) {
        true -> "доступна (/health ответил)"
        false -> "недоступна (сеть или фильтрация)"
        null -> "не проверялась"
    }
    // Без отметки времени старая неудачная проба читается как текущее состояние
    // службы и перечёркивает успешную регистрацию, которая прошла позже неё.
    val probedAt = if (push.endpointCheckedAtMs > 0L) {
        " · проверено ${agoOrNever(facts.createdAtMs, push.endpointCheckedAtMs)}"
    } else {
        ""
    }
    appendLine("служба=$serviceState$probedAt")
    appendLine("эпох_собеседников=${push.peerEpochs ?: "не читается"}")
    if (push.peerEpochs == 0) appendLine(
        "  ↳ ни один собеседник ещё не присылал свою эпоху: пробуждение некуда отправить",
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
