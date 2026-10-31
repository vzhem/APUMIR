package com.vladimir.messenger.data.file

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Датаграмма файлового канала обязана проходить и по IPv6.
 *
 * Минимальный MTU IPv6 — 1280 байт (RFC 8200 §5), и туннели (VPN, 6to4, часть
 * мобильных APN) держат ровно его. Значит на полезную нагрузку остаётся
 * 1280 − 40 (заголовок IPv6) − 8 (UDP) = 1232 байта. Если кто-то поднимет
 * потолок «ради скорости», крупные фрагменты начнут теряться именно там, где
 * IPv6 только-только заработал, — и это будет выглядеть как «файлы не идут в
 * мобильной сети». Тест держит границу.
 */
class FileUdpWireMtuTest {

    private val ipv6HeaderBytes = 40
    private val udpHeaderBytes = 8

    @Test
    fun datagramFitsIpv6MinimumMtu() {
        val payload = FileUdpWire.MAX_DGRAM_BYTES + ipv6HeaderBytes + udpHeaderBytes
        assertTrue(
            "датаграмма $payload байт не влезает в минимальный MTU IPv6 (1280)",
            payload <= 1280,
        )
    }

    @Test
    fun datagramFitsIpv4TypicalMtu() {
        val payload = FileUdpWire.MAX_DGRAM_BYTES + 20 + udpHeaderBytes
        assertTrue("датаграмма $payload байт не влезает в MTU 1500", payload <= 1500)
    }

    @Test
    fun chunkIsSmallerThanDatagram() {
        assertTrue(FileUdpWire.MAX_PART_BYTES < FileUdpWire.MAX_DGRAM_BYTES)
    }
}
