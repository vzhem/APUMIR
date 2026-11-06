package com.vladimir.messenger.data.avatavideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тест провода видеоаватаров: анонс и просьба переживают дорогу и не
 * пропускают мусор. Всё, что здесь проверяется, потом видит GroupRouter на
 * телефоне: старый клиент глотает конверт целиком, новый — разбирает ровно то,
 * что построено здесь.
 */
class AvatarVideoWireTest {

    private val sha = "a".repeat(63) + "7"

    @Test
    fun announceRoundTrips() {
        val wire = AvatarVideoWire.announce("node-1", sha, 24L * 1024 * 1024, circle = true, updatedAtMs = 123456L)
        assertTrue(AvatarVideoWire.isPacket(wire))
        val parsed = AvatarVideoWire.parse(wire) as AvatarVideoWire.Packet.Announce
        assertEquals("node-1", parsed.ownerId)
        assertEquals(sha, parsed.sha256)
        assertEquals(24L * 1024 * 1024, parsed.bytes)
        assertTrue(parsed.circle)
        assertEquals(123456L, parsed.updatedAtMs)
    }

    @Test
    fun emptyShaAnnounceMeansRetract() {
        val wire = AvatarVideoWire.announce("node-1", "", 0L, circle = false, updatedAtMs = 1L)
        val parsed = AvatarVideoWire.parse(wire) as AvatarVideoWire.Packet.Announce
        assertEquals("", parsed.sha256)
        assertEquals(0L, parsed.bytes)
        assertFalse(parsed.circle)
    }

    @Test
    fun readyRoundTrips() {
        val wire = AvatarVideoWire.ready("node-2", sha)
        val parsed = AvatarVideoWire.parse(wire) as AvatarVideoWire.Packet.Ready
        assertEquals("node-2", parsed.ownerId)
        assertEquals(sha, parsed.sha256)
    }

    @Test
    fun garbageIsSwallowedNotParsed() {
        assertNull(AvatarVideoWire.parse(null))
        assertNull(AvatarVideoWire.parse("просто текст"))
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "zz|a|b"))
        // Нехватка полей, битый sha, битый размер, битый круг, битое время.
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "an|node-1|$sha|10"))
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "an|node-1|xyz|10|1|1"))
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "an|node-1|$sha|NaN|1|1"))
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "an|node-1|$sha|10|2|1"))
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "an|node-1|$sha|10|1|-5"))
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "rd|node-1|nope"))
        // Разделитель полей и управляющие символы внутри идентификатора.
        assertNull(AvatarVideoWire.parse(AvatarVideoWire.PREFIX + "an|node|1|$sha|10|1|1"))
        // Больше предела аватара не бывает даже у объявления.
        assertNull(
            AvatarVideoWire.parse(
                AvatarVideoWire.PREFIX + "an|node-1|$sha|${com.vladimir.messenger.data.profile.VideoAvatarPolicy.MAX_EXPORT_BYTES + 1}|1|1",
            ),
        )
    }

    @Test
    fun fileNamesAreReservedAndUniquePerRoll() {
        assertTrue(AvatarVideoWire.reserved(AvatarVideoWire.fileName(sha)))
        assertTrue(AvatarVideoWire.fileName(sha).startsWith(AvatarVideoWire.FILE_PREFIX))
        assertFalse(AvatarVideoWire.reserved("photo.jpg"))
        assertFalse(AvatarVideoWire.reserved("apu-story-video-x.mp4"))
        assertNotNull(AvatarVideoWire.fileName(sha))
    }
}
