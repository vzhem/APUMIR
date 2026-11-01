package com.vladimir.messenger.service

/**
 * Ритм фоновых насосов в простое.
 *
 * Активная передача/события держат короткий шаг, чтобы файлы и сообщения не
 * тормозили. Когда ничего не происходит, круг проверок растягивается: пустой
 * насос не должен будить процессор каждые двадцать секунд ради десятка
 * запросов в базу, которым нечего делать.
 */
object ServiceIdleCadence {

    /** Есть работа или только что была активность: прежний быстрый шаг. */
    const val FILE_PUMP_ACTIVE_MS = 20_000L

    /** Приложение на экране, но в покое: проверки реже. */
    const val FILE_PUMP_IDLE_FOREGROUND_MS = 60_000L

    /** Экран погас и тишина: самый редкий шаг страховки. */
    const val FILE_PUMP_IDLE_BACKGROUND_MS = 120_000L

    /** Барьер официальных загрузок APK: не чаще раза в полминуты. */
    const val UPDATE_BARRIER_MS = 30_000L

    fun filePumpDelayMs(hadWork: Boolean, recentlyActive: Boolean, onScreen: Boolean): Long = when {
        hadWork || recentlyActive -> FILE_PUMP_ACTIVE_MS
        onScreen -> FILE_PUMP_IDLE_FOREGROUND_MS
        else -> FILE_PUMP_IDLE_BACKGROUND_MS
    }
}
