package com.vladimir.messenger.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEnvelopeTest {

    @Test
    fun messageRoundTripsThroughBuildAndParse() {
        val json = RelayEnvelope.buildMessage(
            messageId = "msg-1",
            chatId = "chat-1",
            content = "hello world",
            timestamp = 1234567890L,
            senderId = "pk_alice",
        )
        val parsed = RelayEnvelope.parse(json)

        assertTrue(parsed is RelayEnvelope.Parsed.Message)
        val m = parsed as RelayEnvelope.Parsed.Message
        assertEquals("msg-1", m.messageId)
        assertEquals("chat-1", m.chatId)
        assertEquals("hello world", m.content)
        assertEquals(1234567890L, m.timestamp)
    }

    @Test
    fun ackRoundTripsThroughBuildAndParse() {
        val json = RelayEnvelope.buildAck(messageId = "msg-1", from = "pk_bob", timestamp = 99L)
        val parsed = RelayEnvelope.parse(json)

        assertTrue(parsed is RelayEnvelope.Parsed.Ack)
        val a = parsed as RelayEnvelope.Parsed.Ack
        assertEquals("msg-1", a.messageId)
        assertEquals("pk_bob", a.from)
    }

    @Test
    fun contentWithSpecialCharactersRoundTrips() {
        val content = "line1\nline2 \"quotes\" \\backslash\\ tab\there {brace} emoji 🚀 end"
        val json = RelayEnvelope.buildMessage("m", "c", content, 1L, "pk_x")
        val parsed = RelayEnvelope.parse(json) as RelayEnvelope.Parsed.Message

        assertEquals(content, parsed.content)
    }

    @Test
    fun parsesLegacyOrgJsonMessageFormat() {
        // Обратная совместимость: payload, собранный старым кодом через org.json, должен парситься.
        val legacy = """{"type":"message","messageId":"m1","chatId":"c1","content":"hi there","timestamp":42}"""
        val parsed = RelayEnvelope.parse(legacy) as RelayEnvelope.Parsed.Message

        assertEquals("m1", parsed.messageId)
        assertEquals("c1", parsed.chatId)
        assertEquals("hi there", parsed.content)
        assertEquals(42L, parsed.timestamp)
    }

    @Test
    fun parsesMessageWithoutOptionalFields() {
        val legacy = """{"type":"message","messageId":"m1","content":"hi","timestamp":42}"""
        val parsed = RelayEnvelope.parse(legacy) as RelayEnvelope.Parsed.Message

        assertEquals("m1", parsed.messageId)
        assertEquals("", parsed.chatId)
    }

    @Test
    fun plainTextBecomesOther() {
        assertTrue(RelayEnvelope.parse("just some plain text") is RelayEnvelope.Parsed.Other)
    }

    @Test
    fun blankPayloadBecomesOther() {
        assertTrue(RelayEnvelope.parse("") is RelayEnvelope.Parsed.Other)
    }

    @Test
    fun ackWithoutMessageIdBecomesOther() {
        val json = """{"type":"ack","from":"pk_bob"}"""
        assertTrue(RelayEnvelope.parse(json) is RelayEnvelope.Parsed.Other)
    }

    @Test
    fun unknownTypeBecomesOther() {
        val json = """{"type":"something_else","messageId":"m1"}"""
        assertTrue(RelayEnvelope.parse(json) is RelayEnvelope.Parsed.Other)
    }

    @Test
    fun malformedJsonBecomesOtherWithRawText() {
        val parsed = RelayEnvelope.parse("{not really json")
        assertTrue(parsed is RelayEnvelope.Parsed.Other)
        assertEquals("{not really json", (parsed as RelayEnvelope.Parsed.Other).raw)
    }
}
