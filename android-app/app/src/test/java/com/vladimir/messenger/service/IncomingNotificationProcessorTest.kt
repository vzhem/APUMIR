package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.util.QuietMessage
import com.vladimir.messenger.util.SosMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationProcessorTest {
    private fun row(id: String, content: String, timestamp: Long = 1L) = MessageEntity(
        id = id, chatId = "chat", senderId = "friend", content = content,
        timestamp = timestamp, notificationPending = true,
    )

    @Test
    fun `delayed and skewed messages alert once when locally received`() = runBlocking {
        val claimed = mutableSetOf<String>()
        val alerted = mutableListOf<Pair<String, IncomingAlert>>()
        val processor = IncomingNotificationProcessor(
            claim = { claimed.add(it) },
            isDeferred = { false },
            alert = { msg, kind -> alerted += msg.id to kind },
        )
        val old = row("old", "hello", timestamp = 1)
        val future = row("future", "hello", timestamp = Long.MAX_VALUE)
        processor.process(old)
        processor.process(old) // another collector or restart after claim
        processor.process(future)
        assertEquals(listOf("old" to IncomingAlert.NORMAL, "future" to IncomingAlert.NORMAL), alerted)
    }

    @Test
    fun `deferred letters and service or media parts are consumed without alerts`() = runBlocking {
        val claimed = mutableSetOf<String>()
        val alerted = mutableListOf<String>()
        val processor = IncomingNotificationProcessor(
            claim = { claimed.add(it) },
            isDeferred = { it == "deferred" },
            alert = { msg, _ -> alerted += msg.id },
        )
        processor.process(row("deferred", "not yet"))
        processor.process(row("part", "APUIMGP1:1/1/1:abc"))
        processor.process(row("service", "APUGIF1|ask"))
        assertEquals(setOf("deferred", "part", "service"), claimed)
        assertTrue(alerted.isEmpty())
    }

    @Test
    fun `quiet uses silent path and SOS uses separate alarm path`() = runBlocking {
        val claimed = mutableSetOf<String>()
        val kinds = mutableListOf<IncomingAlert>()
        val processor = IncomingNotificationProcessor(
            claim = { claimed.add(it) },
            isDeferred = { false },
            alert = { _, kind -> kinds += kind },
        )
        processor.process(row("quiet", QuietMessage.wrap("shh")))
        processor.process(row("sos", SosMessage.wrap("help")))
        processor.process(row("normal", "hello"))
        assertEquals(listOf(IncomingAlert.QUIET, IncomingAlert.SOS, IncomingAlert.NORMAL), kinds)
    }

    @Test
    fun `claimed work is not posted again after an alert failure`() = runBlocking {
        val claimed = mutableSetOf<String>()
        var attempts = 0
        val processor = IncomingNotificationProcessor(
            claim = { claimed.add(it) },
            isDeferred = { false },
            alert = { _, _ -> attempts++; error("notification service unavailable") },
        )
        runCatching { processor.process(row("id", "hello")) }
        processor.process(row("id", "hello"))
        assertEquals(1, attempts)
    }
}
