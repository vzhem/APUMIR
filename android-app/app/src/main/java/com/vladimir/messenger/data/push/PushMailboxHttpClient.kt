package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Disabled mailbox transport seam; no production sender, Firebase, scheduler
 * or receive router invokes it yet. Use a fixed reviewed endpoint. A
 * stored/handled/accepted response is NOT proof of user delivery. An ACK must
 * only be created after authenticated opening and durable handling. Cancellation
 * cannot undo a request already accepted by the server; retries must be idempotent.
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

    suspend fun put(request: PushMailboxPutRequest): PutResult {
        currentCoroutineContext().ensureActive()
        if (!request.isValid()) return PutResult.Rejected
        val body = request.toJsonBytes()
        return try {
            currentCoroutineContext().ensureActive()
            val response = transport.post(endpoint, "/v1/mailbox/put", body)
            try {
                currentCoroutineContext().ensureActive()
                val outcome = when (response.status) {
                    200, 201 -> {
                        val parsed = PushMailboxResponseCodec.put(response.body)
                        when {
                            parsed == null || parsed.expiresAt != request.expiresAt -> PutResult.Unavailable
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
                currentCoroutineContext().ensureActive()
                outcome
            } finally { response.body.fill(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Only a generic counter; never include request or response data.
            diag(recordFailure)
            PutResult.Unavailable
        } finally { body.fill(0) }
    }

    suspend fun pull(request: PushMailboxPullRequest): PullResult {
        currentCoroutineContext().ensureActive()
        if (!request.isValid()) return PullResult.Rejected
        val body = request.toJsonBytes()
        return try {
            currentCoroutineContext().ensureActive()
            val response = transport.post(endpoint, "/v1/mailbox/pull", body)
            try {
                currentCoroutineContext().ensureActive()
                val outcome = when (response.status) {
                    200 -> {
                        val page = PushMailboxResponseCodec.page(response.body)
                        if (page == null || request.after != "-" && page.items.any { it.id == request.after }) {
                            PullResult.Unavailable
                        } else PullResult.Page(page.items, page.next)
                    }
                    409 -> PullResult.CursorExpired
                    429 -> PullResult.Throttled
                    400, 403 -> PullResult.Rejected
                    else -> PullResult.Unavailable
                }
                currentCoroutineContext().ensureActive()
                outcome
            } finally { response.body.fill(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag(recordFailure)
            PullResult.Unavailable
        } finally { body.fill(0) }
    }

    /** 204 is an idempotent operation acceptance, not proof any row matched. */
    suspend fun ack(request: PushMailboxAckRequest): AckResult {
        currentCoroutineContext().ensureActive()
        if (!request.isValid()) return AckResult.REJECTED
        val body = request.toJsonBytes()
        return try {
            currentCoroutineContext().ensureActive()
            val response = transport.post(endpoint, "/v1/mailbox/ack", body)
            try {
                currentCoroutineContext().ensureActive()
                val outcome = when (response.status) {
                    204 -> if (response.body.isEmpty()) AckResult.ACCEPTED else AckResult.UNAVAILABLE
                    429 -> AckResult.THROTTLED
                    400, 403 -> AckResult.REJECTED
                    else -> AckResult.UNAVAILABLE
                }
                currentCoroutineContext().ensureActive()
                outcome
            } finally { response.body.fill(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag(recordFailure)
            AckResult.UNAVAILABLE
        } finally { body.fill(0) }
    }
}

internal class PushMailboxHttpResponse(val status: Int, val body: ByteArray) {
    override fun toString(): String = "PushMailboxHttpResponse(redacted)"
}

/** Injectable for tests without Google or Cloudflare. */
internal fun interface PushMailboxPost {
    suspend fun post(endpoint: PushEndpoint, path: String, body: ByteArray): PushMailboxHttpResponse
}

/** Shares the bounded, cancellable HTTPS transport with registration and wake. */
internal object HttpsPushMailboxPost : PushMailboxPost {
    override suspend fun post(endpoint: PushEndpoint, path: String, body: ByteArray): PushMailboxHttpResponse =
        SharedPushHttpTransport.post(endpoint, path, body, json = true)
}
