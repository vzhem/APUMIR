package com.vladimir.messenger.data.push

import com.vladimir.messenger.util.readBytesAtMost
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/**
 * Disabled mailbox transport seam; no Firebase, scheduler, local outbox or
 * receive router invokes it yet. Call off the UI thread with a fixed reviewed
 * endpoint. A stored/handled/accepted response is NOT proof of user delivery.
 * An ACK must only be created after authenticated opening and durable handling.
 */
internal class PushMailboxHttpClient(
    private val endpoint: PushEndpoint,
    private val transport: PushMailboxPost = HttpsPushMailboxPost,
    private val recordFailure: () -> Unit,
) {
    sealed interface PutResult {
        class Stored(val expiresAt: Long, val wakeQueued: Boolean) : PutResult
        class Handled(val expiresAt: Long) : PutResult
        object RouteUnavailable : PutResult
        object Conflict : PutResult
        object Throttled : PutResult
        object Rejected : PutResult
        object Unavailable : PutResult
    }

    sealed interface PullResult {
        class Page(val items: List<PushMailboxItem>, val next: String?) : PullResult {
            override fun toString(): String = "PushMailboxPage(redacted)"
        }
        object CursorExpired : PullResult
        object Throttled : PullResult
        object Rejected : PullResult
        object Unavailable : PullResult
    }

    enum class AckResult { ACCEPTED, THROTTLED, REJECTED, UNAVAILABLE }

    fun put(request: PushMailboxPutRequest): PutResult {
        if (!request.isValid()) return PutResult.Rejected
        val body = request.toJsonBytes()
        return try {
            val response = transport.post(endpoint, "/v1/mailbox/put", body)
            try {
                when (response.status) {
                    200, 201 -> {
                        val parsed = PushMailboxResponseCodec.put(response.body) ?: return PutResult.Unavailable
                        when {
                            response.status == 201 && (parsed.state != "stored" || !parsed.wakeQueued) -> PutResult.Unavailable
                            parsed.state == "stored" -> PutResult.Stored(parsed.expiresAt, parsed.wakeQueued)
                            parsed.state == "handled" -> PutResult.Handled(parsed.expiresAt)
                            else -> PutResult.Unavailable
                        }
                    }
                    404 -> PutResult.RouteUnavailable
                    409 -> PutResult.Conflict
                    429 -> PutResult.Throttled
                    400, 403 -> PutResult.Rejected
                    else -> PutResult.Unavailable
                }
            } finally { response.body.fill(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Only a generic counter; never include request or response data.
            recordFailure()
            PutResult.Unavailable
        } finally { body.fill(0) }
    }

    fun pull(request: PushMailboxPullRequest): PullResult {
        if (!request.isValid()) return PullResult.Rejected
        val body = request.toJsonBytes()
        return try {
            val response = transport.post(endpoint, "/v1/mailbox/pull", body)
            try {
                when (response.status) {
                    200 -> {
                        val page = PushMailboxResponseCodec.page(response.body) ?: return PullResult.Unavailable
                        if (request.after != "-" && page.items.any { it.id == request.after }) return PullResult.Unavailable
                        PullResult.Page(page.items, page.next)
                    }
                    409 -> PullResult.CursorExpired
                    429 -> PullResult.Throttled
                    400, 403 -> PullResult.Rejected
                    else -> PullResult.Unavailable
                }
            } finally { response.body.fill(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            PullResult.Unavailable
        } finally { body.fill(0) }
    }

    /** 204 is an idempotent operation acceptance, not proof any row matched. */
    fun ack(request: PushMailboxAckRequest): AckResult {
        if (!request.isValid()) return AckResult.REJECTED
        val body = request.toJsonBytes()
        return try {
            val response = transport.post(endpoint, "/v1/mailbox/ack", body)
            try {
                when (response.status) {
                    204 -> if (response.body.isEmpty()) AckResult.ACCEPTED else AckResult.UNAVAILABLE
                    429 -> AckResult.THROTTLED
                    400, 403 -> AckResult.REJECTED
                    else -> AckResult.UNAVAILABLE
                }
            } finally { response.body.fill(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            AckResult.UNAVAILABLE
        } finally { body.fill(0) }
    }
}

internal class PushMailboxHttpResponse(val status: Int, val body: ByteArray) {
    override fun toString(): String = "PushMailboxHttpResponse(redacted)"
}

/** Injectable for tests without Google or Cloudflare. */
internal fun interface PushMailboxPost {
    fun post(endpoint: PushEndpoint, path: String, body: ByteArray): PushMailboxHttpResponse
}

/**
 * System TLS, exact allowlisted host, no redirects and bounded response bytes.
 * Socket timeouts are per I/O, not an overall deadline against a slow peer.
 */
internal object HttpsPushMailboxPost : PushMailboxPost {
    override fun post(endpoint: PushEndpoint, path: String, body: ByteArray): PushMailboxHttpResponse =
        postWithConnection(endpoint, path, body) { it.openConnection() as HttpsURLConnection }

    /** Test seam for exercising the actual connection policy without external network. */
    internal fun postWithConnection(
        endpoint: PushEndpoint, path: String, body: ByteArray, open: (URL) -> HttpsURLConnection,
    ): PushMailboxHttpResponse {
        val connection = open(endpoint.url(path))
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200 && status != 201) {
                if (status >= 400) connection.errorStream?.close() else connection.inputStream?.close()
                return PushMailboxHttpResponse(status, byteArrayOf())
            }
            val contentType = connection.contentType?.split(';')?.firstOrNull()?.trim()?.lowercase(Locale.ROOT)
            val encoding = connection.getHeaderField("Content-Encoding")?.trim()?.lowercase(Locale.ROOT)
            if (contentType != "application/json" || encoding != null && encoding != "identity") throw IOException("invalid mailbox response")
            val declared = connection.getHeaderField("Content-Length")
            if (declared != null && (!declared.matches(Regex("^[0-9]+$")) ||
                    (declared.toLongOrNull() ?: Long.MAX_VALUE) > PushMailboxProtocol.MAX_RESPONSE_BYTES)
            ) throw IOException("oversized mailbox response")
            val bytes = connection.inputStream.use { it.readBytesAtMost(PushMailboxProtocol.MAX_RESPONSE_BYTES.toLong()) }
            if (declared != null && declared.toLong() != bytes.size.toLong()) {
                bytes.fill(0)
                throw IOException("invalid mailbox response length")
            }
            return PushMailboxHttpResponse(status, bytes)
        } finally { connection.disconnect() }
    }
}
