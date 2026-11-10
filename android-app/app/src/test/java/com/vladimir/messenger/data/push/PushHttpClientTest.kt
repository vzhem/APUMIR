package com.vladimir.messenger.data.push

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushHttpClientTest {
    private val owner = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
    private val cap = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a"
    private val device = "56".repeat(16)
    private val token = "fcm-test-token-abcdefghijklmnop"
    private val signature = "51d08645a428e7615aaf8cec4d606f9c2a0b65f98a3703844cd0a10156ba10c67971f74b6ee86a90374c37343bd714b82f18560c0b8c389e8297ae1ef178af03"
    private fun endpoint() = PushEndpoint.approved("https://wake.example.workers.dev", setOf("wake.example.workers.dev"))!!
    private fun registration(action: String = "register", owner: String = this.owner, cap: String = this.cap,
        device: String = this.device, sequence: Long = 17, time: Long = 1_700_000_000,
        token: String = this.token, signature: String = this.signature) =
        PushRegistrationRequest(action, owner, cap, device, sequence, time, token, signature)

    @Test fun configRequiresAnExactAllowedHttpsHostAndForbidsPathsAndRedirects() {
        val allowed = setOf("wake.example.workers.dev")
        assertEquals("https://wake.example.workers.dev/v1/wake", endpoint().url("/v1/wake").toString())
        assertTrue(PushEndpoint.approved("https://wake.example.workers.dev:443/", allowed) != null)
        val bad = listOf(
            "http://wake.example.workers.dev", "https://evil.example.workers.dev",
            "https://wake.example.workers.dev.evil.com", "https://wake.example.workers.dev@evil.com",
            "https://user@wake.example.workers.dev", "https://wake.example.workers.dev:444",
            "https://wake.example.workers.dev/path", "https://wake.example.workers.dev/?q=secret",
            "https://wake.example.workers.dev/#fragment", "https://wake.example.workers.dev/%2f",
            "https://127.0.0.1", "https://localhost", "https://[::1]", "https://a..b", "https://[invalid",
            " HTTPS://wake.example.workers.dev", "https://WAKE.example.workers.dev", "",
        )
        for (url in bad) assertEquals(null, PushEndpoint.approved(url, allowed))
        assertEquals(null, PushEndpoint.approved("https://127.0.0.1", setOf("127.0.0.1")))
        assertEquals(null, PushEndpoint.approved("https://localhost", setOf("localhost")))
        val client = PushHttpClient(endpoint(), PushPost { _, _, _ -> 302 }, {})
        assertEquals(PushHttpClient.WakeResult.UNAVAILABLE, client.wake(PushWakeRequest(owner, cap)))
    }

    @Test fun registrationMatchesWorkerVectorAndRequestBytesAreCleared() {
        var path = ""
        var json = ""
        var bodyReference = byteArrayOf()
        val client = PushHttpClient(endpoint(), PushPost { _, p, body ->
            path = p; json = body.toString(Charsets.US_ASCII); bodyReference = body; 204
        }, { error("unexpected error") })
        val request = registration()
        assertEquals(PushHttpClient.RegistrationResult.STORED, client.register(request))
        assertEquals("/v1/registration", path)
        assertEquals("{\"action\":\"register\",\"owner\":\"$owner\",\"capability\":\"$cap\",\"device\":\"$device\",\"sequence\":17,\"issuedAt\":1700000000,\"token\":\"$token\",\"signature\":\"$signature\"}", json)
        assertTrue(bodyReference.isNotEmpty() && bodyReference.all { it == 0.toByte() })
        assertFalse(request.toString().contains(cap))
        assertFalse(request.toString().contains(token))
    }

    @Test fun wakeHasOnlyOpaqueRouteFieldsAndAcceptedIsNotADeliveryReceipt() {
        var json = ""
        val client = PushHttpClient(endpoint(), PushPost { _, path, body ->
            assertEquals("/v1/wake", path); json = body.toString(Charsets.US_ASCII); 202
        }, { error("unexpected error") })
        val request = PushWakeRequest(owner, cap)
        assertEquals(PushHttpClient.WakeResult.ACCEPTED, client.wake(request))
        assertEquals("{\"owner\":\"$owner\",\"capability\":\"$cap\"}", json)
        assertFalse(request.toString().contains(cap))
    }

    @Test fun invalidFieldsNeverReachTheNetwork() {
        var calls = 0
        val client = PushHttpClient(endpoint(), PushPost { _, _, _ -> calls++; 204 }, { error("unexpected error") })
        val invalid = listOf(
            registration(action = "send"), registration(owner = owner.uppercase()), registration(owner = "0".repeat(64)),
            registration(cap = "0".repeat(64)), registration(device = "0".repeat(32)),
            registration(sequence = 0), registration(sequence = 9_007_199_254_740_992), registration(time = 0),
            registration(token = "short"), registration(token = "token-with-newline-abcdefgh\n"),
            registration(token = "\"-token-with-quote-abcdefghijkl"), registration(token = "токен".repeat(10)),
            registration(signature = "bad"), registration(action = "revoke", token = token),
        )
        invalid.forEach { assertEquals(PushHttpClient.RegistrationResult.REJECTED, client.register(it)) }
        assertEquals(PushHttpClient.WakeResult.REJECTED, client.wake(PushWakeRequest(owner, cap.uppercase())))
        assertEquals(0, calls)
        assertEquals(PushHttpClient.RegistrationResult.STORED, client.register(registration(action = "revoke", token = "")))
        assertEquals(1, calls)
    }

    @Test fun cancellationPropagatesAndStillClearsTheRequestBody() {
        var reference = byteArrayOf()
        val client = PushHttpClient(endpoint(), PushPost { _, _, body ->
            reference = body
            throw java.util.concurrent.CancellationException("cancel")
        }, { error("cancellation is not a failure") })
        val failure = runCatching { client.wake(PushWakeRequest(owner, cap)) }.exceptionOrNull()
        assertTrue(failure is java.util.concurrent.CancellationException)
        assertTrue(reference.isNotEmpty() && reference.all { it == 0.toByte() })
    }

    @Test fun statusMappingAndFailuresNeverPretendToDeliver() {
        var status = 409
        var failures = 0
        val client = PushHttpClient(endpoint(), PushPost { _, _, _ -> if (status == -1) throw IOException("sensitive content") else status }, { failures++ })
        assertEquals(PushHttpClient.RegistrationResult.STALE, client.register(registration()))
        status = 429
        assertEquals(PushHttpClient.RegistrationResult.THROTTLED, client.register(registration()))
        assertEquals(PushHttpClient.WakeResult.THROTTLED, client.wake(PushWakeRequest(owner, cap)))
        status = 403
        assertEquals(PushHttpClient.RegistrationResult.REJECTED, client.register(registration()))
        status = 200
        assertEquals(PushHttpClient.RegistrationResult.UNAVAILABLE, client.register(registration()))
        assertEquals(PushHttpClient.WakeResult.UNAVAILABLE, client.wake(PushWakeRequest(owner, cap)))
        status = -1
        assertEquals(PushHttpClient.WakeResult.UNAVAILABLE, client.wake(PushWakeRequest(owner, cap)))
        assertEquals(PushHttpClient.RegistrationResult.UNAVAILABLE, client.register(registration()))
        assertEquals(2, failures)
    }
}
