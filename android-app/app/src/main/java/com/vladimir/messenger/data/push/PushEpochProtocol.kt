package com.vladimir.messenger.data.push

import java.security.MessageDigest

/**
 * Experimental control format only; it is NOT wired to the message router or
 * FCM. The receiver of a wake chooses an epoch and announces it to the sender
 * inside an encrypted, successfully opened envelope. The old APUTYP1 wrapper
 * is already filtered as service traffic by existing APU clients.
 *
 * This codec does not authenticate a packet. Call [accept] only with the
 * binding extracted from the same successfully opened and verified envelope,
 * and with the contact pin that existed BEFORE processing that envelope. Never
 * create a TOFU pin from the same message and then treat it as prior trust.
 * Persistence, safe initial key verification, multi-device coordination and
 * lifecycle serialization remain mandatory before this can be enabled.
 */
internal object PushEpochProtocol {
    const val PREFIX = "APUTYP1|APUPUSH1|"
    private const val MAX_WIRE_CHARS = 256
    private const val MAX_BINDING_BYTES = 512
    private const val MAX_GENERATION = 9_007_199_254_740_991L // safe in JSON/JavaScript too
    private val hashPattern = Regex("^[0-9a-f]{64}$")
    private val epochPattern = Regex("^[0-9a-f]{32}$")
    private val sequencePattern = Regex("^[1-9][0-9]{0,15}$")
    private val zeroEpoch = "0".repeat(32)

    enum class Action { OFFER, REVOKE }

    /** The epoch is sensitive protocol state; deliberately no data-class toString/copy. */
    class Announcement private constructor(
        val action: Action,
        val generation: Long,
        val ownerBindingHash: String,
        val targetBindingHash: String,
        val epochHex: String?,
    ) {
        override fun toString(): String = "PushEpochAnnouncement(redacted)"

        companion object {
            internal fun parsed(
                action: Action,
                generation: Long,
                ownerBindingHash: String,
                targetBindingHash: String,
                epochHex: String?,
            ) = Announcement(action, generation, ownerBindingHash, targetBindingHash, epochHex)
        }
    }

    /** Only states produced by [accept] should be persisted; never import from backup. */
    class State private constructor(
        val action: Action,
        val generation: Long,
        val ownerBindingHash: String,
        val targetBindingHash: String,
        val epochHex: String?,
    ) {
        override fun toString(): String = "PushEpochState(redacted)"

        companion object {
            internal fun from(announcement: Announcement) = State(
                announcement.action, announcement.generation, announcement.ownerBindingHash,
                announcement.targetBindingHash, announcement.epochHex,
            )
        }
    }

    sealed interface Verdict {
        class Applied(val state: State) : Verdict {
            override fun toString(): String = "PushEpochApplied(redacted)"
        }
        object Duplicate : Verdict
        object Rejected : Verdict
    }

    /** Reserved prefixes must be consumed even when the rest of the packet is invalid. */
    fun isPacket(text: String?): Boolean = text?.startsWith(PREFIX) == true

    fun encodeOffer(generation: Long, ownerHash: String, targetHash: String, epochHex: String): String? =
        encode("offer", generation, ownerHash, targetHash, epochHex)

    fun encodeRevoke(generation: Long, ownerHash: String, targetHash: String): String? =
        encode("revoke", generation, ownerHash, targetHash, "-")

    private fun encode(action: String, generation: Long, ownerHash: String, targetHash: String, epoch: String): String? {
        val text = "$PREFIX$action|$generation|$ownerHash|$targetHash|$epoch"
        return text.takeIf { parse(it) != null }
    }

    fun parse(text: String?): Announcement? {
        if (!isPacket(text) || text == null || text.length > MAX_WIRE_CHARS) return null
        val fields = text.split('|')
        if (fields.size != 7 || fields[0] != "APUTYP1" || fields[1] != "APUPUSH1") return null
        val action = when (fields[2]) {
            "offer" -> Action.OFFER
            "revoke" -> Action.REVOKE
            else -> return null
        }
        if (!sequencePattern.matches(fields[3])) return null
        val generation = fields[3].toLongOrNull()?.takeIf { it <= MAX_GENERATION } ?: return null
        val ownerHash = fields[4]
        val targetHash = fields[5]
        val epoch = when (action) {
            Action.OFFER -> fields[6]
            Action.REVOKE -> if (fields[6] == "-") null else return null
        }
        if (!validFields(action, generation, ownerHash, targetHash, epoch)) return null
        return Announcement.parsed(action, generation, ownerHash, targetHash, epoch)
    }

    private fun validFields(action: Action, generation: Long, ownerHash: String, targetHash: String, epoch: String?): Boolean {
        if (generation !in 1..MAX_GENERATION || !hashPattern.matches(ownerHash) ||
            !hashPattern.matches(targetHash) || ownerHash == targetHash
        ) return false
        return when (action) {
            Action.OFFER -> epoch != null && epochPattern.matches(epoch) && epoch != zeroEpoch
            Action.REVOKE -> epoch == null
        }
    }

    /**
     * Reject unauthenticated, newly-self-pinned, wrong-recipient and stale
     * announcements. [priorPin] must come from the established peer store,
     * not from the incoming packet; [openedSenderBinding] is valid only after
     * successful cryptographic envelope verification/decryption. [previous]
     * belongs to the same local profile and peer and must be stored atomically.
     * If a trusted pin/profile changes, explicit reset/re-pairing is required.
     * The return value never initiates network or alters application state.
     */
    fun accept(
        packet: Announcement,
        sealedAndOpened: Boolean,
        openedSenderBinding: ByteArray?,
        priorPin: ByteArray?,
        localBinding: ByteArray?,
        previous: State?,
    ): Verdict {
        val sender = openedSenderBinding ?: return Verdict.Rejected
        val pin = priorPin ?: return Verdict.Rejected
        val local = localBinding ?: return Verdict.Rejected
        if (!sealedAndOpened || !validBinding(sender) || !validBinding(pin) || !validBinding(local) ||
            !MessageDigest.isEqual(sender, pin) ||
            !validFields(packet.action, packet.generation, packet.ownerBindingHash, packet.targetBindingHash, packet.epochHex)
        ) return Verdict.Rejected
        val ownerHash = bindingHash(pin)
        val targetHash = bindingHash(local)
        if (ownerHash != packet.ownerBindingHash || targetHash != packet.targetBindingHash) return Verdict.Rejected
        if (previous != null) {
            if (!validFields(previous.action, previous.generation, previous.ownerBindingHash, previous.targetBindingHash, previous.epochHex) ||
                previous.ownerBindingHash != ownerHash || previous.targetBindingHash != targetHash
            ) return Verdict.Rejected
            if (packet.generation < previous.generation) return Verdict.Rejected
            if (packet.generation == previous.generation) {
                return if (packet.action == previous.action && packet.epochHex == previous.epochHex) {
                    Verdict.Duplicate
                } else Verdict.Rejected
            }
        }
        return Verdict.Applied(State.from(packet))
    }

    private fun validBinding(binding: ByteArray?): Boolean = binding != null && binding.size in 1..MAX_BINDING_BYTES

    internal fun bindingHash(binding: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(binding).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
