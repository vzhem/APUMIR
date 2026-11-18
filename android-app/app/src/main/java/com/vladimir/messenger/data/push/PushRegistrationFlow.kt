package com.vladimir.messenger.data.push

/**
 * Sequences the *offline* registration steps that already exist separately:
 * reserve a durable sequence, sign exactly that reservation, POST it, and only
 * then index the derived route. It adds no wire format and no trust of its own.
 *
 * Guarantees and deliberate limits:
 *  - a sequence is reserved durably before any network call, so a crash can
 *    only skip numbers, never reuse one;
 *  - a server answer is accepted only for the exact reservation that was sent
 *    ([Journal.registered] re-checks the sequence);
 *  - a `204` is stored as "the server accepted this operation". It is NOT proof
 *    that FCM will deliver anything, that the token is valid at Google, or that
 *    a message exists;
 *  - revoke keeps the local route index cleared even when the request fails, so
 *    a lost server answer cannot keep this device admitted;
 *  - the caller must still serialize identity/pin/restore changes against this
 *    flow; re-reading before and after a wait is not an ABA-proof lock.
 */
internal class PushRegistrationFlow(
    private val journal: Journal,
    private val transport: Transport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val issuedAtSeconds: (Long) -> Long = { it / 1000 },
) {
    /** The exact journal operations this flow is allowed to perform. */
    interface Journal {
        fun reserve(
            participants: PushRegistrationStore.Participants,
            nowMs: Long,
        ): PushRegistrationStore.Result
        fun registered(
            participants: PushRegistrationStore.Participants,
            sequence: Long,
            tokenFingerprint: String,
            nowMs: Long,
            route: String,
        ): PushRegistrationStore.Result
        fun beginRevocation(participants: PushRegistrationStore.Participants): PushRegistrationStore.Result
        fun revoked(participants: PushRegistrationStore.Participants, sequence: Long): PushRegistrationStore.Result
    }

    /** Signed transport; the request already carries the capability, never a log copy. */
    fun interface Transport {
        suspend fun post(request: PushRegistrationRequest): PushHttpClient.RegistrationResult
    }

    /** Signs exactly one reserved state. The capability stays inside the material. */
    fun interface Signer {
        fun request(
            state: PushRegistrationState.State,
            issuedAtSeconds: Long,
            token: String,
        ): PushRegistrationRequest?
    }

    sealed interface Result {
        /** Server accepted the register; the local route hint is now indexed. */
        object Registered : Result
        /** Server accepted the revoke. */
        object Revoked : Result
        /** Nothing was sent: state, token, trust or reservation is unusable. */
        object Rejected : Result
        /** Server called the operation stale or unauthorised. */
        object Conflict : Result
        object Throttled : Result
        /** Network or storage failure; a later attempt must re-reserve. */
        object Unavailable : Result
    }

    /**
     * @param token the provider token; only its fingerprint reaches the journal,
     *        and it is never logged.
     */
    suspend fun register(
        participants: PushRegistrationStore.Participants,
        signer: Signer,
        token: String,
        deriveRoute: () -> String?,
    ): Result {
        val fingerprint = PushWakeCrypto.tokenFingerprint(token) ?: return Result.Rejected
        val now = nowMs()
        if (now !in 1..PushRegistrationState.MAX_NUMBER) return Result.Rejected
        val reserved = when (val result = journal.reserve(participants, now)) {
            is PushRegistrationStore.Result.Ready -> result.state
            // A full journal, a changed pin/identity or a corrupted record must
            // never be overwritten by a new registration.
            else -> return Result.Rejected
        }
        if (reserved.phase != PushRegistrationState.Phase.ACTIVE ||
            reserved.reservation != PushRegistrationState.Reservation.REGISTER
        ) return Result.Rejected
        val request = signer.request(reserved, issuedAtSeconds(nowMs()), token) ?: return Result.Rejected
        return when (transport.post(request)) {
            PushHttpClient.RegistrationResult.STORED -> {
                val route = deriveRoute() ?: return Result.Unavailable
                when (journal.registered(participants, reserved.sequence, fingerprint, nowMs(), route)) {
                    is PushRegistrationStore.Result.Ready -> Result.Registered
                    // The server accepted it, but this device could not index the
                    // hint. Do not pretend the local state is complete.
                    PushRegistrationStore.Result.Unavailable -> Result.Unavailable
                    else -> Result.Rejected
                }
            }
            PushHttpClient.RegistrationResult.STALE -> Result.Conflict
            PushHttpClient.RegistrationResult.THROTTLED -> Result.Throttled
            PushHttpClient.RegistrationResult.REJECTED -> Result.Rejected
            PushHttpClient.RegistrationResult.UNAVAILABLE -> Result.Unavailable
        }
    }

    /**
     * Starts revocation locally first, so route admission stops even if the
     * network is down, then tells the server. A failed request leaves the journal
     * in REVOKING with a used sequence; the next attempt reserves a fresh number,
     * which is safe.
     */
    suspend fun revoke(
        participants: PushRegistrationStore.Participants,
        signer: Signer,
    ): Result {
        val revoking = when (val result = journal.beginRevocation(participants)) {
            is PushRegistrationStore.Result.Ready -> result.state
            else -> return Result.Rejected
        }
        if (revoking.phase != PushRegistrationState.Phase.REVOKING) return Result.Rejected
        val now = nowMs()
        if (now !in 1..PushRegistrationState.MAX_NUMBER) return Result.Unavailable
        val reserved = when (val result = journal.reserve(participants, now)) {
            is PushRegistrationStore.Result.Ready -> result.state
            else -> return Result.Unavailable
        }
        if (reserved.reservation != PushRegistrationState.Reservation.REVOKE) return Result.Rejected
        val request = signer.request(reserved, issuedAtSeconds(nowMs()), token = "") ?: return Result.Rejected
        return when (transport.post(request)) {
            PushHttpClient.RegistrationResult.STORED -> when (journal.revoked(participants, reserved.sequence)) {
                is PushRegistrationStore.Result.Ready -> Result.Revoked
                else -> Result.Unavailable
            }
            PushHttpClient.RegistrationResult.STALE -> Result.Conflict
            PushHttpClient.RegistrationResult.THROTTLED -> Result.Throttled
            PushHttpClient.RegistrationResult.REJECTED -> Result.Rejected
            PushHttpClient.RegistrationResult.UNAVAILABLE -> Result.Unavailable
        }
    }

    companion object {
        /** Production wiring: the encrypted journal, the signed HTTP client and the crypto seam. */
        fun of(store: PushRegistrationStore, http: PushHttpClient): PushRegistrationFlow = PushRegistrationFlow(
            journal = object : Journal {
                override fun reserve(participants: PushRegistrationStore.Participants, nowMs: Long) =
                    store.reserve(participants, nowMs)
                override fun registered(
                    participants: PushRegistrationStore.Participants, sequence: Long,
                    tokenFingerprint: String, nowMs: Long, route: String,
                ) = store.registered(participants, sequence, tokenFingerprint, nowMs, route)
                override fun beginRevocation(participants: PushRegistrationStore.Participants) =
                    store.beginRevocation(participants)
                override fun revoked(participants: PushRegistrationStore.Participants, sequence: Long) =
                    store.revoked(participants, sequence)
            },
            transport = { request -> http.register(request) },
        )

        /** Adapter from the derived material to [Signer]; the capability stays owned by it. */
        fun signer(
            material: PushWakeCrypto.Material,
            localBinding: ByteArray,
        ): Signer = Signer { state, issuedAt, token -> material.registrationRequest(localBinding, state, issuedAt, token) }
    }
}
