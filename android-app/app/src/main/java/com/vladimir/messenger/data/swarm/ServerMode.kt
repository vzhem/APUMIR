package com.vladimir.messenger.data.swarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Режим «Я сервер» (раунд 215, вопрос владельца: «если брокер выключится то
 * приложение не может работать?» и пожелание дать каждому телефону роль).
 *
 * ВКЛ (по умолчанию - так работает и сегодня): телефон - полноправный узел
 * сети: хранит чужие файлы на хранении для контактов не в сети, раздаёт
 * файлы сообществ, которые сам скачал, и раздаёт обновления приложения.
 *
 * ВЫКЛ - «режим абонента»: телефон хранит и дожидается доставки только СВОИХ
 * данных (свои неотправленные файлы и сообщения - исходящая очередь и
 * раздача собственных файлов работают всегда). Чужое не хранится и не
 * раздаётся: предложения хранения отклоняются, файлы сообществ и обновления
 * этот телефон другим не отдаёт (сам скачать может как раньше).
 *
 * Хранится в p2p_prefs под ключом im_the_server. Чтение [isEnabled] -
 * каждый раз из prefs (дёшево, решения принимаются на каждый запрос без
 * подписок); [enabled] - поток для настроек.
 */
object ServerMode {
    private const val PREFS = "p2p_prefs"
    private const val KEY = "im_the_server"

    private val _enabled = MutableStateFlow(true)

    /** Текущий режим для экрана настроек. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var initialized = false

    /** Прочитать сохранённый режим. Безопасно звать многократно. */
    fun init(context: Context) {
        if (initialized) return
        val value = prefs(context).getBoolean(KEY, true)
        _enabled.value = value
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
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY, true)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
