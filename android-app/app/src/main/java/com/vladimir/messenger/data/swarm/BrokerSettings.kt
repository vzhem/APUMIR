package com.vladimir.messenger.data.swarm

import android.content.Context
import com.vladimir.messenger.data.RustBridge

/**
 * Свой MQTT-брокер из настроек (пункт 1 списка «чего может не хватать»).
 *
 * Зачем. Всё общение между телефонами идёт через брокеров: публичные (пять
 * адресов, встроенных в ядро) или свой. Пока брокеры живы и не заблокированы,
 * настраивать нечего. Но если публичные лягут или сеть начнёт резать их порт,
 * без своего адреса обновление «в поле» невозможно: пересобирать ради этого
 * приложение нельзя.
 *
 * Что здесь:
 * * хранение адреса в `p2p_prefs` (`own_broker`);
 * * передача его ядру (`RustBridge.setOwnBroker`) — ДО старта службы, потому
 *   что список брокеров читается при поднятии сессии;
 * * проверка доступности (`RustBridge.probeOwnBroker`) для кнопки
 *   «Проверить»: человек видит, что там действительно MQTT, а не опечатка.
 *
 * Пустая строка — осознанное «только публичные брокеры» (это же значение по
 * умолчанию: пока поле не задано, ничего не меняется).
 *
 * Изменение на ходу: ядро пересоздаёт MQTT-сессию по новому адресу
 * (`mqtt_transport::note_broker_settings_changed` → `restart_reason`), поэтому
 * перезапускать приложение не нужно, а очередь сообщений не теряется — она
 * живёт выше транспорта.
 */
object BrokerSettings {
    private const val PREFS = "p2p_prefs"
    private const val KEY_OWN_BROKER = "own_broker"

    /**
     * Разбор введённого адреса — до того, как он уйдёт в ядро.
     *
     * Зачем дублировать разбор ядра: при старте приложения ядра ещё нет
     * (`RustBridge.setOwnBroker` только запоминает строку), и опечатка
     * выяснилась бы лишь через минуту, когда служба поднимется. Проверка здесь
     * даёт ответ сразу и одинаково с ядром: `mqtt://` или `tcp://` можно,
     * прочие схемы — нет; путь/якорь отбрасывается; порт по умолчанию 1883.
     *
     * Возвращает нормализованный `host:port`, `""` для «только публичные» или
     * `null`, если строку понять нельзя.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val withoutScheme = when {
            trimmed.contains("://") -> {
                val scheme = trimmed.substringBefore("://").lowercase()
                if (scheme != "mqtt" && scheme != "tcp") return null
                trimmed.substringAfter("://")
            }
            else -> trimmed
        }
        val withoutPath = withoutScheme
            .split('/', '#', '?')
            .first()
            .trim()
        if (withoutPath.isEmpty()) return null
        // IPv6 в скобках: двоеточия внутри адреса не отделяют порт.
        val (host, port) = if (withoutPath.startsWith("[")) {
            val end = withoutPath.indexOf(']')
            if (end <= 1) return null
            val hostPart = withoutPath.substring(0, end + 1)
            val rest = withoutPath.substring(end + 1)
            if (rest.isEmpty()) hostPart to DEFAULT_PORT.toString()
            else if (!rest.startsWith(":")) return null
            else hostPart to rest.drop(1)
        } else {
            val colon = withoutPath.lastIndexOf(':')
            if (colon < 0) withoutPath to DEFAULT_PORT.toString()
            else withoutPath.substring(0, colon) to withoutPath.substring(colon + 1)
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val portNumber = port.toIntOrNull() ?: return null
        if (portNumber !in 1..65535) return null
        return "$host:$portNumber"
    }

    /** Порт MQTT по умолчанию: тот же, что в ядре (`multi_broker`). */
    private const val DEFAULT_PORT = 1883

    /** Сохранённый адрес или пустая строка («только публичные»). */
    fun text(context: Context): String =
        prefs(context).getString(KEY_OWN_BROKER, "").orEmpty()

    /** Задан ли свой брокер. */
    fun isCustom(context: Context): Boolean = text(context).isNotBlank()

    /**
     * Сохранить адрес и применить.
     *
     * `null` — ядро адрес не приняло (не разобрало строку): тогда в prefs
     * ничего не пишем, а настройка остаётся прежней. Это важно: иначе
     * опечатка выключила бы рабочий брокер.
     */
    fun set(context: Context, value: String): Boolean {
        val app = context.applicationContext
        val clean = normalize(value) ?: return false
        if (!RustBridge.setOwnBroker(clean)) return false
        prefs(app).edit().putString(KEY_OWN_BROKER, clean).apply()
        return true
    }

    /** Применить сохранённый адрес к ядру (старт приложения и службы). */
    fun applyToCore(context: Context) {
        val saved = text(context)
        if (saved.isBlank()) return
        RustBridge.setOwnBroker(saved)
    }

    /** Отвечает ли брокер по введённому адресу прямо сейчас. */
    fun probe(value: String): Boolean {
        val clean = normalize(value) ?: return false
        if (clean.isEmpty()) return true // «публичные» проверять нечего
        return RustBridge.probeOwnBroker(clean)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
