package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Наследование владения (GroupOwnership): кто и после какого молчания может
 * забрать права владельца, удалившегося из сети.
 */
class GroupOwnershipTest {

    private val day = 24L * 60 * 60 * 1000

    // ── requiredSilenceMs ────────────────────────────────────────────────────

    /** Администратору - короткий срок, участнику без админов - длинный. */
    @Test
    fun requiredSilenceByRole() {
        assertEquals(GroupOwnership.ADMIN_SILENCE_MS, GroupOwnership.requiredSilenceMs(GroupRole.ADMIN, adminsExist = true))
        assertEquals(GroupOwnership.ADMIN_SILENCE_MS, GroupOwnership.requiredSilenceMs(GroupRole.ADMIN, adminsExist = false))
        assertEquals(GroupOwnership.MEMBER_SILENCE_MS, GroupOwnership.requiredSilenceMs(GroupRole.MEMBER, adminsExist = false))
        // Участнику при живых администраторах пути нет - только через админство.
        assertNull(GroupOwnership.requiredSilenceMs(GroupRole.MEMBER, adminsExist = true))
        // Владелец не захватывает сам себя, неизвестная роль - не участник.
        assertNull(GroupOwnership.requiredSilenceMs(GroupRole.OWNER, adminsExist = false))
        assertNull(GroupOwnership.requiredSilenceMs("ЧУЖОЕ", adminsExist = false))
    }

    // ── canTakeOver ──────────────────────────────────────────────────────────

    /** Живой владелец: ни администратор, ни участник права не забирают. */
    @Test
    fun activeOwnerBlocksEveryone() {
        val now = 1_000_000_000_000L
        val seenRecently = now - day
        assertFalse(
            GroupOwnership.canTakeOver(GroupRole.ADMIN, adminsExist = true, ownerLastSeenMs = seenRecently, nowMs = now)
        )
        assertFalse(
            GroupOwnership.canTakeOver(GroupRole.MEMBER, adminsExist = false, ownerLastSeenMs = seenRecently, nowMs = now)
        )
    }

    /** Владелец молчал 90 дней - администратор забирает права. */
    @Test
    fun adminTakesOverAfterSilence() {
        val now = 1_000_000_000_000L
        val silent = now - GroupOwnership.ADMIN_SILENCE_MS - 1
        assertTrue(
            GroupOwnership.canTakeOver(GroupRole.ADMIN, adminsExist = true, ownerLastSeenMs = silent, nowMs = now)
        )
        // Ровно на границе - ещё нельзя: срок должен ПРОЙТИ.
        val borderline = now - GroupOwnership.ADMIN_SILENCE_MS
        assertFalse(
            GroupOwnership.canTakeOver(GroupRole.ADMIN, adminsExist = true, ownerLastSeenMs = borderline, nowMs = now)
        )
    }

    /** Участник без администраторов ждёт вдвое дольше. */
    @Test
    fun memberTakesOverOnlyWithoutAdminsAndLater() {
        val now = 1_000_000_000_000L
        val adminSilence = now - GroupOwnership.ADMIN_SILENCE_MS - 1
        // Столько молчания хватило бы администратору, но не участнику.
        assertFalse(
            GroupOwnership.canTakeOver(GroupRole.MEMBER, adminsExist = false, ownerLastSeenMs = adminSilence, nowMs = now)
        )
        val memberSilence = now - GroupOwnership.MEMBER_SILENCE_MS - 1
        assertTrue(
            GroupOwnership.canTakeOver(GroupRole.MEMBER, adminsExist = false, ownerLastSeenMs = memberSilence, nowMs = now)
        )
        // Даже при долгом молчании участник не обходит живых администраторов.
        assertFalse(
            GroupOwnership.canTakeOver(GroupRole.MEMBER, adminsExist = true, ownerLastSeenMs = memberSilence, nowMs = now)
        )
    }

    /** Владелец, которого этот телефон ни разу не видел, молчавшим не считается. */
    @Test
    fun unknownOwnerBlocksClaim() {
        val now = 1_000_000_000_000L
        assertFalse(
            GroupOwnership.canTakeOver(GroupRole.ADMIN, adminsExist = true, ownerLastSeenMs = null, nowMs = now)
        )
        // Нулевая отметка - то же незнание.
        assertFalse(
            GroupOwnership.canTakeOver(GroupRole.ADMIN, adminsExist = true, ownerLastSeenMs = 0L, nowMs = now)
        )
    }

    // ── shouldReplaceClaimer ────────────────────────────────────────────────

    /** Спор двух наследников решает меньший nodeId. */
    @Test
    fun smallerNodeIdWinsDispute() {
        assertTrue(GroupOwnership.shouldReplaceClaimer("pk_bbb", "pk_aaa"))
        assertFalse(GroupOwnership.shouldReplaceClaimer("pk_aaa", "pk_bbb"))
        // Одинаковые претенденты - уже применён, менять ничего не надо.
        assertFalse(GroupOwnership.shouldReplaceClaimer("pk_aaa", "pk_aaa"))
        assertFalse(GroupOwnership.shouldReplaceClaimer("pk_aaa", ""))
    }
}
