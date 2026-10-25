package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила красной кнопки SOS: кто вправе нажимать при каждой настройке,
 * пауза от повторного нажатия (не меньше 10 секунд) и границы числа
 * тревожных сообщений (3..5).
 */
class GroupSosTest {

    @Test
    fun everyoneMayPressWhenRoleAll() {
        assertTrue(GroupSos.canPress(GroupRole.MEMBER, GroupSos.PRESS_ALL, isBanned = false))
        assertTrue(GroupSos.canPress(GroupRole.ADMIN, GroupSos.PRESS_ALL, isBanned = false))
        assertTrue(GroupSos.canPress(GroupRole.OWNER, GroupSos.PRESS_ALL, isBanned = false))
    }

    @Test
    fun adminsOnlyModeExcludesMembers() {
        assertFalse(GroupSos.canPress(GroupRole.MEMBER, GroupSos.PRESS_ADMINS, isBanned = false))
        assertTrue(GroupSos.canPress(GroupRole.ADMIN, GroupSos.PRESS_ADMINS, isBanned = false))
        assertTrue(GroupSos.canPress(GroupRole.OWNER, GroupSos.PRESS_ADMINS, isBanned = false))
    }

    @Test
    fun ownerOnlyModeExcludesAdmins() {
        assertFalse(GroupSos.canPress(GroupRole.MEMBER, GroupSos.PRESS_OWNER, isBanned = false))
        assertFalse(GroupSos.canPress(GroupRole.ADMIN, GroupSos.PRESS_OWNER, isBanned = false))
        assertTrue(GroupSos.canPress(GroupRole.OWNER, GroupSos.PRESS_OWNER, isBanned = false))
    }

    @Test
    fun bannedMemberNeverPresses() {
        assertFalse(GroupSos.canPress(GroupRole.OWNER, GroupSos.PRESS_ALL, isBanned = true))
        assertFalse(GroupSos.canPress(GroupRole.MEMBER, GroupSos.PRESS_ALL, isBanned = true))
    }

    @Test
    fun unknownPressRoleFallsBackToAllAndUnknownRoleIsMember() {
        assertEquals(GroupSos.PRESS_ALL, GroupSos.normalizePressRole("bogus"))
        assertEquals(GroupSos.PRESS_ALL, GroupSos.normalizePressRole(null))
        assertTrue(GroupSos.canPress("bogus-role", "bogus-setting", isBanned = false))
        assertFalse(GroupSos.canPress("bogus-role", GroupSos.PRESS_ADMINS, isBanned = false))
    }

    @Test
    fun countIsClampedToThreeToFive() {
        assertEquals(3, GroupSos.clampCount(0))
        assertEquals(3, GroupSos.clampCount(2))
        assertEquals(3, GroupSos.clampCount(3))
        assertEquals(4, GroupSos.clampCount(4))
        assertEquals(5, GroupSos.clampCount(5))
        assertEquals(5, GroupSos.clampCount(42))
        assertEquals(listOf(3, 4, 5), GroupSos.COUNT_CHOICES)
    }

    @Test
    fun cooldownLocksForAtLeastTenSeconds() {
        assertTrue(GroupSos.COOLDOWN_MS >= 10_000L)
        val sent = 1_000_000L
        assertEquals(GroupSos.COOLDOWN_MS, GroupSos.cooldownRemainingMs(sent, sent))
        assertEquals(1L, GroupSos.cooldownRemainingMs(sent, sent + GroupSos.COOLDOWN_MS - 1))
        assertEquals(0L, GroupSos.cooldownRemainingMs(sent, sent + GroupSos.COOLDOWN_MS))
        assertEquals(0L, GroupSos.cooldownRemainingMs(sent, sent + GroupSos.COOLDOWN_MS + 60_000L))
    }

    @Test
    fun missingOrFuturePressMarkDoesNotLock() {
        assertEquals(0L, GroupSos.cooldownRemainingMs(0L, 1_000_000L))
        assertEquals(0L, GroupSos.cooldownRemainingMs(Long.MAX_VALUE, 1_000_000L))
    }
}
