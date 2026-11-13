package com.vladimir.messenger.data.push

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real OkHttp and real TLS on a disposable loopback server. Only the test call
 * factory rewrites the already-validated endpoint to localhost and only the
 * test client trusts its throwaway certificate; neither reaches a provider.
 */
class PushHttpLoopbackTlsTest {
    private fun endpoint() = PushEndpoint.approved(
        "https://wake.example.workers.dev", setOf("wake.example.workers.dev"),
    )!!

    private class Certificate : Closeable {
        private val directory = Files.createTempDirectory("apu-push-test-tls")
        private val file = directory.resolve("test.p12")
        private val password = "test-only-password".toCharArray()
        val server: SSLContext
        val client: SSLContext
        val trust: X509TrustManager

        init {
            // The private key is generated only in a disposable temp directory.
            val executable = java.io.File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val process = ProcessBuilder(executable, "-genkeypair", "-noprompt", "-alias", "loopback",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", String(password), "-keypass", String(password),
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost")
                .redirectErrorStream(true).start()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "test certificate generation timed out" }
            check(process.exitValue() == 0) { "test certificate generation failed" }
            process.inputStream.close()
            val keys = KeyStore.getInstance("PKCS12")
            Files.newInputStream(file).use { keys.load(it, password) }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            trust = trustManagers.trustManagers.filterIsInstance<X509TrustManager>().single()
            server = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
            client = SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
        }

        override fun close() {
            password.fill('\u0000')
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    private class Server(context: SSLContext, handler: (SSLSocket) -> Unit) : Closeable {
        private val socket = context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        private val acceptedSocket = AtomicReference<SSLSocket?>()
        val accepted = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val port: Int = socket.localPort
        private val worker = thread(name = "push-loopback-test", isDaemon = true) {
            try {
                val peer = socket.accept() as SSLSocket
                acceptedSocket.set(peer)
                accepted.countDown()
                peer.use { it.soTimeout = 4_000; handler(it) }
            } catch (error: Exception) {
                // The untrusted-cert test intentionally causes a handshake failure.
                failure.set(error)
            } finally { done.countDown() }
        }

        override fun close() {
            socket.close()
            acceptedSocket.get()?.close()
            worker.join(5_000)
            check(!worker.isAlive) { "loopback test server did not stop" }
        }
    }

    private fun factory(client: OkHttpClient, port: Int): Call.Factory = Call.Factory { original: Request ->
        assertEquals("https", original.url.scheme)
        assertEquals("wake.example.workers.dev", original.url.host)
        val loopback = original.url.newBuilder().host("localhost").port(port).build()
        client.newCall(original.newBuilder().url(loopback).build())
    }

    private fun trustedClient(certificate: Certificate): OkHttpClient = PushHttpTransport.configuredClient().newBuilder()
        .proxy(Proxy.NO_PROXY)
        .sslSocketFactory(certificate.client.socketFactory, certificate.trust)
        .build()

    private inline fun <T> usingClient(client: OkHttpClient, block: (OkHttpClient) -> T): T = try {
        block(client)
    } finally {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private data class RequestBytes(val headers: String, val body: ByteArray)

    private fun readRequest(socket: SSLSocket): RequestBytes {
        val input = socket.inputStream
        val header = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            check(byte >= 0 && header.size() < 8192)
            header.write(byte)
            val size = header.size()
            val snapshot = header.toByteArray()
            if (size >= 4 && snapshot[size - 4] == 13.toByte() && snapshot[size - 3] == 10.toByte() &&
                snapshot[size - 2] == 13.toByte() && snapshot[size - 1] == 10.toByte()
            ) break
        }
        val text = header.toString("ISO-8859-1")
        val length = text.lineSequence().first { it.startsWith("Content-Length:", ignoreCase = true) }
            .substringAfter(':').trim().toInt()
        check(length in 0..PushHttpTransport.MAX_REQUEST_BYTES)
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            check(count > 0)
            offset += count
        }
        return RequestBytes(text, body)
    }

    private fun writeResponse(socket: SSLSocket, code: String, headers: String = "", body: ByteArray = byteArrayOf()) {
        val output = socket.outputStream
        output.write(("HTTP/1.1 $code\r\n$headers" + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    @Test fun localTlsRoundTripUsesTheExpectedRequestAndClosesTheResponse() = runBlocking {
        Certificate().use { certificate ->
            val received = AtomicReference<RequestBytes>()
            Server(certificate.server) { socket ->
                received.set(readRequest(socket))
                writeResponse(socket, "200 OK", "Content-Type: application/json\r\n", "{\"items\":[],\"next\":null}".toByteArray())
            }.use { server ->
                usingClient(trustedClient(certificate)) { client ->
                    val transport = PushHttpTransport(factory(client, server.port))
                    val request = "local test only".toByteArray()
                    val response = transport.post(endpoint(), "/v1/mailbox/pull", request, true)
                    assertEquals(200, response.status)
                    assertArrayEquals("{\"items\":[],\"next\":null}".toByteArray(), response.body)
                    assertTrue(server.done.await(3, TimeUnit.SECONDS))
                    assertEquals(null, server.failure.get())
                    val seen = received.get()
                    assertTrue(seen.headers.startsWith("POST /v1/mailbox/pull HTTP/1.1\r\n"))
                    assertTrue(seen.headers.contains("Accept-Encoding: identity", ignoreCase = true))
                    assertArrayEquals(request, seen.body)
                    response.body.fill(0)
                    seen.body.fill(0)
                }
            }
        }
    }

    @Test fun untrustedTlsCertificateFailsAndRedirectIsNeverFollowed() = runBlocking {
        Certificate().use { certificate ->
            Server(certificate.server) { socket -> socket.startHandshake() }.use { server ->
                usingClient(PushHttpTransport.configuredClient().newBuilder().proxy(Proxy.NO_PROXY).build()) { client ->
                    val error = runCatching {
                        PushHttpTransport(factory(client, server.port)).post(endpoint(), "/v1/wake", byteArrayOf(1), false)
                    }.exceptionOrNull()
                    assertTrue(error is IOException)
                    assertTrue(server.done.await(3, TimeUnit.SECONDS))
                }
            }
            Server(certificate.server) { socket -> readRequest(socket); error("redirect target must not be contacted") }.use { target ->
                Server(certificate.server) { socket ->
                    readRequest(socket)
                    writeResponse(socket, "302 Found", "Location: https://localhost:${target.port}/leak\r\n", "discard me".toByteArray())
                }.use { source ->
                    usingClient(trustedClient(certificate)) { client ->
                        val transport = PushHttpTransport(factory(client, source.port))
                        val response = transport.post(endpoint(), "/v1/mailbox/pull", byteArrayOf(1), true)
                        assertEquals(302, response.status)
                        assertTrue(response.body.isEmpty())
                        assertTrue(source.done.await(3, TimeUnit.SECONDS))
                        assertFalse(target.accepted.await(200, TimeUnit.MILLISECONDS))
                        assertEquals(null, source.failure.get())
                    }
                }
            }
        }
    }

    @Test fun parentCancellationClosesARealTlsCallWaitingForAResponse() = runBlocking {
        Certificate().use { certificate ->
            val ready = CountDownLatch(1)
            val closed = AtomicBoolean()
            Server(certificate.server) { socket ->
                readRequest(socket)
                ready.countDown()
                try { closed.set(socket.inputStream.read() == -1) }
                catch (_: SocketTimeoutException) { /* A timeout is not evidence of cancellation. */ }
                catch (_: IOException) { closed.set(true) }
            }.use { server ->
                usingClient(trustedClient(certificate)) { client ->
                    val transport = PushHttpTransport(factory(client, server.port))
                    val job = async(Dispatchers.Default) { transport.post(endpoint(), "/v1/wake", byteArrayOf(1), false) }
                    assertTrue(ready.await(3, TimeUnit.SECONDS))
                    job.cancelAndJoin()
                    assertTrue(server.done.await(3, TimeUnit.SECONDS))
                    assertTrue(closed.get())
                    assertEquals(null, server.failure.get())
                }
            }
        }
    }

    @Test fun deadlineAlsoClosesARealTlsCallStalledMidResponseBody() = runBlocking {
        Certificate().use { certificate ->
            val ready = CountDownLatch(1)
            val closed = AtomicBoolean()
            Server(certificate.server) { socket ->
                readRequest(socket)
                socket.outputStream.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{"
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.outputStream.flush()
                ready.countDown()
                try { closed.set(socket.inputStream.read() == -1) }
                catch (_: SocketTimeoutException) { /* A timeout is not evidence of cancellation. */ }
                catch (_: IOException) { closed.set(true) }
            }.use { server ->
                usingClient(trustedClient(certificate)) { client ->
                    val transport = PushHttpTransport(factory(client, server.port), deadlineMs = 2_000)
                    val job = async(Dispatchers.Default) {
                        runCatching { transport.post(endpoint(), "/v1/mailbox/pull", byteArrayOf(1), true) }.exceptionOrNull()
                    }
                    assertTrue(ready.await(3, TimeUnit.SECONDS))
                    assertTrue(withTimeout(4_000) { job.await() } is IOException)
                    assertTrue(server.done.await(3, TimeUnit.SECONDS))
                    assertTrue(closed.get())
                    assertEquals(null, server.failure.get())
                }
            }
        }
    }

    @Test fun overallDeadlineCancelsARealTlsCallAfterRequestWrite() = runBlocking {
        Certificate().use { certificate ->
            val ready = CountDownLatch(1)
            val closed = AtomicBoolean()
            Server(certificate.server) { socket ->
                readRequest(socket)
                ready.countDown()
                try { closed.set(socket.inputStream.read() == -1) }
                catch (_: SocketTimeoutException) { /* A timeout is not evidence of cancellation. */ }
                catch (_: IOException) { closed.set(true) }
            }.use { server ->
                usingClient(trustedClient(certificate)) { client ->
                    val transport = PushHttpTransport(factory(client, server.port), deadlineMs = 2_000)
                    val job = async(Dispatchers.Default) {
                        runCatching { transport.post(endpoint(), "/v1/wake", byteArrayOf(1), false) }.exceptionOrNull()
                    }
                    assertTrue(ready.await(3, TimeUnit.SECONDS))
                    assertTrue(withTimeout(4_000) { job.await() } is IOException)
                    assertTrue(server.done.await(3, TimeUnit.SECONDS))
                    assertTrue(closed.get())
                    assertEquals(null, server.failure.get())
                }
            }
        }
    }
}
