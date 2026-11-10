package com.vladimir.messenger.data.push

/**
 * Pure, bounded state for one receiver -> sender wake route. The epoch and
 * device id are sensitive and must only be persisted in an encrypted journal.
 * This class does not perform networking and is not yet wired to Firebase.
 *
 * A sequence is reserved durably BEFORE each network attempt; skipping values
 * is safe after a crash. A late response to an older attempt cannot mark a
 * newer attempt successful. Epoch announcements are allowed only after the
 * matching registration/revocation was accepted by the server.
 */
internal object PushRegistrationState {
    const val MAX_NUMBER = 9_007_199_254_740_991L
    private val HASH = Regex("^[0-9a-f]{64}$")
    private val ID = Regex("^[0-9a-f]{32}$")
    private val NUMBER = Regex("^(0|[1-9][0-9]{0,15})$")
    private const val MAX_ENCODED_CHARS = 384

    enum class Phase(val wire: String) { ACTIVE("a"), REVOKING("r"), REVOKED("x") }
    enum class Reservation(val wire: String) { NONE("-"), REGISTER("a"), REVOKE("r") }

    class State private constructor(
        val phase: Phase,
        val generation: Long,
        val sequence: Long,
        val reservation: Reservation,
        val ownerHash: String,
        val peerHash: String,
        val epochHex: String,
        val deviceHex: String,
        val registeredAtMs: Long,
        val tokenFingerprint: String?,
        val announcedGeneration: Long,
    ) {
        override fun toString(): String = "PushRegistrationState(redacted)"

        private fun changed(
            phase: Phase = this.phase,
            generation: Long = this.generation,
            sequence: Long = this.sequence,
            reservation: Reservation = this.reservation,
            epochHex: String = this.epochHex,
            registeredAtMs: Long = this.registeredAtMs,
            tokenFingerprint: String? = this.tokenFingerprint,
            announcedGeneration: Long = this.announcedGeneration,
        ): State = State(
            phase, generation, sequence, reservation, ownerHash, peerHash, epochHex, deviceHex,
            registeredAtMs, tokenFingerprint, announcedGeneration,
        )

        /** Persist the returned state before signing or sending the operation. */
        fun reserve(): State? = if (phase != Phase.REVOKED && sequence < MAX_NUMBER) {
            changed(sequence = sequence + 1, reservation = if (phase == Phase.ACTIVE) Reservation.REGISTER else Reservation.REVOKE)
        } else null

        fun registered(attemptSequence: Long, tokenHash: String, nowMs: Long): State? =
            if (phase == Phase.ACTIVE && reservation == Reservation.REGISTER && attemptSequence == sequence && sequence > 0 &&
                HASH.matches(tokenHash) && tokenHash.any { it != '0' } && nowMs in 1..MAX_NUMBER
            ) changed(reservation = Reservation.NONE, registeredAtMs = nowMs, tokenFingerprint = tokenHash) else null

        /** Start revocation without deleting the old capability or sequence. */
        fun beginRevocation(): State? = if (phase == Phase.ACTIVE && generation < MAX_NUMBER) {
            changed(
                phase = Phase.REVOKING, generation = generation + 1, reservation = Reservation.NONE,
                registeredAtMs = 0, tokenFingerprint = null, announcedGeneration = 0,
            )
        } else null

        /** Only a matching accepted server operation enables the revoke announcement. */
        fun revoked(attemptSequence: Long): State? =
            if (phase == Phase.REVOKING && reservation == Reservation.REVOKE && attemptSequence == sequence && sequence > 0) {
                changed(phase = Phase.REVOKED, reservation = Reservation.NONE)
            } else null

        fun announcement(): String? = when (phase) {
            Phase.ACTIVE -> if (registeredAtMs > 0) {
                PushEpochProtocol.encodeOffer(generation, ownerHash, peerHash, epochHex)
            } else null
            Phase.REVOKING -> null
            Phase.REVOKED -> PushEpochProtocol.encodeRevoke(generation, ownerHash, peerHash)
        }

        /** Marks a local send attempt, not receipt by the peer. */
        fun announced(attemptGeneration: Long): State? =
            if (attemptGeneration == generation && announcement() != null) changed(announcedGeneration = generation)
            else null

        /** Old route must be server-revoked and its revoke offered first. */
        fun rotate(newEpochHex: String): State? =
            if (phase == Phase.REVOKED && announcedGeneration == generation && generation < MAX_NUMBER &&
                validId(newEpochHex) && newEpochHex != epochHex
            ) changed(
                phase = Phase.ACTIVE, generation = generation + 1, sequence = 0, reservation = Reservation.NONE,
                epochHex = newEpochHex, registeredAtMs = 0, tokenFingerprint = null, announcedGeneration = 0,
            ) else null

        fun encode(): String = listOf(
            "APUPREG1", phase.wire, generation, sequence, reservation.wire, ownerHash, peerHash, epochHex,
            deviceHex, registeredAtMs, tokenFingerprint ?: "-", announcedGeneration,
        ).joinToString("|")

        companion object {
            internal fun create(
                phase: Phase, generation: Long, sequence: Long, reservation: Reservation, ownerHash: String, peerHash: String,
                epochHex: String, deviceHex: String, registeredAtMs: Long, tokenFingerprint: String?,
                announcedGeneration: Long,
            ): State? {
                if (generation !in 1..MAX_NUMBER || sequence !in 0..MAX_NUMBER ||
                    !HASH.matches(ownerHash) || !HASH.matches(peerHash) || ownerHash == peerHash ||
                    !validId(epochHex) || !validId(deviceHex) || registeredAtMs !in 0..MAX_NUMBER ||
                    announcedGeneration !in 0..MAX_NUMBER || announcedGeneration > generation
                ) return null
                if (announcedGeneration != 0L && announcedGeneration != generation) return null
                if (reservation == Reservation.REGISTER && phase != Phase.ACTIVE ||
                    reservation == Reservation.REVOKE && phase != Phase.REVOKING ||
                    reservation != Reservation.NONE && sequence == 0L
                ) return null
                if ((registeredAtMs == 0L) != (tokenFingerprint == null)) return null
                if (tokenFingerprint != null && (!HASH.matches(tokenFingerprint) || tokenFingerprint.all { it == '0' })) return null
                if (phase != Phase.ACTIVE && (registeredAtMs != 0L || tokenFingerprint != null)) return null
                if (phase == Phase.REVOKING && announcedGeneration != 0L) return null
                if (phase == Phase.ACTIVE && registeredAtMs == 0L && announcedGeneration != 0L) return null
                if (registeredAtMs > 0 && sequence == 0L) return null
                if (phase == Phase.REVOKED && sequence == 0L) return null
                return State(phase, generation, sequence, reservation, ownerHash, peerHash, epochHex, deviceHex,
                    registeredAtMs, tokenFingerprint, announcedGeneration)
            }
        }
    }

    fun fresh(ownerHash: String, peerHash: String, generation: Long, epochHex: String, deviceHex: String): State? =
        State.create(Phase.ACTIVE, generation, 0, Reservation.NONE, ownerHash, peerHash, epochHex, deviceHex, 0, null, 0)

    /** Strictly parse decrypted state; never log [wire] even on failure. */
    fun decode(wire: String): State? {
        if (wire.length > MAX_ENCODED_CHARS) return null
        val fields = wire.split('|')
        if (fields.size != 12 || fields[0] != "APUPREG1") return null
        val phase = Phase.entries.firstOrNull { it.wire == fields[1] } ?: return null
        val generation = number(fields[2]) ?: return null
        val sequence = number(fields[3]) ?: return null
        val reservation = Reservation.entries.firstOrNull { it.wire == fields[4] } ?: return null
        val registeredAt = number(fields[9]) ?: return null
        val announced = number(fields[11]) ?: return null
        val tokenHash = fields[10].takeUnless { it == "-" }
        return State.create(phase, generation, sequence, reservation, fields[5], fields[6], fields[7], fields[8],
            registeredAt, tokenHash, announced)
    }

    private fun validId(value: String): Boolean = ID.matches(value) && value.any { it != '0' }
    private fun number(value: String): Long? =
        value.takeIf { NUMBER.matches(it) }?.toLongOrNull()?.takeIf { it <= MAX_NUMBER }
}
