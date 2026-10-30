package com.vladimir.messenger.service

/**
 * Замок кнопки «Скачать обновление».
 *
 * Владелец 2026-10-29: «люди жмут кнопку и у них качается по два-три
 * обновления». Причина не в сети: в окне обновления кнопка никогда не
 * блокировалась (`isDownloading = false` при любом состоянии), а каждый тап
 * ставил в `DownloadManager` новую задачу. Сторож был только внутри одного
 * экрана настроек (`SettingsViewModel` помнил свой `downloadId`), поэтому окно
 * обновления и второй экран его не видели.
 *
 * Здесь правило одно на всё приложение:
 *
 *  - пока загрузка той же версии не завершена — второй запрос не начинается;
 *  - в любом случае после начала загрузки кнопка молчит [COOLDOWN_MS] секунд:
 *    человек успевает увидеть «Скачивание началось» и не жмёт ещё раз;
 *  - другая версия (вышел новый релиз) не заперта — там решение принимает
 *    человек, а не таймер;
 *  - «загрузка идёт» ставится по факту запроса к `DownloadManager`, а снимается
 *    завершением, отменой или истечением [IN_FLIGHT_TTL_MS]: забытая отметка не
 *    должна навсегда закрыть кнопку.
 *
 * Android-зависимостей нет: время передаётся снаружи, поэтому правило
 * проверяется обычными JVM-тестами (`UpdateDownloadGateTest`).
 */
object UpdateDownloadGate {

    /** Сколько секунд кнопка не отправляет повторный запрос. Требование владельца. */
    const val COOLDOWN_MS = 30_000L

    /**
     * Потолок «загрузка идёт». `DownloadManager` может умереть вместе с
     * приложением и не прислать завершение — отметка обязана истечь сама.
     */
    const val IN_FLIGHT_TTL_MS = 15 * 60_000L

    /**
     * Сколько ждём подтверждения от `DownloadManager`, что загрузка реально
     * появилась. Если её нет и это время прошло — отметка была забыта
     * (менеджер не принял задачу), и человека держит уже только пауза нажатия.
     */
    const val IN_FLIGHT_GRACE_MS = 5_000L

    /** Что делать с нажатием. */
    sealed interface Decision {
        /** Можно начинать загрузку. */
        data object Start : Decision

        /** Эта версия уже качается: второй файл не нужен. */
        data class InFlight(val version: String) : Decision

        /** Только что начинали: просим подождать остаток секунд. */
        data class Cooldown(val version: String, val remainingMs: Long) : Decision
    }

    private class Attempt(val version: String, val atMs: Long, val inFlight: Boolean)

    private var last: Attempt? = null

    /**
     * Спросить разрешение на загрузку.
     *
     * @param version нормализованная версия релиза
     * @param nowMs текущее время, мс
     * @param hasActiveDownload в `DownloadManager` уже есть активная загрузка
     *   обновления (проверка Android-стороны); если да — второй запрос не нужен,
     *   даже когда наш таймер уже истёк (например, приложение перезапустили).
     */
    @Synchronized
    fun request(
        version: String,
        nowMs: Long,
        hasActiveDownload: Boolean = false,
    ): Decision {
        val previous = last
        val sameVersion = previous != null &&
            previous.version == version &&
            nowMs - previous.atMs < IN_FLIGHT_TTL_MS
        if (sameVersion) {
            val ageMs = nowMs - previous!!.atMs
            val markStillTrusted = hasActiveDownload || ageMs < IN_FLIGHT_GRACE_MS
            if (previous.inFlight && markStillTrusted) return Decision.InFlight(version)
            if (ageMs < COOLDOWN_MS) return Decision.Cooldown(version, COOLDOWN_MS - ageMs)
        }
        if (hasActiveDownload) return Decision.InFlight(version)
        last = Attempt(version, nowMs, inFlight = true)
        return Decision.Start
    }

    /** Загрузка завершилась успешно или отменена: версия больше не «в полёте». */
    @Synchronized
    fun finished(version: String) {
        val previous = last ?: return
        if (previous.version == version) {
            last = Attempt(version, previous.atMs, inFlight = false)
        }
    }

    /** `DownloadManager` сообщил, что загрузки этой версии больше нет. */
    @Synchronized
    fun lost(version: String) {
        val previous = last ?: return
        if (previous.version == version) last = null
    }

    /** Для диагностики: что сейчас помнит замок. */
    @Synchronized
    fun describe(): String {
        val previous = last ?: return "свободен"
        return "${previous.version}: ${if (previous.inFlight) "идёт загрузка" else "пауза после нажатия"}"
    }

    /** Только для тестов: вернуть замок в исходное состояние. */
    @Synchronized
    internal fun reset() {
        last = null
    }
}
