package com.vladimir.messenger.data.swarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Режим «Я сервер» (раунд 215).
 *
 * По умолчанию выключен: серверная роль и хранение/раздача чужих данных
 * требуют явного выбора владельца телефона. При выключенном режиме телефон
 * работает абонентом; собственные сообщения и файлы продолжают отправляться.
 * Включение роли сохраняется как явное предпочтение и не сбрасывается при
 * обновлении приложения.
 *
 * ВКЛ: телефон может хранить чужие файлы на хранении для контактов не в сети
 * и раздавать тяжёлые файлы сообществ. ВЫКЛ: чужое на хранение не берётся,
 * тяжёлое содержимое сообществ не отдаётся; разрешённые лёгкие файлы и
 * отдельные пути (например, APK-раздача) управляются своими политиками.
 *
 * Хранится в p2p_prefs под ключом im_the_server. Чтение [isEnabled] -
 * каждый раз из prefs (дёшево, решения принимаются на каждый запрос без
 * подписок); [enabled] - поток для настроек.
 */
object ServerMode {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "im_the_server"

    /**
     * Раунд 217 (финальная семантика владельца): черта «лёгкого». Абонент
     * («Я сервер»=ВЫКЛ) раздаёт другим только лёгкие файлы - маленькие
     * документы и стикеры; тяжёлое (картинки, видео, гифки, большие файлы)
     * не пересылает. Текст, резервные копии и APK обновлений несут все
     * (у них свои пути, этот предел их не задевает).
     */
    const val LIGHT_SERVE_MAX_BYTES: Long = 2L * 1024 * 1024

    private val _enabled = MutableStateFlow(ServerModePolicy.DEFAULT_ENABLED)

    /** Текущий режим для экрана настроек. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var initialized = false

    /** Прочитать сохранённый режим. Безопасно звать многократно. */
    fun init(context: Context) {
        if (initialized) return
        _enabled.value = currentValue(context)
        initialized = true
    }

    /** Из настроек: пишет в prefs и действует сразу (следующий запрос уже по-новому). */
    fun set(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY, value).apply()
        _enabled.value = value
        initialized = true
    }

    /**
     * Решение на месте: читать prefs каждый раз - дёшево, а подписки и
     * приёмники не нужны (тот же приём, что у SwarmSettings/бюджета).
     */
    fun isEnabled(context: Context): Boolean = currentValue(context)

    private fun currentValue(context: Context): Boolean {
        val preferences = prefs(context)
        val stored = if (preferences.contains(KEY)) preferences.getBoolean(KEY, false) else null
        return ServerModePolicy.enabled(stored)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
