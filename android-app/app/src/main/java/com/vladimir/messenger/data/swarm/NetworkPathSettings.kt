package com.vladimir.messenger.data.swarm

import android.content.Context
import com.vladimir.messenger.data.RustBridge

/**
 * Настройки пути доставки (часть 2 задачи IPv6: устойчивость и гибкость сети).
 *
 * Два переключателя, по умолчанию ОБА выключены — поведение приложения не
 * меняется для всех, кто ничего не трогал.
 *
 * * **Только через relay** (`relay_only`) — прямой канал не используется ни на
 *   отправку, ни на приём. Нужен в сетях, где прямой UDP запрещён или
 *   нежелателен: корпоративный Wi-Fi, строгий firewall, чужой роутер. Сообщения
 *   идут через брокер/relay, просто чуть длиннее путь.
 * * **Экономия трафика** (`save_traffic`) — если связь идёт через мобильную
 *   сеть (или в системе включена «Экономия трафика»), прямой канал не
 *   поднимается: сообщения идут через брокер. На Wi-Fi поведение прежнее.
 *   Это не «выключить прямой канал навсегда» — только на мобильной.
 *
 * Почему не второй `SwarmSettings`: тот уже описывает «сколько отдавать
 * другим» (раздача, пределы роя), а здесь речь о том, КАК доставлять. Два
 * независимых смысла в одном переключателе дали бы противоречивые настройки.
 *
 * Хранится в `p2p_prefs`, как остальные настройки вида.
 */
object NetworkPathSettings {
    private const val PREFS = "p2p_prefs"
    private const val KEY_RELAY_ONLY = "network_relay_only"
    private const val KEY_SAVE_TRAFFIC = "network_save_traffic"
    private const val KEY_IPV6 = "network_ipv6_enabled"

    /** «Только через relay» сейчас включено? */
    fun relayOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RELAY_ONLY, false)

    /** «Экономия трафика» сейчас включена? */
    fun saveTraffic(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAVE_TRAFFIC, false)

    /**
     * «Использовать IPv6» — kill-switch на случай, когда сеть объявляет IPv6,
     * но маршрута нет (типичная беда мобильных операторов и VPN). По
     * умолчанию включено: выключение — осознанный выбор человека.
     */
    fun ipv6Enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IPV6, true)

    /** Из настроек: пишет в prefs и сразу применяет к ядру. */
    fun setIpv6Enabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IPV6, value).apply()
        applyToCore(context)
    }

    /** Из настроек: пишет в prefs и сразу применяет к ядру. */
    fun setRelayOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RELAY_ONLY, value).apply()
        applyToCore(context)
    }

    /** Из настроек: пишет в prefs и сразу применяет к ядру. */
    fun setSaveTraffic(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_TRAFFIC, value).apply()
        applyToCore(context)
    }

    /**
     * Передать политику ядру.
     *
     * `metered` считаем здесь: ядро не видит тариф, а `SwarmSettings.isMetered`
     * уже знает и про мобильную сеть, и про системную «Экономию трафика»
     * (`restrictBackgroundStatus`). Зовётся при старте службы (из
     * `NetworkMonitor`), при смене сети и при переключении.
     */
    fun applyToCore(context: Context) {
        val app = context.applicationContext
        RustBridge.setNetworkPathPolicy(
            relayOnly = relayOnly(app),
            saveTraffic = saveTraffic(app),
            metered = SwarmSettings.isMetered(app),
        )
        // Kill-switch IPv6 живёт в том же месте: обе настройки описывают
        // «каким путём доставлять», и применяются вместе.
        RustBridge.setIpv6Enabled(ipv6Enabled(app))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
