package com.vladimir.messenger.data.notification

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Локальное хранилище настроек фонарика для уведомлений.
 *
 * Хранится только на этом устройстве (как паузы уведомлений и выбор карты):
 * у телефона в кармане и у домашнего планшета/ПК-эмулятора на одной личности
 * потребность во вспышке разная.
 */
object FlashlightAlertStore {
    private const val TAG = "FlashlightAlertStore"
    private const val PREFS = "notification_flashlight"
    private const val KEY_SOS_ENABLED = "sos_enabled"
    private const val KEY_MESSAGE_MODE = "message_mode"

    private val _config = MutableStateFlow(FlashlightAlertConfig())
    val config: StateFlow<FlashlightAlertConfig> = _config.asStateFlow()

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = FlashlightAlertConfig(
            sosEnabled = prefs.getBoolean(KEY_SOS_ENABLED, FlashlightAlertPolicy.DEFAULT_SOS_ENABLED),
            messageMode = FlashlightMessageMode.fromId(
                prefs.getString(KEY_MESSAGE_MODE, FlashlightAlertPolicy.DEFAULT_MESSAGE_MODE.id),
            ),
        )
    }

    /** Читает актуальную настройку даже при старте фоновой службы до открытия экрана. */
    fun current(context: Context): FlashlightAlertConfig {
        initialize(context)
        return _config.value
    }

    fun setSosEnabled(context: Context, enabled: Boolean) {
        val next = current(context).copy(sosEnabled = enabled)
        persist(context, next)
    }

    fun setMessageMode(context: Context, mode: FlashlightMessageMode) {
        val next = current(context).copy(messageMode = mode)
        persist(context, next)
    }

    fun reset(context: Context) {
        val defaults = FlashlightAlertConfig()
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SOS_ENABLED)
            .remove(KEY_MESSAGE_MODE)
            .commit()
        if (!saved) Log.w(TAG, "Не удалось сбросить настройки фонарика")
        _config.value = defaults
    }

    private fun persist(context: Context, next: FlashlightAlertConfig) {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SOS_ENABLED, next.sosEnabled)
            .putString(KEY_MESSAGE_MODE, next.messageMode.id)
            .commit()
        if (!saved) Log.w(TAG, "Не удалось сохранить настройки фонарика")
        _config.value = next
    }
}
