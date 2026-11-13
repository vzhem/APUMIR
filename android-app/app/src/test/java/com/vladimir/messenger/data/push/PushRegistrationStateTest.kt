package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationStateTest {
    private val ownerHash = "ab".repeat(32)
    private val peerHash = "34".repeat(32)
    private val epoch = "56".repeat(16)
    private val device = "78".repeat(16)
    private val tokenHash = "cd".repeat(32)

    private fun fresh() = PushRegistrationState.fresh(ownerHash, peerHash, 100, epoch, device)!!
    private fun reread(state: PushRegistrationState.State) = PushRegistrationState.decode(state.encode())!!

    @Test fun reservesBeforeEveryAttemptAndRejectsLateResponses() {
        val initial = reread(fresh())
        assertEquals(0L, initial.sequence)
        assertNull(initial.announcement())
        val attempt1 = reread(initial.reserve()!!)
        val attempt2 = reread(attempt1.reserve()!!)
        assertEquals(2L, attempt2.sequence)
        assertNull(attempt2.registered(attempt1.sequence, tokenHash, 1000))
        val stored = reread(attempt2.registered(attempt2.sequence, tokenHash, 1000)!!)
        assertEquals(1000L, stored.registeredAtMs)
        assertEquals(tokenHash, stored.tokenFingerprint)
        assertNull(stored.registered(attempt2.sequence, tokenHash, 1001))
        assertNotNull(stored.announcement())
        val packet = PushEpochProtocol.parse(stored.announcement())!!
        assertEquals(PushEpochProtocol.Action.OFFER, packet.action)
        assertEquals(ownerHash, packet.ownerBindingHash)
        assertEquals(peerHash, packet.targetBindingHash)
        assertEquals(epoch, packet.epochHex)
        assertFalse(stored.toString().contains(epoch))
        assertFalse(stored.toString().contains(tokenHash))
    }

    @Test fun revokeRequiresItsOwnReservationAndAcceptedServerResult() {
        val first = fresh().reserve()!!
        val ready = first.registered(first.sequence, tokenHash, 1000)!!
        val revoking = reread(ready.beginRevocation()!!)
        assertEquals(101L, revoking.generation)
        assertNull(revoking.announcement())
        assertNull(revoking.registered(first.sequence, tokenHash, 1001))
        assertNull(revoking.revoked(first.sequence)) // registration ACK is not revoke ACK
        val revoke1 = reread(revoking.reserve()!!)
        val revoke2 = reread(revoke1.reserve()!!)
        assertNull(revoke2.revoked(revoke1.sequence))
        val revoked = reread(revoke2.revoked(revoke2.sequence)!!)
        assertEquals(PushRegistrationState.Phase.REVOKED, revoked.phase)
        val packet = PushEpochProtocol.parse(revoked.announcement())!!
        assertEquals(PushEpochProtocol.Action.REVOKE, packet.action)
        assertEquals(101L, packet.generation)
        assertNull(revoked.reserve())
        assertNull(revoked.rotate("90".repeat(16)))
        val announced = reread(revoked.announced(revoked.generation)!!)
        val rotated = reread(announced.rotate("90".repeat(16))!!)
        assertEquals(102L, rotated.generation)
        assertEquals(0L, rotated.sequence)
        assertEquals(device, rotated.deviceHex)
        assertNull(rotated.announcement())
        assertNull(announced.rotate(epoch))
    }

    @Test fun announcementsRequireMatchingGenerationAndNeverImplyReceipt() {
        val attempt = fresh().reserve()!!
        val ready = attempt.registered(attempt.sequence, tokenHash, 1000)!!
        assertNull(ready.announced(99))
        val announced = ready.announced(100)!!
        assertEquals(100L, reread(announced).announcedGeneration)
        assertEquals(ready.announcement(), announced.announcement())
        val refreshing = announced.reserve()!!
        assertNotNull(refreshing.announcement()) // the prior server registration still exists
        assertNull(refreshing.registered(attempt.sequence, tokenHash, 1001))
    }

    @Test fun migrationToRouteIndexRequiresReregistrationWithoutResettingSequence() {
        val attempt = fresh().reserve()!!
        val accepted = attempt.registered(attempt.sequence, tokenHash, 1000)!!
        val previouslyAnnounced = accepted.announced(accepted.generation)!!
        val migrated = reread(previouslyAnnounced.requireReregistration())
        assertEquals(attempt.sequence, migrated.sequence)
        assertEquals(epoch, migrated.epochHex)
        assertEquals(device, migrated.deviceHex)
        assertEquals(0L, migrated.registeredAtMs)
        assertEquals(0L, migrated.announcedGeneration)
        assertNull(migrated.tokenFingerprint)
        assertNull(migrated.announcement())
        assertEquals(attempt.sequence + 1, migrated.reserve()!!.sequence)
    }

    @Test fun invalidOrNoncanonicalStateFailsClosed() {
        val wire = fresh().encode()
        val malformed = listOf(
            "", wire + "|extra", " $wire", wire.replace("|100|", "|0100|"),
            wire.replace("|100|", "|0|"), wire.replace("|100|", "|9007199254740992|"),
            wire.replace(ownerHash, ownerHash.uppercase()), wire.replace(peerHash, ownerHash),
            wire.replace(epoch, "0".repeat(32)), wire.replace(device, "0".repeat(32)),
            wire.replace("|a|", "|x|"), wire.replace("APUPREG1", "APUPREG2"),
            wire + "\n", "x".repeat(385),
        )
        malformed.forEachIndexed { index, candidate -> assertNull("case $index", PushRegistrationState.decode(candidate)) }
        assertNull(PushRegistrationState.fresh(ownerHash, peerHash, 0, epoch, device))
        assertNull(PushRegistrationState.fresh(ownerHash, peerHash, 1, "0".repeat(32), device))
        assertNull(PushRegistrationState.fresh(ownerHash, ownerHash, 1, epoch, device))
    }

    @Test fun reserveAndGenerationCapsNeverWrap() {
        val max = PushRegistrationState.MAX_NUMBER
        val sequenceExhausted = PushRegistrationState.State.create(
            PushRegistrationState.Phase.ACTIVE, 2, max, PushRegistrationState.Reservation.NONE,
            ownerHash, peerHash, epoch, device, 0, null, 0,
        )!!
        assertNull(sequenceExhausted.reserve())
        val generationExhausted = PushRegistrationState.fresh(ownerHash, peerHash, max, epoch, device)!!
        assertNull(generationExhausted.beginRevocation())
    }

    @Test fun deserializedPhaseAndReservationMustBeConsistent() {
        val attempt = fresh().reserve()!!
        val fields = attempt.encode().split('|').toMutableList()
        fields[4] = "r"
        assertNull(PushRegistrationState.decode(fields.joinToString("|")))
        fields[4] = "a"
        fields[9] = "1"
        fields[10] = "-" // registered time without token fingerprint
        assertNull(PushRegistrationState.decode(fields.joinToString("|")))
        fields[10] = tokenHash
        fields[11] = "99" // not this generation
        assertNull(PushRegistrationState.decode(fields.joinToString("|")))
    }
}
