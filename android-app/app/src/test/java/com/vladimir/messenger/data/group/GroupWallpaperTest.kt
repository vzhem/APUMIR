package com.vladimir.messenger.data.group

import org.junit.Assert.*
import org.junit.Test

class GroupWallpaperTest {
    private val now = 1_792_150_000_000L
    private val revision = "$now-${"a".repeat(32)}"
    @Test fun onlyAuthorizedAdministratorsCanSetSharedBackground() {
        assertTrue(GroupWallpaper.canChange(GroupRole.OWNER, 0, 0, false))
        assertTrue(GroupWallpaper.canChange(GroupRole.ADMIN, GroupPermissions.Admin.CHANGE_INFO, 0, false))
        assertFalse(GroupWallpaper.canChange(GroupRole.ADMIN, 0, 0, false))
        assertFalse(GroupWallpaper.canChange(GroupRole.ADMIN, GroupPermissions.Admin.ALL, 0, true))
        assertFalse(GroupWallpaper.canChange(GroupRole.MEMBER, GroupPermissions.Admin.ALL, GroupPermissions.Member.ALL, false))
    }
    @Test fun personalOverrideWinsUntilRemoved() {
        assertEquals("preset:mint", GroupWallpaper.resolve("preset:mint", "preset:sky"))
        assertEquals("preset:sky", GroupWallpaper.resolve(null, "preset:sky"))
        assertNull(GroupWallpaper.resolve(null, ""))
        assertEquals("", GroupWallpaper.resolve("", "preset:sky"))
    }
    @Test fun roundTripPresetAndRemoval() {
        for (value in listOf("preset:mint", "")) {
            val packet = GroupWire.parse(GroupWire.buildWallpaper("group", revision, value).single()) as GroupWire.Packet.Wallpaper
            assertEquals(value, GroupWallpaperParts().receive(packet.groupId, "owner", packet.revision,
                packet.index, packet.total, packet.data, now))
        }
        assertTrue(GroupWire.parse(GroupWire.buildWallpaperRequest("group")) is GroupWire.Packet.WallpaperRequest)
    }
    @Test fun imagePacketsFitBudgetAndAssembleOutOfOrder() {
        val bytes = ByteArray(48 * 1024) { 7 }.also { it[0] = 0xff.toByte(); it[1] = 0xd8.toByte() }
        val value = "image:" + java.util.Base64.getEncoder().encodeToString(bytes)
        val packets = GroupWire.buildWallpaper("group", revision, value)
        assertTrue(packets.all { it.length < 2300 })
        val assembler = GroupWallpaperParts()
        var result: String? = null
        packets.reversed().forEach { raw ->
            val p = GroupWire.parse(raw) as GroupWire.Packet.Wallpaper
            result = assembler.receive(p.groupId, "admin", p.revision, p.index, p.total, p.data, now)
        }
        assertEquals(value, result)
    }
    @Test fun rejectUrlsUnknownPresetsOversizeAndFutureRevisions() {
        listOf("content://local/image", "https://example.org/a.jpg", "preset:unknown", "image:invalid",
            "image:" + "a".repeat(GroupWallpaper.MAX_VALUE)).forEach { assertFalse(GroupWallpaper.valid(it)) }
        assertFalse(GroupWallpaper.validRevision("${now + 300_001}-${"a".repeat(32)}", now))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|wallpaper|group|$revision|99|1|preset:mint"))
        assertNull(GroupWire.parse("${GroupWire.PREFIX}|wallpaper|group|$revision|0|999|preset:mint"))
    }
    @Test fun cannotMixRevisionsOrUseExpiredParts() {
        val assembler = GroupWallpaperParts()
        assertNull(assembler.receive("g", "admin", revision, 0, 2, "preset:", now))
        val newer = "${now + 1}-${"b".repeat(32)}"
        assertNull(assembler.receive("g", "admin", newer, 1, 2, "mint", now))
        assertNull(assembler.receive("g", "admin", revision, 0, 2, "preset:", now))
        assertNull(assembler.receive("g", "admin", newer, 0, 2, "preset:", now + 300_001))
    }
    @Test fun duplicateReplyFromAnotherAuthorizedAdminCanCompleteTheSameRevision() {
        val assembler = GroupWallpaperParts()
        assertNull(assembler.receive("g", "admin1", revision, 0, 2, "preset:", now))
        assertEquals("preset:mint", assembler.receive("g", "owner", revision, 1, 2, "mint", now))
    }
}
