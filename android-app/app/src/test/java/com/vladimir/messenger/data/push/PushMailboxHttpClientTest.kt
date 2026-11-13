package com.vladimir.messenger.data.push

import java.io.IOException
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PushMailboxHttpClientTest {
    private val owner = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
    private val cap = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a"
    private val device = "56".repeat(16)
    private val id = "11".repeat(16)
    private val receipt = "22".repeat(16)
    private val signature = "8283a87b9ae172188e4c7dd879bb456d0474eba22acf0f8f3b83d56b5f87f22533506e7a5eb3256263b65d3581c6c1d3f2502f4416347aab80212ba486b2d00f"
    private val now = 1_700_000_000L
    private val expiry = 1_700_604_800_000L

    private fun endpoint() = PushEndpoint.approved("https://wake.example.workers.dev", setOf("wake.example.workers.dev"))!!
    private fun envelope(fill: Byte = 1, cipherBytes: Int = 32): String {
        val key = ByteArray(204) { fill }.also { it[0] = 1; it[1] = 0; it[2] = 1 }
        val b64 = Base64.getEncoder()
        return "APUSEAL1|${b64.encodeToString(key)}|${b64.encodeToString(ByteArray(12) { fill })}|" +
            b64.encodeToString(ByteArray(cipherBytes) { fill })
    }
    private fun put(value: String = envelope(), itemId: String? = null, cutoff: Long = expiry) = PushMailboxPutRequest(
        owner, cap, itemId ?: PushMailboxProtocol.putId(cutoff, value) ?: id, cutoff, value,
    )
    private fun pull(after: String = "-") = PushMailboxPullRequest(owner, cap, device, now, after, signature)
    private fun ack(items: List<PushMailboxReceipt> = listOf(PushMailboxReceipt(id, receipt))) =
        PushMailboxAckRequest(owner, cap, device, now, items, signature)
    private fun response(status: Int, json: String = "") = PushMailboxHttpResponse(status, json.toByteArray(Charsets.US_ASCII))
    private fun stored(state: String = "stored", queued: Boolean = true) =
        "{\"state\":\"$state\",\"expiresAt\":$expiry,\"wakeQueued\":$queued}"
    private fun item(itemId: String = id, itemReceipt: String = receipt, value: String = envelope()) =
        "{\"id\":\"$itemId\",\"envelope\":\"$value\",\"expiresAt\":$expiry,\"receipt\":\"$itemReceipt\"}"
    private fun page(items: String = item(), next: String? = id) =
        "{\"items\":[$items],\"next\":" + (next?.let { "\"$it\"" } ?: "null") + "}"

    @Test fun putStoresOnlyWithAValidServerReceiptAndClearsTransientBytes() = runBlocking {
        var sent = ""
        var sentRef = byteArrayOf()
        var responseRef = byteArrayOf()
        val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, path, body ->
            assertEquals("/v1/mailbox/put", path)
            sent = body.toString(Charsets.US_ASCII)
            sentRef = body
            response(201, stored()).also { responseRef = it.body }
        }, { error("unexpected failure") })
        val request = put()
        val result = client.put(request)
        assertTrue(result is PushMailboxHttpClient.PutResult.Stored)
        result as PushMailboxHttpClient.PutResult.Stored
        assertEquals(expiry, result.expiresAt)
        assertTrue(result.wakeQueued)
        val outsideId = PushMailboxProtocol.putId(expiry, envelope())!!
        assertEquals("{\"owner\":\"$owner\",\"capability\":\"$cap\",\"id\":\"$outsideId\",\"expiresAt\":$expiry,\"envelope\":\"${envelope()}\"}", sent)
        assertTrue(sentRef.isNotEmpty() && sentRef.all { it == 0.toByte() })
        assertTrue(responseRef.isNotEmpty() && responseRef.all { it == 0.toByte() })
        assertFalse(request.toString().contains(cap))
        assertFalse(request.toString().contains(envelope()))
    }

    @Test fun serverAcceptanceAndRetryStatesAreNotDelivery() = runBlocking {
        var status = 200
        var json = stored("handled", false)
        val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, _, _ -> response(status, json) }, {})
        val handled = client.put(put())
        assertTrue(handled is PushMailboxHttpClient.PutResult.Handled)
        assertEquals(expiry, (handled as PushMailboxHttpClient.PutResult.Handled).expiresAt)
        status = 201
        assertTrue(client.put(put()) is PushMailboxHttpClient.PutResult.Unavailable)
        json = stored("stored", false)
        assertTrue(client.put(put()) is PushMailboxHttpClient.PutResult.Unavailable)
        status = 200
        val existing = client.put(put()) as PushMailboxHttpClient.PutResult.Stored
        assertFalse(existing.wakeQueued)
        json = stored().replace("\"expiresAt\":$expiry", "\"expiresAt\":${expiry + 1}")
        assertTrue(client.put(put()) is PushMailboxHttpClient.PutResult.Unavailable)
        json = stored("stored", false)
        for ((code, expected) in listOf(
            404 to PushMailboxHttpClient.PutResult.RouteUnavailable,
            409 to PushMailboxHttpClient.PutResult.Conflict,
            429 to PushMailboxHttpClient.PutResult.Throttled,
            403 to PushMailboxHttpClient.PutResult.Rejected,
            202 to PushMailboxHttpClient.PutResult.Unavailable,
            302 to PushMailboxHttpClient.PutResult.Unavailable,
            503 to PushMailboxHttpClient.PutResult.Unavailable,
        )) {
            status = code
            assertEquals(expected, client.put(put()))
        }
    }

    @Test fun pullMatchesSignedWorkerVectorAndReturnsOnlyABoundedCheckedPage() = runBlocking {
        var sent = ""
        var sentRef = byteArrayOf()
        var responseRef = byteArrayOf()
        val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, path, body ->
            assertEquals("/v1/mailbox/pull", path)
            sent = body.toString(Charsets.US_ASCII)
            sentRef = body
            response(200, page()).also { responseRef = it.body }
        }, { error("unexpected failure") })
        val request = pull()
        val result = client.pull(request) as PushMailboxHttpClient.PullResult.Page
        assertEquals("{\"owner\":\"$owner\",\"capability\":\"$cap\",\"device\":\"$device\",\"issuedAt\":1700000000,\"after\":\"-\",\"signature\":\"$signature\"}", sent)
        assertEquals(id, result.next)
        assertEquals(id, result.items.single().id)
        assertEquals(receipt, result.items.single().receipt)
        assertEquals(envelope(), result.items.single().envelope)
        assertTrue(sentRef.all { it == 0.toByte() })
        assertTrue(responseRef.all { it == 0.toByte() })
        assertFalse(result.toString().contains(receipt))
        assertFalse(result.items.single().toString().contains(envelope()))
        assertFalse(request.toString().contains(cap))
    }

    @Test fun acknowledgementsAreSortedSnapshotAndOnlyMeanServerAcceptedAnOperation() = runBlocking {
        val secondId = "33".repeat(16)
        val secondReceipt = "44".repeat(16)
        val original = mutableListOf(PushMailboxReceipt(id, receipt), PushMailboxReceipt(secondId, secondReceipt))
        val request = ack(original)
        original.clear()
        var sent = ""
        var code = 204
        val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, path, body ->
            assertEquals("/v1/mailbox/ack", path)
            sent = body.toString(Charsets.US_ASCII)
            response(code)
        }, {})
        assertEquals(PushMailboxHttpClient.AckResult.ACCEPTED, client.ack(request))
        assertTrue(sent.contains("\"items\":[{\"id\":\"$id\",\"receipt\":\"$receipt\"},{\"id\":\"$secondId\",\"receipt\":\"$secondReceipt\"}]"))
        code = 200
        assertEquals(PushMailboxHttpClient.AckResult.UNAVAILABLE, client.ack(request))
        code = 429
        assertEquals(PushMailboxHttpClient.AckResult.THROTTLED, client.ack(request))
        assertFalse(request.toString().contains(receipt))
        assertFalse(PushMailboxReceipt(id, receipt).toString().contains(receipt))
    }

    @Test fun invalidRequestsNeverReachTheNetworkAndInputIsBounded() = runBlocking {
        var calls = 0
        val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, _, _ -> calls++; response(204) }, {})
        val bad = listOf(
            put("plaintext"), put(envelope().replace("APUSEAL1", "APUSEAL2")),
            put(envelope(1, 16)), put(envelope(1, 64 * 1024 + 17)), put(envelope(), "0".repeat(32)),
            put("APUSEAL1|" + "|".repeat(PushMailboxProtocol.MAX_ENVELOPE_CHARS - 9)),
            PushMailboxPutRequest(owner.uppercase(), cap, id, expiry, envelope()),
            put(envelope(), "11".repeat(16)), put(envelope(), cutoff = 0),
        )
        bad.forEach { assertEquals(PushMailboxHttpClient.PutResult.Rejected, client.put(it)) }
        assertTrue(PushMailboxProtocol.validEnvelope(envelope(1, 64 * 1024 + 16)))
        val invalidItems = listOf(
            emptyList(), listOf(PushMailboxReceipt(id, "0".repeat(32))),
            listOf(PushMailboxReceipt(id, receipt), PushMailboxReceipt(id, receipt)),
            listOf(PushMailboxReceipt("33".repeat(16), receipt), PushMailboxReceipt(id, receipt)),
            List(17) { PushMailboxReceipt((it + 1).toString(16).padStart(32, '0'), receipt) },
        )
        invalidItems.forEach { assertEquals(PushMailboxHttpClient.AckResult.REJECTED, client.ack(ack(it))) }
        assertEquals(PushMailboxHttpClient.PullResult.Rejected, client.pull(pull("0".repeat(32))))
        assertEquals(0, calls)
    }

    @Test fun putIdBindsCiphertextAndImmutableCutoffToTheIndependentWorkerVector() = runBlocking {
        val wire = envelope()
        val expected = "bbdfc6257694ab5da289e92b67093da1"
        assertEquals(expected, PushMailboxProtocol.putId(expiry, wire))
        assertTrue(expected != PushMailboxProtocol.putId(expiry + 1, wire))
        assertTrue(expected != PushMailboxProtocol.putId(expiry, envelope(2)))
        assertEquals(null, PushMailboxProtocol.putId(0, wire))
        assertEquals(null, PushMailboxProtocol.putId(PushMailboxProtocol.MAX_SAFE_INTEGER + 1, wire))
        assertEquals(null, PushMailboxProtocol.putId(expiry, "plaintext"))
        assertFalse(put().toString().contains(expected))
    }

    @Test fun envelopeRequiresCanonicalBase64AndExactSeparators() = runBlocking {
        val wire = envelope()
        val parts = wire.split('|')
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val ciphertext = parts[3]
        assertTrue(ciphertext.endsWith("="))
        val original = alphabet.indexOf(ciphertext[ciphertext.length - 2])
        val changed = ciphertext.dropLast(2) + alphabet[original or 1] + "="
        assertTrue(Base64.getDecoder().decode(ciphertext).contentEquals(Base64.getDecoder().decode(changed)))
        assertTrue(changed != ciphertext)
        assertTrue(PushMailboxProtocol.validEnvelope(wire))
        assertFalse(PushMailboxProtocol.validEnvelope(parts.take(3).joinToString("|") + "|" + changed))
        assertFalse(PushMailboxProtocol.validEnvelope(wire.dropLast(1)))
        assertFalse(PushMailboxProtocol.validEnvelope(wire + "|extra"))
        assertFalse(PushMailboxProtocol.validEnvelope(wire.replace("APUSEAL1|", "APUSEAL1|\\n")))
    }

    @Test fun malformedDuplicateOversizedAndAmbiguousResponsesFailClosed() = runBlocking {
        var json = ""
        val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, _, _ -> response(200, json) }, {})
        val badPages = listOf(
            "{\"items\":[],\"next\":\"$id\"}", page(next = null), page(next = "33".repeat(16)),
            page(items = item() + "," + item()),
            page(items = List(5) { item((it + 1).toString(16).padStart(32, '0')) }.joinToString(",")),
            page(items = item(value = "not ciphertext")),
            page(items = item().replace("$expiry", "-1")),
            page(items = item().replace("$expiry", "0$expiry")),
            page(items = item().replace("$expiry", "$expiry.0")),
            page().replace("\"items\":", "\"items\":[],\"items\":"),
            page().replace("\"next\":", "\"extra\":1,\"next\":"),
            page().replace("\"next\":\"$id\"", "\"next\":\"\\u0031$id\""),
            page() + "junk",
        )
        badPages.forEach {
            json = it
            assertEquals("accepted invalid page: ${it.take(30)}", PushMailboxHttpClient.PullResult.Unavailable, client.pull(pull()))
        }
        json = page()
        assertEquals("cursor cannot repeat", PushMailboxHttpClient.PullResult.Unavailable, client.pull(pull(id)))
        val nonAscii = page().toByteArray(Charsets.US_ASCII).also { it[0] = 0x80.toByte() }
        assertEquals(null, PushMailboxResponseCodec.page(nonAscii))
        json = " ".repeat(PushMailboxProtocol.MAX_RESPONSE_BYTES + 1)
        assertEquals(PushMailboxHttpClient.PullResult.Unavailable, client.pull(pull()))
        json = "{\"expiresAt\":$expiry,\"wakeQueued\":true,\"state\":\"stored\",\"state\":\"handled\"}"
        assertEquals(PushMailboxHttpClient.PutResult.Unavailable, client.put(put()))
        json = "{\"wakeQueued\":false,\"expiresAt\":$expiry,\"state\":\"handled\"}"
        assertTrue("field order is not significant", client.put(put()) is PushMailboxHttpClient.PutResult.Handled)
    }

    @Test fun cancellationRacingAnyMailboxResultOrErrorClearsBuffersAndPropagates() = runBlocking {
        for (throws in listOf(false, true)) {
            for (method in listOf("put", "pull", "ack")) {
                var requestBody = byteArrayOf()
                var responseBody = byteArrayOf()
                var diagnostics = 0
                val result = CompletableDeferred<Throwable?>()
                val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, _, body ->
                    requestBody = body
                    val response = response(if (method == "ack") 204 else 200, if (method == "put") stored() else page())
                    responseBody = response.body
                    currentCoroutineContext().cancel()
                    if (throws) {
                        // A throwing transport retains ownership of any response it created.
                        responseBody.fill(0)
                        throw IOException("private")
                    }
                    response
                }, { diagnostics++ })
                val job = launch {
                    result.complete(runCatching {
                        when (method) {
                            "put" -> client.put(put())
                            "pull" -> client.pull(pull())
                            else -> client.ack(ack())
                        }
                    }.exceptionOrNull())
                }
                job.join()
                assertTrue(result.await() is CancellationException)
                assertTrue(requestBody.isNotEmpty() && requestBody.all { it == 0.toByte() })
                assertTrue(responseBody.isNotEmpty() && responseBody.all { it == 0.toByte() })
                assertEquals(0, diagnostics)
            }
        }
    }

    @Test fun brokenDiagnosticsCannotMaskCancellationOrPretendMailboxSuccess() = runBlocking {
        for (method in listOf("put", "pull", "ack")) {
            var requestBody = byteArrayOf()
            val transport = PushMailboxPost { _, _, body -> requestBody = body; throw IOException("private") }
            val failing = PushMailboxHttpClient(endpoint(), transport, { throw IllegalStateException("private diagnostic") })
            when (method) {
                "put" -> assertEquals(PushMailboxHttpClient.PutResult.Unavailable, failing.put(put()))
                "pull" -> assertEquals(PushMailboxHttpClient.PullResult.Unavailable, failing.pull(pull()))
                else -> assertEquals(PushMailboxHttpClient.AckResult.UNAVAILABLE, failing.ack(ack()))
            }
            assertTrue(requestBody.isNotEmpty() && requestBody.all { it == 0.toByte() })
            for (throws in listOf(false, true)) {
                lateinit var child: Job
                val result = CompletableDeferred<Throwable?>()
                val cancelled = PushMailboxHttpClient(endpoint(), transport, {
                    child.cancel()
                    if (throws) throw IllegalStateException("private diagnostic")
                })
                child = launch(start = CoroutineStart.LAZY) {
                    result.complete(runCatching {
                        when (method) {
                            "put" -> cancelled.put(put())
                            "pull" -> cancelled.pull(pull())
                            else -> cancelled.ack(ack())
                        }
                    }.exceptionOrNull())
                }
                child.start()
                child.join()
                assertTrue(result.await() is CancellationException)
                assertTrue(requestBody.isNotEmpty() && requestBody.all { it == 0.toByte() })
            }
        }
    }

    @Test fun failureAndCancellationDoNotLeakOrTurnIntoSuccess() = runBlocking {
        var sent = byteArrayOf()
        var failures = 0
        var cancel = false
        val client = PushMailboxHttpClient(endpoint(), PushMailboxPost { _, _, body ->
            sent = body
            if (cancel) throw CancellationException("secret") else throw IOException("secret")
        }, { failures++ })
        assertEquals(PushMailboxHttpClient.PutResult.Unavailable, client.put(put()))
        assertEquals(1, failures)
        assertTrue(sent.all { it == 0.toByte() })
        cancel = true
        assertTrue(runCatching { client.pull(pull()) }.exceptionOrNull() is CancellationException)
        assertEquals(1, failures)
        assertTrue(sent.all { it == 0.toByte() })
    }
}
