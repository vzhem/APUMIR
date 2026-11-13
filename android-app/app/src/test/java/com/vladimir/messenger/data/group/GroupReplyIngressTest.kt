package com.vladimir.messenger.data.group

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The callback models the author/first-quote rule; Room tests cover the real SQL. */
class GroupReplyIngressTest {
    private val valid = GroupWire.Packet.Reply("group", "message", "target", "Alice", "quote")
    private val conflict = valid.copy(replyToId = "other", replyText = "other quote")

    @Test fun earlySendersDoNotReplaceEachOtherAndOnlyParentAuthorCanApply() = runBlocking {
        var parent: String? = null
        var quote: GroupWire.Packet.Reply? = null
        val attempts = mutableListOf<String>()
        val ingress = GroupReplyIngress(
            localId = { "owner" }, isMember = { _, _ -> true }, parentGroup = { parent },
            apply = { _, sender, packet ->
                attempts += sender
                if (sender == "author" && quote == null) quote = packet
            }, capacity = 20, ttlMs = 1000, clock = { 0 },
        )
        ingress.receive("author", valid)
        ingress.receive("other-member", conflict)
        parent = "group"
        ingress.parentInserted("group", "message")
        assertEquals(listOf("author", "other-member"), attempts)
        assertEquals(valid, quote)
    }

    @Test fun insertionBetweenLookupAndBufferPutDoesNotStrandTheReply() = runBlocking {
        var parent: String? = null
        var lookups = 0
        val applied = mutableListOf<String>()
        lateinit var ingress: GroupReplyIngress
        ingress = GroupReplyIngress(
            localId = { "owner" }, isMember = { _, _ -> true },
            parentGroup = {
                lookups++
                if (lookups == 1) {
                    parent = "group"
                    ingress.parentInserted("group", "message")
                    null // the first lookup completed before insertion
                } else parent
            },
            apply = { _, sender, _ -> applied += sender }, capacity = 20, ttlMs = 1000, clock = { 0 },
        )
        ingress.receive("author", valid)
        assertEquals(listOf("author"), applied)
        ingress.parentInserted("group", "message")
        assertEquals(listOf("author"), applied)
    }

    @Test fun changingLocalIdentityOrLeavingTheGroupCannotDrainAnotherOwnersBuffer() = runBlocking {
        var owner: String? = "owner-a"
        var member = true
        var parent: String? = null
        var applied: String? = null
        val ingress = GroupReplyIngress(
            localId = { owner }, isMember = { _, _ -> member }, parentGroup = { parent },
            apply = { _, sender, _ -> applied = sender }, capacity = 20, ttlMs = 1000, clock = { 0 },
        )
        ingress.receive("author", valid)
        parent = "group"
        owner = "owner-b"
        ingress.parentInserted("group", "message")
        assertNull(applied)
        owner = "owner-a"
        member = false
        ingress.parentInserted("group", "message")
        assertNull(applied)
        member = true
        ingress.parentInserted("group", "message")
        assertEquals("author", applied)
    }

    @Test fun identityChangeWhileCheckingMembershipDoesNotDrainTheOldBuffer() = runBlocking {
        var owner = "owner-a"
        var parent: String? = null
        var switchWhileChecking = false
        var applications = 0
        val ingress = GroupReplyIngress(
            localId = { owner },
            isMember = { _, _ -> if (switchWhileChecking) owner = "owner-b"; true },
            parentGroup = { parent }, apply = { _, _, _ -> applications++ },
            capacity = 20, ttlMs = 1000, clock = { 0 },
        )
        ingress.receive("author", valid)
        parent = "group"
        switchWhileChecking = true
        ingress.parentInserted("group", "message")
        assertEquals(0, applications)
        switchWhileChecking = false
        owner = "owner-a"
        ingress.parentInserted("group", "message")
        assertEquals(1, applications)
    }

    @Test fun missingIdentityOrMembershipAndBlankSenderAreRejected() = runBlocking {
        var owner: String? = null
        var member = true
        var attempts = 0
        val ingress = GroupReplyIngress(
            localId = { owner }, isMember = { _, _ -> member }, parentGroup = { "group" },
            apply = { _, _, _ -> attempts++ }, capacity = 20, ttlMs = 1000, clock = { 0 },
        )
        ingress.receive("author", valid)
        owner = "owner"
        member = false
        ingress.receive("author", valid)
        member = true
        ingress.receive("", valid)
        assertEquals(0, attempts)
        ingress.receive("author", valid)
        assertEquals(1, attempts)
    }

    @Test fun identityChangeDuringReceiveDoesNotBufferForTheOldOwner() = runBlocking {
        var owner = "owner-a"
        var phase = "membership"
        var parent: String? = null
        var lookups = 0
        val applications = mutableListOf<String>()
        val ingress = GroupReplyIngress(
            localId = { owner },
            isMember = { _, _ -> if (phase == "membership") owner = "owner-b"; true },
            parentGroup = {
                lookups++
                if (phase == "parent") owner = "owner-b"
                parent
            },
            apply = { localOwner, _, _ -> applications += localOwner },
            capacity = 20, ttlMs = 1000, clock = { 0 },
        )
        ingress.receive("author", valid)
        assertEquals(0, lookups)
        owner = "owner-a"
        phase = "parent"
        ingress.receive("author", valid)
        assertEquals(1, lookups)
        owner = "owner-a"
        phase = "stable"
        parent = "group"
        ingress.parentInserted("group", "message")
        assertEquals(emptyList<String>(), applications)
        ingress.receive("author", valid)
        assertEquals(listOf("owner-a"), applications)
    }
}
