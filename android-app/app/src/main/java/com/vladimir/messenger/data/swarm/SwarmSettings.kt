package com.vladimir.messenger.data.swarm

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Режим раздачи и действующие пределы.
 *
 * Режим выбирает человек в настройках («Раздача»), обстановку (мобильный
 * интернет, экономия трафика, низкий заряд) телефон определяет сам при
 * каждом обращении - без подписок и приёмников: пределы спрашиваются не
 * чаще пары раз в секунду, а системные вызовы дешёвые.
 *
 * Хранится в p2p_prefs под ключом swarm_mode, как остальные настройки вида.
 */
object SwarmSettings {
    private const val PREFS = "p2p_prefs"
    private const val KEY_MODE = "swarm_mode"
    private const val ENVIRONMENT_TTL_MS = 1_000L

    private val _mode = MutableStateFlow(SwarmMode.NORMAL)
    val mode: StateFlow<SwarmMode> = _mode.asStateFlow()

    @Volatile
    private var initialized = false

    /** Прочитать сохранённый режим. Безопасно звать многократно. */
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mode.value = SwarmMode.fromStored(prefs.getString(KEY_MODE, null))
        initialized = true
    }

    /** Из настроек: пишет в prefs и действует сразу (бюджет читает пределы при каждом решении). */
    fun set(context: Context, value: SwarmMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, value.storedValue).apply()
        _mode.value = value
        initialized = true
    }

    /** Обстановка, снятая не чаще раза в секунду: бюджет спрашивает её на каждый пакет. */
    private data class Environment(val atMs: Long, val metered: Boolean, val lowPower: Boolean)

    @Volatile
    private var environment: Environment? = null

    /** Действующие пределы с учётом режима и обстановки прямо сейчас. */
    fun limits(context: Context, nowMs: Long = System.currentTimeMillis()): SwarmLimits {
        init(context)
        val env = environment?.takeIf { nowMs - it.atMs < ENVIRONMENT_TTL_MS }
            ?: Environment(nowMs, isMetered(context), isLowPower(context)).also { environment = it }
        return SwarmPolicy.limitsFor(
            mode = _mode.value,
            metered = env.metered,
            lowPower = env.lowPower,
        )
    }

    /** Мобильный интернет или включённая «Экономия трафика». */
    fun isMetered(context: Context): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            cm.isActiveNetworkMetered ||
                cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENFORCED
        } catch (_: Exception) {
            false
        }
    }

    /** Заряд ниже порога и телефон не на зарядке. */
    fun isLowPower(context: Context): Boolean {
        return try {
            val battery = context.applicationContext
                .getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return false
            if (battery.isCharging) return false
            // Отрицательное или огромное значение - свойство недоступно: считаем, что заряда хватает.
            val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            percent in 0 until SwarmPolicy.LOW_BATTERY_PERCENT
        } catch (_: Exception) {
            false
        }
    }
}
