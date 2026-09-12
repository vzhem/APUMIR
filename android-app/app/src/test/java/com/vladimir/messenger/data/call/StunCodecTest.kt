package com.vladimir.messenger.data.call

import java.net.Inet4Address
import java.net.Inet6Address
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Векторы RFC 5769 (§2.1 запрос, §2.2 ответ IPv4, §2.3 ответ IPv6). */
class StunCodecTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private val txId = hex("b7e7a701bc34d686fa87dfae")

    private val respV4 = hex(
        "0101003c 2112a442 b7e7a701bc34d686fa87dfae" +
            "8022000b 746573742076656374 6f7220" +
            "00200008 0001a147 e112a643" +
            "00080014 2b91f599fd9e90c38c7489f92af9ba53f06be7d7" +
            "80280004 c07d4c96",
    )

    private val respV6 = hex(
        "01010048 2112a442 b7e7a701bc34d686fa87dfae" +
            "8022000b 746573742076656374 6f7220" +
            "00200014 0002a147 0113a9faa5d3f179bc25f4b5bed2b9d9" +
            "00080014 a382954e4be67bf11784c97c8292c275bfe3ed41" +
            "80280004 c8fb0b4c",
    )

    @Test
    fun bindingRequestHeaderMatchesSpec() {
        val request = StunCodec.bindingRequest(txId)
        assertEquals(20, request.size)
        // Тип 0x0001, длина 0, magic cookie, transaction id.
        assertArrayEquals(hex("00010000 2112a442 b7e7a701bc34d686fa87dfae"), request)
        assertTrue(StunCodec.isStun(request, request.size))
    }

    @Test
    fun parsesXorMappedIpv4FromRfcVector() {
        val mapped = StunCodec.parseBindingResponse(respV4, respV4.size, txId)
        assertNotNull(mapped)
        assertTrue(mapped!!.address is Inet4Address)
        assertEquals("192.0.2.1", mapped.address.hostAddress)
        assertEquals(32853, mapped.port)
    }

    @Test
    fun parsesXorMappedIpv6FromRfcVector() {
        val mapped = StunCodec.parseBindingResponse(respV6, respV6.size, txId)
        assertNotNull(mapped)
        assertTrue(mapped!!.address is Inet6Address)
        assertEquals("2001:db8:1234:5678:11:2233:4455:6677", mapped.address.hostAddress)
        assertEquals(32853, mapped.port)
    }

    @Test
    fun rejectsForeignTransactionAndNonStun() {
        val otherTx = ByteArray(12) { 7 }
        assertNull(StunCodec.parseBindingResponse(respV4, respV4.size, otherTx))
        // Наш провод начинается с 0x02 (control) / 0xAC (media): за STUN не принимается.
        val control = CallLinkWire.buildControl(1, ByteArray(20))
        assertFalse(StunCodec.isStun(control, control.size))
        val media = CallLinkWire.buildMedia(3, listOf(CallLinkWire.MediaFrame(1, ByteArray(20))))!!
        assertFalse(StunCodec.isStun(media, media.size))
        // Обрезанный ответ — null, не исключение.
        assertNull(StunCodec.parseBindingResponse(respV4, 30, txId))
        // Запрос (тип 0x0001) не является успешным ответом.
        val request = StunCodec.bindingRequest(txId)
        assertNull(StunCodec.parseBindingResponse(request, request.size, txId))
    }

    @Test
    fun fallsBackToPlainMappedAddress() {
        // Старый сервер без XOR-MAPPED-ADDRESS: MAPPED-ADDRESS 203.0.113.9:4242 (0x1092).
        val response = hex(
            "0101000c 2112a442 b7e7a701bc34d686fa87dfae" +
                "00010008 0001 1092 cb007109",
        )
        val mapped = StunCodec.parseBindingResponse(response, response.size, txId)
        assertNotNull(mapped)
        assertEquals("203.0.113.9", mapped!!.address.hostAddress)
        assertEquals(4242, mapped.port)
    }
}
