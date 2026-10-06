package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор строки MQTT ядра: одна и та же человекочитаемая строка нужна и
 * карточке «Сообщения сети», и отчёту «Логи». Тест чистый (без Android),
 * поэтому его гоняет scripts/ci/check-chat-history.sh на runner.
 */
class MqttLinkTextTest {

    @Test
    fun connectedLineIsReadAsLiveSession() {
        val state = MqttLinkText.parse(
            "MQTT: tcp broker.example:1883, ConnAck 5 с назад, ошибка 12 с назад: Network timeout",
        )

        assertTrue(state != null)
        assertEquals("напрямую", state!!.path)
        assertEquals(5, state.connAckAgoSec)
        assertEquals(12, state.errorAgoSec)
        // Ошибка была РАНЬШЕ подтверждения: текущая сессия жива.
        assertTrue(state.connected)
        assertFalse(state.droppedAfterConnect)
        assertTrue(MqttLinkText.humanize("MQTT: tcp broker.example:1883, ConnAck 5 с назад").contains("На связи"))
    }

    @Test
    fun errorAfterConnectIsReadAsOwnReconnect() {
        val state = MqttLinkText.parse(
            "MQTT: tcp broker.example:1883, ConnAck 120 с назад, ошибка 12 с назад: Connection refused",
        )

        assertTrue(state != null)
        assertFalse(state!!.connected)
        assertTrue(state.droppedAfterConnect)
        val human = MqttLinkText.humanize(
            "MQTT: tcp broker.example:1883, ConnAck 120 с назад, ошибка 12 с назад: Connection refused",
        )
        assertTrue(human.contains("Был перебой"))
        assertTrue(human.contains("подключаемся снова сами"))
    }

    @Test
    fun bridgeAndFailingLineKeepHumanWords() {
        val raw = "MQTT: wss bridge, ошибка 3 с назад: DNS lookup failed"
        val state = MqttLinkText.parse(raw)

        assertTrue(state != null)
        assertEquals("через обходной канал", state!!.path)
        assertNull(state.connAckAgoSec)
        assertFalse(state.connected)
        assertEquals("не удалось найти адрес узла", MqttLinkText.humanError(state.errorText))
        assertTrue(MqttLinkText.humanize(raw).contains("соединение восстанавливается автоматически"))
    }

    @Test
    fun unknownPathAndForeignTextAreNotMistakenForMqtt() {
        assertEquals("через наш сервер", MqttLinkText.parse("MQTT: наш брокер, ConnAck 1 с назад")?.path)
        assertEquals(
            "через наш сервер (прямой путь не прошёл)",
            MqttLinkText.parse("MQTT: наш брокер, переход на запасной, ConnAck 1 с назад")?.path,
        )
        assertEquals("через мост", MqttLinkText.parse("MQTT: SOCKS5 мост, ConnAck 1 с назад")?.path)
        assertNull(MqttLinkText.parse(""))
        assertNull(MqttLinkText.parse("MQTT: , ConnAck 1 с назад"))
        assertEquals("", MqttLinkText.humanize("не строка mqtt"))
    }

    @Test
    fun agoAndErrorsAreHumanReadable() {
        assertEquals("45 с назад", MqttLinkText.humanAgo(45))
        assertEquals("6 мин назад", MqttLinkText.humanAgo(6 * 60))
        assertEquals("3 ч назад", MqttLinkText.humanAgo(3 * 3600))
        assertEquals("2 дн назад", MqttLinkText.humanAgo(2 * 86_400))
        assertEquals("не дождались ответа", MqttLinkText.humanError("timed out"))
        assertEquals("узел не принял связь", MqttLinkText.humanError("connection refused"))
        assertEquals("сеть не ответила", MqttLinkText.humanError(""))
    }
}
