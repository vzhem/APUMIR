package com.vladimir.messenger.ui.components

/**
 * Правило «показывать ли обложку перед входом».
 *
 * Раунд 263 убрал заставку при тёплом старте: если ядро уже поднято, человек
 * сразу попадал в приложение. На современных телефонах ядро живёт в фоне
 * постоянно (сторож, WorkManager, быстрый подъём), поэтому обложку перестали
 * видеть вовсе - не потому, что файл пропал, а потому, что экран не рисовался.
 *
 * Теперь обложка показывается при каждом открытии приложения, а при возврате из
 * фона - только если приложение отсутствовало дольше
 * [REPLAY_AFTER_BACKGROUND_MILLIS]: короткое переключение мессенджеров не даёт
 * вспышки на весь экран.
 *
 * Android-зависимостей нет: время передаётся снаружи, поэтому правила
 * проверяются обычными JVM-тестами.
 */
class SplashGate(
    private val replayAfterBackgroundMillis: Long = REPLAY_AFTER_BACKGROUND_MILLIS,
) {
    /** Момент ухода в фон; `null` - приложение на экране. */
    private var backgroundedAtMillis: Long? = null
    private var screenCreated = false

    /**
     * Создан экран приложения.
     *
     * Первый экран процесса - всегда с обложкой. Пересоздание активности
     * (поворот, смена темы, размера шрифта) обложку не повторяет.
     */
    fun onScreenCreated(nowMillis: Long): Boolean {
        if (!screenCreated) {
            screenCreated = true
            backgroundedAtMillis = null
            return true
        }
        return replayAfterBackground(nowMillis)
    }

    fun onBackground(nowMillis: Long) {
        backgroundedAtMillis = nowMillis
    }

    /** Возврат на экран: `true` - показать обложку. */
    fun onForeground(nowMillis: Long): Boolean = replayAfterBackground(nowMillis)

    private fun replayAfterBackground(nowMillis: Long): Boolean {
        val hiddenAt = backgroundedAtMillis ?: return false
        backgroundedAtMillis = null
        return nowMillis - hiddenAt >= replayAfterBackgroundMillis
    }

    companion object {
        /** Сколько приложение должно пробыть в фоне, чтобы обложка вернулась. */
        const val REPLAY_AFTER_BACKGROUND_MILLIS = 60_000L
    }
}

/**
 * Один гейт на процесс: переживает пересоздание активности, но не перезапуск
 * приложения - после запуска процесса обложка снова показывается.
 */
object SplashSchedule {
    val gate = SplashGate()
}
