package com.vladimir.messenger.data.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWakeAdmissionTest {
    private val localId = "pk_" + "1".repeat(32)
    private val peerId = "pk_" + "2".repeat(32)
    private val local = byteArrayOf(1, 2, 3)
    private val peer = byteArrayOf(4, 5, 6)
    private val route = "ab".repeat(32)
    private val payload = PushWakePayload.parse(mapOf("v" to "1", "route" to route))!!
    private val fresh = PushRegistrationState.fresh(
        PushEpochProtocol.bindingHash(local), PushEpochProtocol.bindingHash(peer), 100,
        "09".repeat(16), "56".repeat(16),
    )!!
    private val registered = fresh.reserve()!!.registered(1, "cc".repeat(32), 1000)!!

    private fun consistent(
        wake: PushWakePayload = payload,
        indexLocal: String = localId,
        indexPeer: String = peerId,
        generation: Long = 100,
        actualLocal: String? = localId,
        actualPeer: String? = peerId,
        own: ByteArray? = local,
        pin: ByteArray? = peer,
        state: PushRegistrationState.State? = registered,
        derived: String? = route,
    ) = PushWakeAdmission.isConsistent(wake, indexLocal, indexPeer, generation, actualLocal, actualPeer,
        own, pin, state, derived)

    @Test fun parsesOnlyCanonicalOpaqueV1DataAndRedactsTheRoute() {
        assertFalse(payload.toString().contains(route))
        assertTrue(consistent())
        val invalid = listOf(
            emptyMap(), mapOf("v" to "1"), mapOf("route" to route),
            mapOf("v" to "2", "route" to route), mapOf("v" to "01", "route" to route),
            mapOf("v" to "1", "route" to route, "text" to "private"),
            mapOf("v" to "1", "route" to route.uppercase()), mapOf("v" to "1", "route" to route + "0"),
            mapOf("v" to "1", "route" to route.drop(1)), mapOf("v" to "1", "route" to route + "\n"),
            mapOf("v" to "1", "route" to "g".repeat(64)), mapOf("V" to "1", "route" to route),
        )
        invalid.forEach { assertNull(PushWakePayload.parse(it)) }
    }

    @Test fun indexIsOnlyAHintAndMustMatchTheCurrentProfileAndContact() {
        assertFalse(consistent(actualLocal = null))
        assertFalse(consistent(actualPeer = null))
        assertFalse(consistent(actualLocal = peerId))
        assertFalse(consistent(actualPeer = localId))
        assertFalse(consistent(indexLocal = "pk_bad"))
        assertFalse(consistent(indexPeer = localId, actualPeer = localId))
        assertFalse(consistent(generation = 99))
        assertFalse(consistent(generation = 101))
    }

    @Test fun changedMissingOrOversizedBindingsFailClosed() {
        assertFalse(consistent(own = null))
        assertFalse(consistent(pin = null))
        assertFalse(consistent(own = byteArrayOf()))
        assertFalse(consistent(pin = ByteArray(513)))
        assertFalse(consistent(own = peer, pin = peer))
        assertFalse(consistent(own = byteArrayOf(1, 2, 4)))
        assertFalse(consistent(pin = byteArrayOf(4, 5, 7)))
    }

    @Test fun onlyTheCurrentConfirmedActiveGenerationAndFreshRouteCanMatch() {
        assertFalse(consistent(state = null))
        assertFalse(consistent(state = fresh))
        assertFalse(consistent(state = fresh.reserve()))
        assertFalse(consistent(state = registered.beginRevocation()))
        val revoked = registered.beginRevocation()!!.reserve()!!.revoked(2)!!
        assertFalse(consistent(state = revoked, generation = revoked.generation))
        assertFalse(consistent(derived = null))
        assertFalse(consistent(derived = "cd".repeat(32)))
        assertFalse(consistent(derived = route.uppercase()))
        val otherWake = PushWakePayload.parse(mapOf("v" to "1", "route" to "cd".repeat(32)))!!
        assertFalse(consistent(wake = otherWake))
        // A reserved renewal does not revoke an already confirmed generation.
        assertTrue(consistent(state = registered.reserve()))
    }
}
