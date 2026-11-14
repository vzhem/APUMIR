package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRuntimeConfigTest {
    private val host = "wake.example.workers.dev"

    @Test fun noBuildMayBuildATransportWithoutAVerifiedFirebaseClient() {
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = false, testedBuild = true, setOf(host), "https://$host"))
        assertFalse(PushRuntimeConfig.gateOpen(firebaseConfigured = false, testedBuild = true))
    }

    @Test fun aReleasedBuildStaysClosedEvenWithFirebaseConfigured() {
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = true, testedBuild = false, setOf(host), "https://$host"))
        assertFalse(PushRuntimeConfig.gateOpen(firebaseConfigured = true, testedBuild = false))
    }

    @Test fun anApprovedHostIsStillRequired() {
        // Firebase configured and a test build, but no reviewed host: no endpoint.
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = true, testedBuild = true, emptySet(), "https://$host"))
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = true, testedBuild = true, setOf(host), null))
        assertEquals(
            "https://$host/v1/mailbox/pull",
            PushRuntimeConfig.endpoint(firebaseConfigured = true, testedBuild = true, setOf(host), "https://$host")
                ?.url("/v1/mailbox/pull")?.toString(),
        )
    }

    @Test fun anUnreviewedHostOrAnHttpUrlIsRejected() {
        assertNull(PushRuntimeConfig.endpoint(true, true, setOf(host), "http://$host"))
        assertNull(PushRuntimeConfig.endpoint(true, true, setOf(host), "https://evil.workers.dev"))
        assertNull(PushRuntimeConfig.endpoint(true, true, setOf(host), "https://$host/path"))
        assertTrue(PushRuntimeConfig.gateOpen(firebaseConfigured = true, testedBuild = true))
    }

    @Test fun theReviewedHostIsTheOnlyDefaultAndStillNeedsBothBuildFacts() {
        // The default endpoint is the one reviewed host, but it is reached only
        // when both build facts are true: a Firebase-configured released build
        // and a build without the client configuration still get nothing.
        val endpoint = PushRuntimeConfig.endpoint(firebaseConfigured = true, testedBuild = true)
        assertEquals("apu-push.apuchat.workers.dev", PushRuntimeConfig.PRODUCTION_HOST)
        assertEquals(
            "https://" + PushRuntimeConfig.PRODUCTION_HOST + "/v1/registration",
            endpoint?.url("/v1/registration")?.toString(),
        )
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = false, testedBuild = true))
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = true, testedBuild = false))
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = false, testedBuild = false))
    }

    @Test fun anApprovedHostCannotBeChosenByAnythingButTheReviewedConstant() {
        // A build property may point at the approved host; it can never widen the
        // allowlist, so an unreviewed service never receives a capability.
        val reviewed = PushRuntimeConfig.PRODUCTION_HOST
        assertEquals(
            "https://$reviewed/v1/wake",
            PushRuntimeConfig.endpoint(true, true, setOf(reviewed), "https://$reviewed")?.url("/v1/wake")?.toString(),
        )
        assertNull(PushRuntimeConfig.endpoint(true, true, setOf(reviewed), "https://apu-push-evil.workers.dev"))
        assertNull(PushRuntimeConfig.endpoint(true, true, setOf(reviewed), "https://p2p-relay.apuchat.workers.dev"))
        // The relay that already carries MQTT is deliberately not this service.
        assertFalse(reviewed.contains("p2p-relay"))
    }
}
