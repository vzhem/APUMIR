package com.vladimir.messenger.data.push

import com.vladimir.messenger.util.readBytesAtMost
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared transport for the disabled push clients. Cancellation calls OkHttp's
 * cancel, and the coroutine deadline includes waiting for a slot and the entire
 * asynchronous call. The separate OkHttp call/connect/write/read limits are a
 * second line of defence. A cancelled request may already have reached the
 * server; it is never a delivery receipt and must be retried idempotently.
 *
 * At most [MAX_CALLS] physical calls may remain outstanding, even if a DNS or
 * platform operation ignores cancellation. A slot and the private request
 * buffer are held until the network callback actually finishes, rather than
 * being released when its caller times out. If callbacks never arrive, later
 * callers time out instead of accumulating more physical calls. This is a
 * classloader-local limit, not a cross-process bound or a lifecycle lock.
 *
 * Only a fixed, validated [PushEndpoint] and its allowlisted paths are used.
 * The real client has system TLS, no redirect, no retry, no cookies, no cache,
 * and no authenticator. Response and exception contents are never logged here.
 * The JVM, OkHttp and the platform may retain copies of request data.
 */
internal class PushHttpTransport(
    private val calls: Call.Factory = configuredClient(),
    private val deadlineMs: Long = DEADLINE_MS,
    maxCalls: Int = MAX_CALLS,
) {
    private val slots = Semaphore(maxCalls)

    init {
        require(deadlineMs in 1..DEADLINE_MS)
        require(maxCalls in 1..MAX_CALLS)
    }

    /** JSON is needed only for mailbox 200/201; all other bodies are discarded. */
    suspend fun post(endpoint: PushEndpoint, path: String, body: ByteArray, json: Boolean): PushMailboxHttpResponse {
        require(body.size <= MAX_REQUEST_BYTES)
        // Validate the path before allocating a slot or starting any call.
        val url = endpoint.url(path)
        var heldResponse: PushMailboxHttpResponse? = null
        return try {
            val result = withTimeoutOrNull(deadlineMs) {
                // The timeout can race even after the continuation received a
                // response. Keep ownership until this scope returns to its caller.
                send(url, body, json).also { heldResponse = it }
            } ?: throw IOException("push request deadline exceeded")
            currentCoroutineContext().ensureActive()
            heldResponse = null
            result
        } finally { heldResponse?.body?.fill(0) }
    }

    private suspend fun send(url: java.net.URL, body: ByteArray, json: Boolean): PushMailboxHttpResponse {
        slots.acquire()
        var owned: ByteArray? = null
        var handedOff = false
        return try {
            currentCoroutineContext().ensureActive()
            val copy = body.copyOf()
            owned = copy
            suspendCancellableCoroutine { continuation ->
                handedOff = true
                val finished = AtomicBoolean(false)
                fun finish() {
                    if (finished.compareAndSet(false, true)) {
                        copy.fill(0)
                        slots.release()
                    }
                }
                var submitted = false
                try {
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    val request = Request.Builder().url(url).post(copy.toRequestBody(JSON))
                        .header("Accept", "application/json")
                        // Disable OkHttp's transparent gzip; enforce the wire limit ourselves.
                        .header("Accept-Encoding", "identity")
                        .build()
                    val call = calls.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, error: IOException) {
                            try {
                                // Do not expose the URL or provider/exception text.
                                continuation.resumeWithException(IOException("push transport unavailable"))
                            } finally { finish() }
                        }

                        @OptIn(ExperimentalCoroutinesApi::class)
                        override fun onResponse(call: Call, response: Response) {
                            var result: PushMailboxHttpResponse? = null
                            try {
                                response.use {
                                    if (!continuation.isActive) return
                                    result = readResponse(it, json)
                                }
                                val ready = checkNotNull(result)
                                // If cancellation wins before the caller receives it,
                                // no one else owns these potentially sensitive bytes.
                                continuation.resume(ready) { ready.body.fill(0) }
                                result = null
                            } catch (_: Exception) {
                                result?.body?.fill(0)
                                if (continuation.isActive) Logger.getLogger("PushHttpTransport").warning("Push response unavailable")
                                continuation.resumeWithException(IOException("push response unavailable"))
                            } finally { finish() }
                        }
                    })
                    submitted = true
                } catch (_: Exception) {
                    // Do not pass exception text or request data to diagnostics.
                    if (continuation.isActive) Logger.getLogger("PushHttpTransport").warning("Push transport unavailable")
                    continuation.resumeWithException(IOException("push transport unavailable"))
                } finally {
                    if (!submitted) finish()
                }
            }
        } finally {
            if (!handedOff) {
                owned?.fill(0)
                slots.release()
            }
        }
    }

    private fun readResponse(response: Response, json: Boolean): PushMailboxHttpResponse {
        val status = response.code
        if (!json || status != 200 && status != 201) return PushMailboxHttpResponse(status, byteArrayOf())
        val type = singleHeader(response, "Content-Type")?.split(';')?.firstOrNull()?.trim()?.lowercase(Locale.ROOT)
        val encoding = singleHeader(response, "Content-Encoding")?.trim()?.lowercase(Locale.ROOT)
        if (type != "application/json" || encoding != null && encoding != "identity") throw IOException("invalid push response")
        val declared = singleHeader(response, "Content-Length")
        if (declared != null && (!declared.matches(Regex("^[0-9]+$")) ||
                (declared.toLongOrNull() ?: Long.MAX_VALUE) > PushMailboxProtocol.MAX_RESPONSE_BYTES)
        ) throw IOException("invalid push response")
        val stream = response.body?.byteStream() ?: throw IOException("missing push response")
        val bytes = stream.use { it.readBytesAtMost(PushMailboxProtocol.MAX_RESPONSE_BYTES.toLong()) }
        if (declared != null && declared.toLong() != bytes.size.toLong()) {
            bytes.fill(0)
            throw IOException("invalid push response")
        }
        return PushMailboxHttpResponse(status, bytes)
    }

    private fun singleHeader(response: Response, name: String): String? {
        val values = response.headers.values(name)
        if (values.size > 1) throw IOException("ambiguous push response")
        return values.singleOrNull()
    }

    companion object {
        internal const val DEADLINE_MS = 20_000L
        internal const val MAX_CALLS = 4
        internal const val MAX_REQUEST_BYTES = PushMailboxProtocol.MAX_ENVELOPE_CHARS + 4096
        private val JSON = "application/json".toMediaType()

        internal fun configuredClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** Both clients share the same process-local physical-call budget. */
internal object SharedPushHttpTransport {
    private val transport = PushHttpTransport()
    suspend fun post(endpoint: PushEndpoint, path: String, body: ByteArray, json: Boolean): PushMailboxHttpResponse =
        transport.post(endpoint, path, body, json)
}
