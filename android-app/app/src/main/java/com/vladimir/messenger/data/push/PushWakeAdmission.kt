package com.vladimir.messenger.data.push

/**
 * Parser and consistency checks for a future FCM wake receiver. They perform no
 * I/O and cannot establish trust: the caller must obtain the current local
 * identity and an independently pinned peer binding, read the live registration
 * journal, and rederive the route using the matching local secret. In particular,
 * a route-index hit or a valid-looking FCM payload alone is NOT authorization.
 *
 * These checks are not yet connected to Firebase or a scheduler. Identity,
 * pin and registration changes must be serialized with every eventual effect;
 * re-checking these values after a wait alone does not close an ABA race.
 */
internal class PushWakePayload private constructor(internal val route: String) {
    override fun toString(): String = "PushWakePayload(redacted)"

    companion object {
        private val ROUTE = Regex("^[0-9a-f]{64}$")

        /** Only data-only protocol v1, not arbitrary notification payloads. */
        fun parse(data: Map<String, String>): PushWakePayload? {
            if (data.size != 2 || data["v"] != "1") return null
            val route = data["route"] ?: return null
            if (!ROUTE.matches(route)) return null
            return PushWakePayload(route)
        }
    }
}

internal object PushWakeAdmission {
    private val NODE = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")

    /**
     * A necessary check immediately before a wake effect, NOT a lock. [route]
     * must be freshly derived with verified bindings and the current local
     * secret; [pinnedPeerBinding] must be read independently of the FCM data.
     * The journal and the indexed hint must refer to the same live profile.
     */
    fun isConsistent(
        payload: PushWakePayload,
        indexedLocalId: String,
        indexedPeerId: String,
        indexedGeneration: Long,
        currentLocalId: String?,
        currentPeerId: String?,
        localBinding: ByteArray?,
        pinnedPeerBinding: ByteArray?,
        registration: PushRegistrationState.State?,
        route: String?,
    ): Boolean {
        val local = localBinding ?: return false
        val pin = pinnedPeerBinding ?: return false
        val state = registration ?: return false
        if (!NODE.matches(indexedLocalId) || !NODE.matches(indexedPeerId) || indexedLocalId == indexedPeerId ||
            indexedLocalId != currentLocalId || indexedPeerId != currentPeerId ||
            local.size !in 1..512 || pin.size !in 1..512 || local.contentEquals(pin) ||
            state.phase != PushRegistrationState.Phase.ACTIVE || state.registeredAtMs <= 0 ||
            indexedGeneration != state.generation ||
            state.ownerHash != PushEpochProtocol.bindingHash(local) ||
            state.peerHash != PushEpochProtocol.bindingHash(pin)
        ) return false
        return route != null && payload.route == route
    }
}
