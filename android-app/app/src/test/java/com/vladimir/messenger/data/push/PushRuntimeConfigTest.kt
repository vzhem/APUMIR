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

    @Test fun shippedDefaultsHaveNoApprovedEndpoint() {
        // The default arguments of the real object must not approve anything:
        // a released build cannot reach a production Worker by accident.
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = true, testedBuild = true))
        assertNull(PushRuntimeConfig.endpoint(firebaseConfigured = false, testedBuild = false))
    }
}
