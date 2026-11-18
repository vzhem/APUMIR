package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationFlowTest {
    private val owner = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
    private val capability = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a"
    private val device = "56".repeat(16)
    private val token = "fcm-test-token-abcdefghijklmnop"

    private fun request(action: String, sequence: Long) = PushRegistrationRequest(
        action = action, owner = owner, capability = capability, device = device, sequence = sequence,
        issuedAt = 1_700_000_000, token = if (action == "register") token else "", signature = "51".repeat(48),
    )

    @Test fun aStoredRegistrationIndexesTheRouteOnlyForTheReservedSequence() = runBlocking {
        val journal = FakeJournal()
        val transport = FakeTransport(PushHttpClient.RegistrationResult.STORED)
        val flow = PushRegistrationFlow(journal, transport, nowMs = { 1_700_000_000_000 },
            issuedAtSeconds = { 1_700_000_000 })
        val signer = PushRegistrationFlow.Signer { state, issuedAt, _ -> request("register", state.sequence) }

        assertEquals(
            PushRegistrationFlow.Result.Registered,
            flow.register(journal.participants, signer, token) { "ab".repeat(32) },
        )
        assertEquals(listOf("reserve", "registered:1"), journal.events)
        assertEquals(1, transport.calls)
    }

    @Test fun aStoredAnswerWithoutADerivableRouteIsNotReportedAsRegistered() = runBlocking {
        val journal = FakeJournal()
        val flow = PushRegistrationFlow(journal, FakeTransport(PushHttpClient.RegistrationResult.STORED),
            nowMs = { 1_700_000_000_000 }, issuedAtSeconds = { 1_700_000_000 })
        val signer = PushRegistrationFlow.Signer { state, _, _ -> request("register", state.sequence) }
        assertEquals(PushRegistrationFlow.Result.Unavailable, flow.register(journal.participants, signer, token) { null })
        assertTrue(journal.events.none { it.startsWith("registered") })
    }

    @Test fun everyNonStoredAnswerLeavesTheRouteUnindexed() = runBlocking {
        for ((answer, expected) in listOf(
            PushHttpClient.RegistrationResult.STALE to PushRegistrationFlow.Result.Conflict,
            PushHttpClient.RegistrationResult.THROTTLED to PushRegistrationFlow.Result.Throttled,
            PushHttpClient.RegistrationResult.REJECTED to PushRegistrationFlow.Result.Rejected,
            PushHttpClient.RegistrationResult.UNAVAILABLE to PushRegistrationFlow.Result.Unavailable,
        )) {
            val journal = FakeJournal()
            val flow = PushRegistrationFlow(journal, FakeTransport(answer),
                nowMs = { 1_700_000_000_000 }, issuedAtSeconds = { 1_700_000_000 })
            val signer = PushRegistrationFlow.Signer { state, _, _ -> request("register", state.sequence) }
            assertEquals(expected, flow.register(journal.participants, signer, token) { "ab".repeat(32) })
            assertTrue(journal.events.none { it.startsWith("registered") })
            assertEquals(1, journal.reserves)
        }
    }

    @Test fun aMalformedTokenOrUnusableJournalNeverReachesTheNetwork() = runBlocking {
        val journal = FakeJournal()
        val transport = FakeTransport(PushHttpClient.RegistrationResult.STORED)
        val flow = PushRegistrationFlow(journal, transport, nowMs = { 1_700_000_000_000 },
            issuedAtSeconds = { 1_700_000_000 })
        val signer = PushRegistrationFlow.Signer { state, _, _ -> request("register", state.sequence) }

        assertEquals(PushRegistrationFlow.Result.Rejected, flow.register(journal.participants, signer, "short") { "ab" })
        assertEquals(0, transport.calls)

        journal.reserveResult = PushRegistrationStore.Result.Rejected
        assertEquals(PushRegistrationFlow.Result.Rejected, flow.register(journal.participants, signer, token) { "ab" })
        assertEquals(0, transport.calls)
    }

    @Test fun aSignerThatRefusesTheReservationSendsNothing() = runBlocking {
        val journal = FakeJournal()
        val transport = FakeTransport(PushHttpClient.RegistrationResult.STORED)
        val flow = PushRegistrationFlow(journal, transport, nowMs = { 1_700_000_000_000 },
            issuedAtSeconds = { 1_700_000_000 })
        val refusing = PushRegistrationFlow.Signer { _, _, _ -> null }
        assertEquals(PushRegistrationFlow.Result.Rejected, flow.register(journal.participants, refusing, token) { "ab" })
        assertEquals(0, transport.calls)
        assertEquals(1, journal.reserves)
    }

    @Test fun revokeClearsTheLocalIndexFirstAndNeverSendsAToken() = runBlocking {
        val journal = FakeJournal()
        val transport = FakeTransport(PushHttpClient.RegistrationResult.UNAVAILABLE)
        val flow = PushRegistrationFlow(journal, transport, nowMs = { 1_700_000_000_000 },
            issuedAtSeconds = { 1_700_000_000 })
        val signer = PushRegistrationFlow.Signer { state, _, sent ->
            assertTrue(sent.isEmpty())
            if (state.reservation != PushRegistrationState.Reservation.REVOKE) null
            else request("revoke", state.sequence)
        }
        assertEquals(PushRegistrationFlow.Result.Unavailable, flow.revoke(journal.participants, signer))
        assertEquals(listOf("beginRevocation", "reserve"), journal.events)
        assertEquals(1, transport.calls)
        assertTrue(journal.events.none { it.startsWith("revoked") })
    }

    @Test fun anAcceptedRevokeMarksTheJournalRevoked() = runBlocking {
        val journal = FakeJournal()
        val flow = PushRegistrationFlow(journal, FakeTransport(PushHttpClient.RegistrationResult.STORED),
            nowMs = { 1_700_000_000_000 }, issuedAtSeconds = { 1_700_000_000 })
        val signer = PushRegistrationFlow.Signer { state, _, _ -> request("revoke", state.sequence) }
        assertEquals(PushRegistrationFlow.Result.Revoked, flow.revoke(journal.participants, signer))
        assertTrue(journal.events.contains("revoked:1"))
    }

    @Test fun recallersThatAreNotRevokingNeverSend() = runBlocking {
        val journal = FakeJournal()
        journal.beginRevokeResult = PushRegistrationStore.Result.Rejected
        val transport = FakeTransport(PushHttpClient.RegistrationResult.STORED)
        val flow = PushRegistrationFlow(journal, transport, nowMs = { 1_700_000_000_000 },
            issuedAtSeconds = { 1_700_000_000 })
        val signer = PushRegistrationFlow.Signer { state, _, _ -> request("revoke", state.sequence) }
        assertEquals(PushRegistrationFlow.Result.Rejected, flow.revoke(journal.participants, signer))
        assertEquals(0, transport.calls)
    }

    @Test fun cancellationFromTheTransportIsNotConvertedIntoAnOutcome() = runBlocking {
        val journal = FakeJournal()
        val flow = PushRegistrationFlow(journal, PushRegistrationFlow.Transport {
            throw CancellationException("cancelled")
        }, nowMs = { 1_700_000_000_000 }, issuedAtSeconds = { 1_700_000_000 })
        val signer = PushRegistrationFlow.Signer { state, _, _ -> request("register", state.sequence) }
        var cancelled = false
        try {
            flow.register(journal.participants, signer, token) { "ab" }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private class FakeTransport(private val answer: PushHttpClient.RegistrationResult) : PushRegistrationFlow.Transport {
        var calls = 0
        override suspend fun post(request: PushRegistrationRequest): PushHttpClient.RegistrationResult {
            calls++
            return answer
        }
    }

    private class FakeJournal : PushRegistrationFlow.Journal {
        val events = mutableListOf<String>()
        var reserves = 0
        var reserveResult: PushRegistrationStore.Result = PushRegistrationStore.Result.Missing
        var beginRevokeResult: PushRegistrationStore.Result = PushRegistrationStore.Result.Missing
        private var revoking = false
        private var sequence = 0L

        val participants = PushRegistrationStore.Participants(
            "pk_" + "1".repeat(32), "pk_" + "2".repeat(32), ByteArray(3) { 1 }, ByteArray(3) { 2 },
        )

        /** Builds a strictly valid state through the real decoder, not a constructor. */
        private fun state(phase: String, reservation: String, sequence: Long): PushRegistrationState.State =
            PushRegistrationState.decode(
                listOf(
                    "APUPREG1", phase, "1", sequence.toString(), reservation,
                    "ab".repeat(32), "cd".repeat(32), "09".repeat(16), "56".repeat(16), "0", "-", "0",
                ).joinToString("|"),
            ) ?: error("invalid test state")

        override fun reserve(
            participants: PushRegistrationStore.Participants,
            nowMs: Long,
        ): PushRegistrationStore.Result {
            events += "reserve"
            reserves++
            if (reserveResult != PushRegistrationStore.Result.Missing) return reserveResult
            sequence++
            return if (revoking) {
                PushRegistrationStore.Result.Ready(state("r", "r", sequence))
            } else {
                PushRegistrationStore.Result.Ready(state("a", "a", sequence))
            }
        }

        override fun registered(
            participants: PushRegistrationStore.Participants,
            sequence: Long,
            tokenFingerprint: String,
            nowMs: Long,
            route: String,
        ): PushRegistrationStore.Result {
            events += "registered:$sequence"
            return PushRegistrationStore.Result.Ready(state("a", "-", sequence))
        }

        override fun beginRevocation(participants: PushRegistrationStore.Participants): PushRegistrationStore.Result {
            events += "beginRevocation"
            if (beginRevokeResult != PushRegistrationStore.Result.Missing) return beginRevokeResult
            revoking = true
            return PushRegistrationStore.Result.Ready(state("r", "-", 0))
        }

        override fun revoked(
            participants: PushRegistrationStore.Participants,
            sequence: Long,
        ): PushRegistrationStore.Result {
            events += "revoked:$sequence"
            return PushRegistrationStore.Result.Ready(state("x", "-", sequence))
        }
    }
}
