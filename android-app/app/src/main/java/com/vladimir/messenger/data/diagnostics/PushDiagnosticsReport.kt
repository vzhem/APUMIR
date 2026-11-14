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
object PushDiagnosticsReport {
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
        appendLine("токен=${if (push.tokenRecorded) "записан" else "нет"}")
        appendLine("маршрутов=${push.registeredRoutes ?: "не читается"}")
        appendLine("фаза=${push.phase.name.lowercase()}")
        appendLine("регистрация_назад=${agoOrNever(facts.createdAtMs, push.lastRegisterAtMs)}")
        appendLine("сигналов=${push.signalsReceived} · отклонено=${push.signalsRejected}")
        appendLine("сигнал_назад=${agoOrNever(facts.createdAtMs, push.lastSignalAtMs)}")
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
}
