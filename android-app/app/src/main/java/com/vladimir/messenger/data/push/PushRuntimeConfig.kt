package com.vladimir.messenger.data.push

/**
 * Reviewed, immutable endpoint and feature-gate description for a future
 * remote-push runtime. Nothing here performs I/O, reads Firebase or schedules
 * work; it only decides whether the *offline* pieces may be used at all.
 *
 * Deliberately, [PushRuntimeConfig.enabled] can never be turned on by a value
 * that arrives at runtime (an FCM payload, a peer packet, a deep link or a
 * remote setting). It is compiled from two build-time facts:
 *
 *  - a build property that must be set explicitly for a testing build, and
 *  - inclusion of a verified Firebase client configuration.
 *
 * Even when both are true, the runtime still requires a registered device, an
 * authenticated mailbox item and a completed end-to-end test on two physical
 * phones before push may be announced. A false gate is not a delivery failure;
 * it is the intended state of every released build today.
 */
internal object PushRuntimeConfig {
    /**
     * Endpoint selection. There is no approved production host: the isolated
     * Worker exists only as a disabled prototype. When the gate is off this is
     * null, so no transport can be constructed by accident.
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

    /** No host is approved until one is reviewed; keep the list empty. */
    private val APPROVED_HOSTS: Set<String> = emptySet()

    /** No endpoint is approved until one is reviewed; keep it null. */
    private val APPROVED_URL: String? = null
}
