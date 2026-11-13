package com.vladimir.messenger.data.push

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real OkHttp request/response objects with controllable calls; no provider is contacted. */
class PushMailboxHttpsTransportTest {
    private fun endpoint() = PushEndpoint.approved(
        "https://wake.example.workers.dev", setOf("wake.example.workers.dev"),
    )!!

    private class TrackedBody(payload: ByteArray) : ResponseBody() {
        val closed = AtomicBoolean()
        val reads = AtomicInteger()
        private val source = object : ForwardingSource(Buffer().write(payload)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads.incrementAndGet()
                return super.read(sink, byteCount)
            }
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }

    private class FakeCall(private val request: Request, private val onEnqueue: (FakeCall) -> Unit) : Call {
        private val cancelled = AtomicBoolean()
        private val executed = AtomicBoolean()
        lateinit var callback: Callback
        override fun request(): Request = request
        override fun execute(): Response = error("synchronous HTTP must not be used")
        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true))
            callback = responseCallback
            onEnqueue(this)
        }
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout()
        override fun clone(): Call = FakeCall(request, onEnqueue)
        fun fail() = callback.onFailure(this, IOException("private fake failure"))
        fun respond(response: Response) = callback.onResponse(this, response)
        fun bodyBytes(): ByteArray = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
    }

    private class FakeFactory(private val onEnqueue: (FakeCall) -> Unit = {}) : Call.Factory {
        val calls = Channel<FakeCall>(Channel.UNLIMITED)
        val created = AtomicInteger()
        override fun newCall(request: Request): Call {
            created.incrementAndGet()
            return FakeCall(request) { call -> calls.trySend(call); onEnqueue(call) }
        }
    }

    private fun reply(
        call: FakeCall,
        code: Int = 200,
        payload: ByteArray = "{}".toByteArray(),
        headers: List<Pair<String, String>> = listOf("Content-Type" to "application/json"),
    ): Pair<Response, TrackedBody> {
        val body = TrackedBody(payload)
        val h = Headers.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
        return Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").headers(h).body(body).build() to body
    }

    @Test fun clientPolicyHasTotalAndSocketTimeoutsAndDoesNotFollowRedirects() {
        val client = PushHttpTransport.configuredClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(Authenticator.NONE, client.authenticator)
        assertEquals(Authenticator.NONE, client.proxyAuthenticator)
        assertEquals(null, client.cache)
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(5_000, client.writeTimeoutMillis)
        assertEquals(5_000, client.readTimeoutMillis)
        assertEquals(15_000, client.callTimeoutMillis)
        assertTrue(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())
    }

    @Test fun approvedHttpsPathAndHeadersReachTheFactoryWithAnIsolatedBody() = runBlocking {
        val original = "private request".toByteArray()
        var sent = byteArrayOf()
        lateinit var call: FakeCall
        lateinit var responseBody: TrackedBody
        val factory = FakeFactory { current ->
            call = current
            val request = current.request()
            assertEquals("https://wake.example.workers.dev/v1/mailbox/pull", request.url.toString())
            assertEquals("POST", request.method)
            assertEquals("application/json", request.body!!.contentType().toString())
            assertEquals("application/json", request.header("Accept"))
            assertEquals("identity", request.header("Accept-Encoding"))
            sent = current.bodyBytes()
            val (response, body) = reply(current)
            responseBody = body
            current.respond(response)
        }
        val result = PushHttpTransport(factory).post(endpoint(), "/v1/mailbox/pull", original, json = true)
        assertEquals(200, result.status)
        assertArrayEquals("{}".toByteArray(), result.body)
        assertArrayEquals(original, sent)
        assertTrue(responseBody.closed.get())
        assertTrue(call.bodyBytes().all { it == 0.toByte() })
        assertArrayEquals("private request".toByteArray(), original)
        result.body.fill(0)
    }

    @Test fun redirectsAndErrorBodiesAreClosedWithoutBeingReadOrReturned() = runBlocking {
        for (code in listOf(204, 302, 400, 403, 409, 429, 503)) {
            lateinit var tracked: TrackedBody
            val factory = FakeFactory { call ->
                val (response, body) = reply(call, code, "private error".toByteArray())
                tracked = body
                call.respond(response)
            }
            val response = PushHttpTransport(factory).post(endpoint(), "/v1/mailbox/ack", byteArrayOf(1), json = true)
            assertEquals(code, response.status)
            assertTrue(response.body.isEmpty())
            assertTrue(tracked.closed.get())
            assertEquals(0, tracked.reads.get())
        }
    }

    @Test fun malformedOrAmbiguousHeadersFailBeforeReadingTheBody() = runBlocking {
        val cap = PushMailboxProtocol.MAX_RESPONSE_BYTES
        val bad = listOf(
            emptyList(), listOf("Content-Type" to "text/plain"),
            listOf("Content-Type" to "application/json", "Content-Encoding" to "gzip"),
            listOf("Content-Type" to "application/json", "Content-Length" to "-1"),
            listOf("Content-Type" to "application/json", "Content-Length" to "4,4"),
            listOf("Content-Type" to "application/json", "Content-Length" to (cap + 1).toString()),
            listOf("Content-Type" to "application/json", "Content-Length" to "9".repeat(40)),
            listOf("Content-Type" to "application/json", "Content-Type" to "application/json"),
            listOf("Content-Type" to "application/json", "Content-Length" to "2", "Content-Length" to "2"),
            listOf("Content-Type" to "application/json", "Content-Encoding" to "identity", "Content-Encoding" to "identity"),
        )
        for (headers in bad) {
            lateinit var tracked: TrackedBody
            val factory = FakeFactory { call ->
                val (response, body) = reply(call, headers = headers)
                tracked = body
                call.respond(response)
            }
            val failure = runCatching { PushHttpTransport(factory).post(endpoint(), "/v1/mailbox/put", byteArrayOf(), true) }.exceptionOrNull()
            assertTrue("invalid response headers accepted", failure is IOException)
            assertTrue(tracked.closed.get())
            assertEquals(0, tracked.reads.get())
        }
    }

    @Test fun streamingCapAndDeclaredLengthAreBothEnforced() = runBlocking {
        val cap = PushMailboxProtocol.MAX_RESPONSE_BYTES
        val cases = listOf(
            ByteArray(cap + 1) { 1 } to emptyList(),
            "{}".toByteArray() to listOf("Content-Length" to "3"),
            "{}".toByteArray() to listOf("Content-Length" to "1"),
        )
        for ((bytes, extra) in cases) {
            lateinit var tracked: TrackedBody
            val factory = FakeFactory { call ->
                val (response, body) = reply(call, payload = bytes, headers = listOf("Content-Type" to "application/json") + extra)
                tracked = body
                call.respond(response)
            }
            val failure = runCatching { PushHttpTransport(factory).post(endpoint(), "/v1/mailbox/pull", byteArrayOf(), true) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(tracked.closed.get() && tracked.reads.get() > 0)
        }
        val payload = ByteArray(cap) { 3 }
        val factory = FakeFactory { call ->
            call.respond(reply(call, payload = payload, headers = listOf(
                "Content-Type" to "Application/JSON; charset=utf-8", "Content-Encoding" to "IDENTITY",
                "Content-Length" to cap.toString(),
            )).first)
        }
        val result = PushHttpTransport(factory).post(endpoint(), "/v1/mailbox/pull", byteArrayOf(), true)
        assertArrayEquals(payload, result.body)
        result.body.fill(0)
    }

    @Test fun unapprovedPathsAndOversizedRequestsNeverCreateACall() = runBlocking {
        val factory = FakeFactory()
        val transport = PushHttpTransport(factory)
        for (path in listOf("/v1/mailbox", "/v1/mailbox/pull?secret=1", "/other", "https://evil.invalid/")) {
            val failure = runCatching { transport.post(endpoint(), path, byteArrayOf(), true) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        val failure = runCatching {
            transport.post(endpoint(), "/v1/wake", ByteArray(PushHttpTransport.MAX_REQUEST_BYTES + 1), false)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, factory.created.get())
    }

    @Test fun cancellationCancelsTheCallAndLateResponsesAreClosedWithoutReading() = runBlocking {
        val factory = FakeFactory()
        val original = "secret body".toByteArray()
        val transport = PushHttpTransport(factory)
        val job = async { transport.post(endpoint(), "/v1/mailbox/pull", original, true) }
        val call = withTimeout(2_000) { factory.calls.receive() }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        // The network's private copy stays intact until its callback finishes.
        assertArrayEquals(original, call.bodyBytes())
        val (response, tracked) = reply(call)
        call.respond(response)
        assertTrue(tracked.closed.get())
        assertEquals(0, tracked.reads.get())
        assertTrue(call.bodyBytes().all { it == 0.toByte() })
        assertArrayEquals("secret body".toByteArray(), original)
    }

    @Test fun timedOutCallsKeepTheirSlotUntilTheirRealCallbackFinishes() = runBlocking {
        val factory = FakeFactory()
        val transport = PushHttpTransport(factory, deadlineMs = 150, maxCalls = 1)
        val first = async { runCatching { transport.post(endpoint(), "/v1/wake", "secret".toByteArray(), false) }.exceptionOrNull() }
        val call = withTimeout(2_000) { factory.calls.receive() }
        assertTrue(first.await() is IOException)
        assertTrue(call.isCanceled())
        assertEquals("secret", call.bodyBytes().toString(Charsets.US_ASCII))
        val second = runCatching { transport.post(endpoint(), "/v1/wake", byteArrayOf(2), false) }.exceptionOrNull()
        assertTrue(second is IOException)
        assertEquals(1, factory.created.get()) // timeout did not free an active physical call
        call.fail()
        assertTrue(call.bodyBytes().all { it == 0.toByte() })
        val third = async { transport.post(endpoint(), "/v1/wake", byteArrayOf(3), false) }
        val fresh = withTimeout(2_000) { factory.calls.receive() }
        fresh.respond(reply(fresh, code = 202).first)
        assertEquals(202, third.await().status)
        assertEquals(2, factory.created.get())
    }

    @Test fun parentCancellationIsNotConvertedIntoAnInternalDeadlineFailure() = runBlocking {
        val factory = FakeFactory()
        val transport = PushHttpTransport(factory, deadlineMs = 2_000)
        val task = async {
            runCatching { withTimeout(100) { transport.post(endpoint(), "/v1/wake", byteArrayOf(7), false) } }.exceptionOrNull()
        }
        val call = withTimeout(2_000) { factory.calls.receive() }
        assertTrue(task.await() is CancellationException)
        assertTrue(call.isCanceled())
        call.fail()
        assertTrue(call.bodyBytes().all { it == 0.toByte() })
    }

    @Test fun enqueueFailureReturnsItsSlotAndClearsTheRequestCopy() = runBlocking {
        lateinit var failed: FakeCall
        val failedFactory = FakeFactory { call -> failed = call; throw IllegalStateException("private data") }
        val transport = PushHttpTransport(failedFactory, deadlineMs = 500, maxCalls = 1)
        repeat(2) {
            val result = runCatching { transport.post(endpoint(), "/v1/wake", "private".toByteArray(), false) }.exceptionOrNull()
            assertTrue(result is IOException)
            assertTrue(failed.bodyBytes().all { it == 0.toByte() })
        }
        assertEquals(2, failedFactory.created.get())
    }
}
