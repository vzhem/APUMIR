package com.vladimir.messenger.data.push

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real HTTP policy against fake connections, never TLS or a provider. */
class PushMailboxHttpsTransportTest {
    private fun endpoint() = PushEndpoint.approved(
        "https://wake.example.workers.dev", setOf("wake.example.workers.dev"),
    )!!

    private class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private class FakeConnection(
        url: URL,
        private val code: Int = 200,
        payload: ByteArray = byteArrayOf(),
        private val type: String? = "application/json; charset=utf-8",
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpsURLConnection(url) {
        val incoming = TrackingInput(payload)
        val errorBody = TrackingInput(payload)
        val outgoing = ByteArrayOutputStream()
        var disconnected = false
        var fixedLength = -1
        var openedInput = false
        var openedError = false

        override fun getResponseCode(): Int = code
        override fun getContentType(): String? = type
        override fun getHeaderField(name: String?): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun getOutputStream(): OutputStream = outgoing
        override fun getInputStream(): InputStream { openedInput = true; return incoming }
        override fun getErrorStream(): InputStream { openedError = true; return errorBody }
        override fun setFixedLengthStreamingMode(contentLength: Int) {
            fixedLength = contentLength
            super.setFixedLengthStreamingMode(contentLength)
        }
        override fun connect() { connected = true }
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "fake"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun postsOnlyToTheReviewedHttpsHostWithRedirectsDisabledAndSocketTimeouts() {
        val payload = "{\"items\":[],\"next\":null}".toByteArray(Charsets.US_ASCII)
        val request = "test request".toByteArray(Charsets.US_ASCII)
        lateinit var connection: FakeConnection
        val response = HttpsPushMailboxPost.postWithConnection(endpoint(), "/v1/mailbox/pull", request) { url ->
            assertEquals("https://wake.example.workers.dev/v1/mailbox/pull", url.toString())
            FakeConnection(url, payload = payload, headers = mapOf("Content-Length" to payload.size.toString()))
                .also { connection = it }
        }
        assertEquals(200, response.status)
        assertArrayEquals(payload, response.body)
        assertArrayEquals(request, connection.outgoing.toByteArray())
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertFalse(connection.useCaches)
        assertTrue(connection.doOutput)
        assertEquals(request.size, connection.fixedLength)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertTrue(connection.openedInput && connection.incoming.closed && connection.disconnected)
        assertFalse(connection.openedError)
    }

    @Test fun redirectsAndErrorBodiesAreNeverReturnedAsSuccessData() {
        for (code in listOf(204, 302, 400, 403, 409, 429, 503)) {
            lateinit var connection: FakeConnection
            val response = HttpsPushMailboxPost.postWithConnection(endpoint(), "/v1/mailbox/ack", byteArrayOf(1)) { url ->
                FakeConnection(url, code, "not a receipt".toByteArray()).also { connection = it }
            }
            assertEquals(code, response.status)
            assertTrue(response.body.isEmpty())
            assertTrue(connection.disconnected)
            if (code >= 400) assertTrue(connection.openedError && connection.errorBody.closed)
            else assertTrue(connection.openedInput && connection.incoming.closed)
        }
    }

    @Test fun malformedContentTypeEncodingAndDeclaredLengthsFailClosed() {
        val cap = PushMailboxProtocol.MAX_RESPONSE_BYTES
        val invalid = listOf(
            null to emptyMap(),
            "text/plain" to emptyMap(),
            "application/json" to mapOf("Content-Encoding" to "gzip"),
            "application/json" to mapOf("Content-Length" to "-1"),
            "application/json" to mapOf("Content-Length" to "4,4"),
            "application/json" to mapOf("Content-Length" to (cap + 1).toString()),
            "application/json" to mapOf("Content-Length" to "9".repeat(40)),
        )
        for ((type, headers) in invalid) {
            lateinit var connection: FakeConnection
            val failure = runCatching {
                HttpsPushMailboxPost.postWithConnection(endpoint(), "/v1/mailbox/put", byteArrayOf()) { url ->
                    FakeConnection(url, payload = "{}".toByteArray(), type = type, headers = headers)
                        .also { connection = it }
                }
            }.exceptionOrNull()
            assertTrue("invalid response headers accepted", failure is IOException)
            assertTrue(connection.disconnected)
            assertFalse(connection.openedInput)
        }
    }

    @Test fun identityEncodingAndExactlyTheResponseCapRemainAcceptable() {
        val cap = PushMailboxProtocol.MAX_RESPONSE_BYTES
        val payload = ByteArray(cap) { 'x'.code.toByte() }
        lateinit var connection: FakeConnection
        val response = HttpsPushMailboxPost.postWithConnection(endpoint(), "/v1/mailbox/pull", byteArrayOf()) { url ->
            FakeConnection(url, payload = payload, type = "Application/JSON; charset=utf-8",
                headers = mapOf("Content-Encoding" to "IDENTITY", "Content-Length" to cap.toString()))
                .also { connection = it }
        }
        assertEquals(200, response.status)
        assertEquals(cap, response.body.size)
        assertArrayEquals(payload, response.body)
        assertTrue(connection.incoming.closed && connection.disconnected)
        response.body.fill(0)
    }

    @Test fun streamingCapAndDeclaredLengthAreBothEnforcedAndStreamsClosed() {
        val cap = PushMailboxProtocol.MAX_RESPONSE_BYTES
        val cases = listOf(
            ByteArray(cap + 1) { 'x'.code.toByte() } to emptyMap(),
            "{}".toByteArray() to mapOf("Content-Length" to "3"),
            "{}".toByteArray() to mapOf("Content-Length" to "1"),
        )
        for ((payload, headers) in cases) {
            lateinit var connection: FakeConnection
            val failure = runCatching {
                HttpsPushMailboxPost.postWithConnection(endpoint(), "/v1/mailbox/pull", byteArrayOf()) { url ->
                    FakeConnection(url, payload = payload, headers = headers).also { connection = it }
                }
            }.exceptionOrNull()
            assertTrue("oversized or truncated response accepted", failure is IOException)
            assertTrue(connection.openedInput && connection.incoming.closed && connection.disconnected)
        }
    }

    @Test fun pathsOutsideTheEndpointAllowlistCannotOpenAConnection() {
        for (path in listOf("/v1/mailbox", "/v1/mailbox/pull?secret=1", "/other", "https://evil.invalid/")) {
            var opened = false
            val failure = runCatching {
                HttpsPushMailboxPost.postWithConnection(endpoint(), path, byteArrayOf()) { url ->
                    opened = true
                    FakeConnection(url)
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(opened)
        }
    }
}
