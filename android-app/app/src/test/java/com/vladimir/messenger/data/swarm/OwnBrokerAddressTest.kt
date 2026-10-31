package com.vladimir.messenger.data.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор адреса своего MQTT-брокера.
 *
 * Правила обязаны совпадать с ядром (`network::multi_broker::parse_broker_endpoint`):
 * иначе приложение примет строку, которую ядро отвергнет, или наоборот —
 * человек увидит «сохранено», а брокером не станет. Тест чистый (без Android):
 * `normalize` не трогает Context и работает в обычном JVM-прогоне.
 */
class OwnBrokerAddressTest {

    @Test
    fun plainHostGetsDefaultMqttPort() {
        assertEquals("broker.example.com:1883", BrokerSettings.normalize("broker.example.com"))
        assertEquals("broker.example.com:1883", BrokerSettings.normalize("  broker.example.com  "))
    }

    @Test
    fun schemeAndPathAreAcceptedAndDropped() {
        assertEquals("10.0.0.5:1883", BrokerSettings.normalize("mqtt://10.0.0.5:1883/"))
        assertEquals("10.0.0.5:1883", BrokerSettings.normalize("tcp://10.0.0.5:1883"))
        // Схема без учёта регистра — как в ядре.
        assertEquals("mq.example.org:8883", BrokerSettings.normalize("MQTT://mq.example.org:8883"))
    }

    @Test
    fun ipv6InBracketsKeepsItsColons() {
        assertEquals("[2001:db8::1]:1883", BrokerSettings.normalize("[2001:db8::1]"))
        assertEquals("[2001:db8::1]:8883", BrokerSettings.normalize("mqtt://[2001:db8::1]:8883"))
    }

    @Test
    fun otherSchemesAndBrokenPortsAreRejected() {
        // Молча ходить не туда хуже, чем честно остаться на публичных брокерах.
        assertNull(BrokerSettings.normalize("https://broker.example.com"))
        assertNull(BrokerSettings.normalize("ws://broker.example.com:1883"))
        assertNull(BrokerSettings.normalize("broker.example.com:abc"))
        assertNull(BrokerSettings.normalize("broker.example.com:0"))
        assertNull(BrokerSettings.normalize("broker.example.com:70000"))
        assertNull(BrokerSettings.normalize(":1883"))
        assertNull(BrokerSettings.normalize("bad host:1883"))
        assertNull(BrokerSettings.normalize("[::1"))
    }

    @Test
    fun emptyMeansOnlyPublicBrokers() {
        assertEquals("", BrokerSettings.normalize(""))
        assertEquals("", BrokerSettings.normalize("   "))
    }
}
