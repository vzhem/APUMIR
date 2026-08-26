package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPermissionsTest {

    @Test
    fun ownerCanPinWithoutAnyMask() {
        assertTrue(GroupPermissions.canPinMessages(GroupRole.OWNER, 0L))
    }

    @Test
    fun adminCannotPinWithoutExplicitRight() {
        assertFalse(GroupPermissions.canPinMessages(GroupRole.ADMIN, 0L))
        assertFalse(
            "право на бан не должно давать право на закреп",
            GroupPermissions.canPinMessages(GroupRole.ADMIN, GroupPermissions.Admin.BAN_USERS),
        )
    }

    @Test
    fun adminCanPinOnlyWithPinRight() {
        assertTrue(GroupPermissions.canPinMessages(GroupRole.ADMIN, GroupPermissions.Admin.PIN_MESSAGES))
        assertTrue(
            GroupPermissions.canPinMessages(
                GroupRole.ADMIN,
                GroupPermissions.Admin.PIN_MESSAGES or GroupPermissions.Admin.CHANGE_INFO,
            )
        )
    }

    @Test
    fun memberCanNeverPinEvenWithFullMemberMask() {
        assertFalse(GroupPermissions.canPinMessages(GroupRole.MEMBER, GroupPermissions.Member.ALL))
        assertFalse(GroupPermissions.canPinMessages("", GroupPermissions.Member.ALL))
    }

    @Test
    fun defaultAdminMaskExcludesDestructiveRights() {
        val mask = GroupPermissions.Admin.DEFAULT
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Admin.CHANGE_INFO))
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Admin.INVITE_USERS))
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Admin.PIN_MESSAGES))
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Admin.MANAGE_TOPICS))
        assertFalse("удаление чужих сообщений не выдаётся по умолчанию", GroupPermissions.has(mask, GroupPermissions.Admin.DELETE_MESSAGES))
        assertFalse("баны не выдаются по умолчанию", GroupPermissions.has(mask, GroupPermissions.Admin.BAN_USERS))
        assertFalse("назначение админов не выдаётся по умолчанию", GroupPermissions.has(mask, GroupPermissions.Admin.ADD_ADMINS))
    }

    @Test
    fun defaultMemberMaskAllowsSendingButNotChangeInfo() {
        val mask = GroupPermissions.Member.DEFAULT
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Member.SEND_MESSAGES))
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Member.SEND_MEDIA))
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Member.ADD_MEMBERS))
        assertFalse(GroupPermissions.has(mask, GroupPermissions.Member.CHANGE_INFO))
    }

    @Test
    fun withFlagTogglesSingleBit() {
        var mask = GroupPermissions.Admin.DEFAULT
        assertFalse(GroupPermissions.has(mask, GroupPermissions.Admin.BAN_USERS))
        mask = GroupPermissions.withFlag(mask, GroupPermissions.Admin.BAN_USERS, true)
        assertTrue(GroupPermissions.has(mask, GroupPermissions.Admin.BAN_USERS))
        mask = GroupPermissions.withFlag(mask, GroupPermissions.Admin.BAN_USERS, false)
        assertFalse(GroupPermissions.has(mask, GroupPermissions.Admin.BAN_USERS))
        assertEquals(GroupPermissions.Admin.DEFAULT, mask)
    }

    @Test
    fun hasRequiresAllBitsOfFlag() {
        val mask = GroupPermissions.Admin.PIN_MESSAGES
        assertFalse(GroupPermissions.has(mask, GroupPermissions.Admin.PIN_MESSAGES or GroupPermissions.Admin.BAN_USERS))
    }

    @Test
    fun adminAndMemberMasksAreIndependentNamespaces() {
        // Обе маски живут в разных полях, поэтому совпадение числовых значений
        // не должно давать участнику администраторских прав.
        assertEquals(GroupPermissions.Admin.CHANGE_INFO, GroupPermissions.Member.SEND_MESSAGES)
        val memberMask = GroupPermissions.Member.ALL
        assertFalse(GroupPermissions.canPinMessages(GroupRole.MEMBER, memberMask))
        assertFalse(GroupPermissions.canBan(GroupRole.MEMBER, memberMask))
        assertFalse(GroupPermissions.canManageTopics(GroupRole.MEMBER, memberMask))
    }

    @Test
    fun inviteFallsBackToMemberRight() {
        assertTrue(
            GroupPermissions.canInvite(GroupRole.MEMBER, 0L, GroupPermissions.Member.ADD_MEMBERS)
        )
        assertFalse(GroupPermissions.canInvite(GroupRole.MEMBER, 0L, GroupPermissions.Member.SEND_MESSAGES))
        assertTrue(GroupPermissions.canInvite(GroupRole.ADMIN, GroupPermissions.Admin.INVITE_USERS, 0L))
    }

    @Test
    fun bannedMemberLosesSendRightEvenWithFullMask() {
        assertFalse(
            GroupPermissions.canSendMessages(GroupRole.MEMBER, GroupPermissions.Member.ALL, isBanned = true)
        )
        assertTrue(
            GroupPermissions.canSendMessages(GroupRole.MEMBER, GroupPermissions.Member.ALL, isBanned = false)
        )
    }

    @Test
    fun unknownRoleNormalizesToMember() {
        assertEquals(GroupRole.MEMBER, GroupRole.normalize("SUPERUSER"))
        assertEquals(GroupRole.MEMBER, GroupRole.normalize(null))
        assertEquals(GroupRole.ADMIN, GroupRole.normalize("ADMIN"))
        assertFalse(GroupRole.isAdminOrOwner(GroupRole.MEMBER))
    }

    @Test
    fun everyDocumentedFlagIsListedForUi() {
        val adminFlags = GroupPermissions.Admin.entries.map { it.flag }.toSet()
        assertEquals(
            "каждое право администратора должно иметь подпись для экрана",
            GroupPermissions.Admin.ALL,
            adminFlags.fold(0L) { acc, f -> acc or f },
        )
        val memberFlags = GroupPermissions.Member.entries.map { it.flag }.toSet()
        assertEquals(
            GroupPermissions.Member.ALL,
            memberFlags.fold(0L) { acc, f -> acc or f },
        )
    }
}
