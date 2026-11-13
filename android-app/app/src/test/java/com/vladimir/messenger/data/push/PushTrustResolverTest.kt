package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTrustResolverTest {
    private val nodeId = "pk_" + "1".repeat(32)
    private val peerId = "pk_" + "2".repeat(32)
    private val localBinding = ByteArray(4) { 1 }
    private val peerBinding = ByteArray(4) { 2 }
    private val secret = ByteArray(32) { 3 }

    private fun state(phase: String = "a", registeredAt: String = "1", epoch: String = "ab".repeat(16)): PushRegistrationState.State =
        PushRegistrationState.decode(
            listOf(
                "APUPREG1", phase, "1", "1", "-", "09".repeat(32), "0a".repeat(32), epoch,
                "56".repeat(16), registeredAt, if (registeredAt == "0") "-" else "0b".repeat(32), "0",
            ).joinToString("|"),
        ) ?: error("invalid test state")

    private fun resolver(
        state: PushRegistrationState.State? = state(),
        pinned: ByteArray? = peerBinding,
        secretValue: ByteArray? = secret,
        local: ByteArray? = localBinding,
    ) = PushTrustResolver(
        localNodeId = { nodeId },
        localBinding = { local?.copyOf() },
        localSecret = { secretValue?.copyOf() },
        pinnedPeer = { pinned?.copyOf() },
        registration = {
            if (state == null) PushRegistrationStore.Result.Missing else PushRegistrationStore.Result.Ready(state)
        },
    )

    @Test fun aLivePairYieldsAPassBoundToTheCurrentValues() = runBlocking {
        val pass = resolver().resolve(peerId)
        assertNotNull(pass)
        pass!!.use {
            assertEquals(nodeId, it.localId)
            assertEquals(peerId, it.peerId)
            assertEquals(state(), it.registration)
            assertTrue(it.localBinding.contentEquals(localBinding))
            assertTrue(it.pinnedPeerBinding.contentEquals(peerBinding))
        }
    }

    @Test fun missingValuesAreNeverTurnedIntoTrust() = runBlocking {
        assertNull(resolver(pinned = null).resolve(peerId))
        assertNull(resolver(secretValue = null).resolve(peerId))
        assertNull(resolver(local = null).resolve(peerId))
        assertNull(resolver(state = null).resolve(peerId))
        // A self-pin cannot be a peer.
        assertNull(resolver(pinned = localBinding).resolve(peerId))
    }

    @Test fun aReceivingPassRequiresAServerConfirmedRegistration() = runBlocking {
        val unregistered = state(registeredAt = "0")
        // Registration may proceed before the first acceptance, receiving may not.
        assertNotNull(resolver(state = unregistered).resolveForRegistration(peerId))
        assertNull(resolver(state = unregistered).resolve(peerId))

        val revoking = state(phase = "r", registeredAt = "0")
        assertNull(resolver(state = revoking).resolve(peerId))
        assertNull(resolver(state = revoking).resolveForRegistration(peerId))
    }

    @Test fun anUnreadableJournalIsNotAnEmptyJournal() = runBlocking {
        val unavailable = PushTrustResolver(
            localNodeId = { nodeId },
            localBinding = { localBinding.copyOf() },
            localSecret = { secret.copyOf() },
            pinnedPeer = { peerBinding.copyOf() },
            registration = { PushRegistrationStore.Result.Unavailable },
        )
        assertNull(unavailable.resolve(peerId))
    }

    @Test fun aPassNeverInventsMaterialFromAFailingOrMalformedDerivation() = runBlocking {
        // A failing FFI must not become a capability.
        val failing = PushTrustResolver(
            localNodeId = { nodeId },
            localBinding = { localBinding.copyOf() },
            localSecret = { secret.copyOf() },
            pinnedPeer = { peerBinding.copyOf() },
            registration = { PushRegistrationStore.Result.Ready(state()) },
        ).resolve(peerId)!!
        failing.use { pass ->
            assertNull(pass.derive("ab".repeat(16)))
            // A malformed or empty epoch is rejected before the FFI is reached.
            assertNull(pass.derive(""))
            assertNull(pass.derive("00"))
            assertNull(pass.derive("0A".repeat(16)))
        }
        assertNotNull(failing)
    }

    @Test fun aSnapshotIsTheSameLiveReadsWithoutDerivation() = runBlocking {
        val snapshot = resolver().snapshot(nodeId, peerId)
        assertNotNull(snapshot)
        snapshot!!.apply {
            assertEquals(nodeId, senderId)
            assertEquals(peerId, recipientId)
            assertTrue(localBinding.contentEquals(this@PushTrustResolverTest.localBinding))
        }
        assertNull(resolver(pinned = null).snapshot(nodeId, peerId))
    }

    @Test fun cancellationPropagatesFromEveryReader() = runBlocking {
        var cancelled = false
        try {
            PushTrustResolver(
                localNodeId = { throw CancellationException("cancelled") },
                localBinding = { null },
                localSecret = { null },
                pinnedPeer = { null },
                registration = { PushRegistrationStore.Result.Missing },
            ).resolve(peerId)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
