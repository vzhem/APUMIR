package com.vladimir.messenger.data.push

/**
 * Reviewed, immutable endpoint and feature-gate description for the remote-push
 * runtime. Nothing here performs I/O, reads Firebase or schedules work; it only
 * decides whether the *offline* pieces may be used at all.
 *
 * Deliberately, [PushRuntimeConfig.enabled] can never be turned on by a value
 * that arrives at runtime (an FCM payload, a peer packet, a deep link or a
 * remote setting). It is compiled from two build-time facts:
 *
 *  - a build property that must be set explicitly for a testing build, and
 *  - inclusion of a verified Firebase client configuration.
 *
 * The endpoint is a third, independent build-time fact: it must be one of the
 * hosts in [APPROVED_HOSTS], which is a reviewed constant in this file. A build
 * property can only *select* among approved hosts; it can never introduce one.
 *
 * Even when all three are true, the runtime still requires a registered device,
 * an authenticated mailbox item and a completed end-to-end check on two physical
 * phones before push may be announced. A false gate is not a delivery failure;
 * it is the intended state of every released build today.
 */
internal object PushRuntimeConfig {
    /**
     * Endpoint selection. The host must be in [approvedHosts]; anything else is
     * null, so a transport cannot be constructed against an unreviewed service.
     */
    fun endpoint(
        firebaseConfigured: Boolean,
        testedBuild: Boolean,
        approvedHosts: Set<String> = APPROVED_HOSTS,
        url: String? = APPROVED_URL,
    ): PushEndpoint? {
        if (!firebaseConfigured || !testedBuild) return null
        val raw = url ?: return null
        if (approvedHosts.isEmpty()) return null
        return PushEndpoint.approved(raw, approvedHosts)
    }

    /**
     * True only for a build that is both Firebase-configured and explicitly
     * marked as a push test build. Released builds keep this false.
     */
    fun gateOpen(firebaseConfigured: Boolean, testedBuild: Boolean): Boolean =
        firebaseConfigured && testedBuild

    /**
     * The one reviewed push service host.
     *
     * It is the dedicated, storage-backed Worker for this feature — deliberately
     * not the MQTT relay (`p2p-relay`) and not the disabled prototype Worker.
     * Changing this string is a reviewed change: it decides where a device will
     * send a capability and where it will look for its own ciphertext.
     */
    const val PRODUCTION_HOST: String = "apu-push.apuchat.workers.dev"

    /** Reviewed hosts. A build property may select among these, never add one. */
    private val APPROVED_HOSTS: Set<String> = setOf(PRODUCTION_HOST)

    /** Default endpoint used when a build does not name an approved one. */
    private val APPROVED_URL: String = "https://" + PRODUCTION_HOST
}
