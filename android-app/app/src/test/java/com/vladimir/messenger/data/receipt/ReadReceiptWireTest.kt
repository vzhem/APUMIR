package com.vladimir.messenger.data.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт «прочитано» — по нему у отправителя галочки синеют.
 *
 * Он едет тем же транспортом, что и обычный текст, поэтому обязан надёжно
 * отличаться от него и не разваливаться на мусорных данных.
 */
class ReadReceiptWireTest {

    @Test
    fun `round trip keeps chat and message ids`() {
        val envelope = ReadReceiptWire.build("pk_abc", listOf("m1", "m2", "m3"), 1700)!!
        val packet = ReadReceiptWire.parse(envelope)!!
        assertEquals("pk_abc", packet.chatId)
        assertEquals(listOf("m1", "m2", "m3"), packet.messageIds)
        assertEquals(1700L, packet.atMs)
    }

    @Test
    fun `plain text and other protocols are not receipts`() {
        assertFalse(ReadReceiptWire.isReadReceipt("Привет"))
        assertFalse(ReadReceiptWire.isReadReceipt("APUGRP1|msg|x"))
        assertFalse(ReadReceiptWire.isReadReceipt("APUREACT1|c|m|x|1|1"))
        assertFalse(ReadReceiptWire.isReadReceipt(null))
        assertNull(ReadReceiptWire.parse("Привет"))
    }

    @Test
    fun `ids containing separators are dropped, not mangled`() {
        // Иначе один кривой идентификатор разорвал бы разбор всего конверта.
        val envelope = ReadReceiptWire.build("pk_abc", listOf("good", "ba|d", "al,so"), 1)!!
        assertEquals(listOf("good"), ReadReceiptWire.parse(envelope)!!.messageIds)
    }

    @Test
    fun `duplicates are collapsed`() {
        val envelope = ReadReceiptWire.build("pk_abc", listOf("m1", "m1", "m2"), 1)!!
        assertEquals(listOf("m1", "m2"), ReadReceiptWire.parse(envelope)!!.messageIds)
    }

    @Test
    fun `empty input produces nothing`() {
        assertNull(ReadReceiptWire.build("pk_abc", emptyList(), 1))
        assertNull(ReadReceiptWire.build("", listOf("m1"), 1))
        assertNull(ReadReceiptWire.build("pk_a|bc", listOf("m1"), 1))
    }

    @Test
    fun `batch is bounded`() {
        val many = (1..200).map { "m$it" }
        val packet = ReadReceiptWire.parse(ReadReceiptWire.build("pk_abc", many, 1)!!)!!
        assertTrue(packet.messageIds.size <= ReadReceiptWire.MAX_IDS)
    }

    @Test
    fun `malformed envelopes are rejected`() {
        assertNull(ReadReceiptWire.parse("APUREAD1|only|two"))
        assertNull(ReadReceiptWire.parse("APUREAD1|chat|m1|notanumber"))
        assertNull(ReadReceiptWire.parse("APUREAD1|chat||123"))
    }
}
