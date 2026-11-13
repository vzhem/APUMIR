package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMailboxMessageCodecTest {
    private val id = "01234567-89ab-4cde-8f01-23456789abcd"
    private val sender = "pk_" + "1".repeat(32)
    private val recipient = "pk_" + "2".repeat(32)
    private val sentAt = 1_700_000_000_000L

    @Test fun authenticatedPayloadCarriesTheOriginalIdBothPeersAndExactUnicodeText() {
        val content = "Привет | мир\nemoji 😀 and separator | end"
        val encoded = PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, content)!!
        val decoded = PushMailboxMessageCodec.parse(encoded, sender, recipient)!!
        assertEquals(id, decoded.id)
        assertEquals(sender, decoded.sender)
        assertEquals(recipient, decoded.recipient)
        assertEquals(sentAt, decoded.sentAtMs)
        assertEquals(content, decoded.content)
        assertTrue(encoded.startsWith(PushMailboxMessageCodec.PREFIX))
        assertFalse(decoded.toString().contains(content))
        assertFalse(decoded.toString().contains(id))
    }

    @Test fun independentlyExpectedPeersAndCanonicalMetadataAreRequired() {
        val encoded = PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, "hello")!!
        assertNull(PushMailboxMessageCodec.parse(encoded, recipient, sender))
        assertNull(PushMailboxMessageCodec.parse(encoded, sender, "pk_" + "3".repeat(32)))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace(id, id.uppercase()), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace(id, "00000000-0000-0000-0000-000000000000"), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace("|$sentAt|", "|0$sentAt|"), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace("|$sentAt|", "|0|"), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace("|$sentAt|", "|9007199254740992|"), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace("|5|hello", "|05|hello"), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace("|5|hello", "|4|hello"), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(encoded + "!", sender, recipient))
        assertNull(PushMailboxMessageCodec.encode(id, sender, sender, sentAt, "hello"))
        assertNull(PushMailboxMessageCodec.encode(id, sender.uppercase(), recipient, sentAt, "hello"))
        assertNull(PushMailboxMessageCodec.encode(id, sender, recipient, 0, "hello"))
        assertNull(PushMailboxMessageCodec.encode("not-an-id", sender, recipient, sentAt, "hello"))
        assertNull(PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, ""))
    }

    @Test fun byteLimitAndMalformedUtf16AreRejectedWithoutSplittingTheBody() {
        val fixed = "${PushMailboxMessageCodec.PREFIX}$id|$sender|$recipient|$sentAt|".length
        // At this limit the length field has five ASCII digits and a separator.
        val maxBody = PushMailboxMessageCodec.MAX_PLAINTEXT_BYTES - fixed - 6
        val atLimit = PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, "x".repeat(maxBody))!!
        assertEquals(PushMailboxMessageCodec.MAX_PLAINTEXT_BYTES, atLimit.toByteArray(Charsets.UTF_8).size)
        assertNotNull(PushMailboxMessageCodec.parse(atLimit, sender, recipient))
        assertNull(PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, "x".repeat(maxBody + 1)))
        assertNull(PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, "a\uD800"))
        assertNull(PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, "a\uDC00"))
        val encoded = PushMailboxMessageCodec.encode(id, sender, recipient, sentAt, "😀")!!
        assertTrue(encoded.contains("|4|😀"))
        assertNull(PushMailboxMessageCodec.parse(encoded.replace("😀", "\uD800"), sender, recipient))
        assertNull(PushMailboxMessageCodec.parse(PushMailboxMessageCodec.PREFIX + "|".repeat(64 * 1024), sender, recipient))
    }
}
