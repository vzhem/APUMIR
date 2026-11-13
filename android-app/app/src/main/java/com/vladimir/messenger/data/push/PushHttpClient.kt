package com.vladimir.messenger.data.push

import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Experimental client for the disabled wake Worker; not connected to Android,
 * not an FCM client, and never an acknowledgment of message delivery. The
 * endpoint allowlist must come from a reviewed, immutable application
 * configuration, not from received messages or URLs. A cancelled operation may
 * already have reached the server; retries must be idempotent.
 */
internal class PushHttpClient(
    private val endpoint: PushEndpoint,
    private val transport: PushPost = HttpsPushPost,
    private val recordFailure: () -> Unit,
) {
    enum class RegistrationResult { STORED, STALE, THROTTLED, REJECTED, UNAVAILABLE }
    enum class WakeResult { ACCEPTED, THROTTLED, REJECTED, UNAVAILABLE }

    /** [request] holds an FCM token and capability; never log, back up, or print it. */
    suspend fun register(request: PushRegistrationRequest): RegistrationResult {
        currentCoroutineContext().ensureActive()
        if (!request.isValid()) return RegistrationResult.REJECTED
        val body = request.toJsonBytes()
        return try {
            currentCoroutineContext().ensureActive()
            val status = transport.post(endpoint, "/v1/registration", body)
            currentCoroutineContext().ensureActive()
            when (status) {
                204 -> RegistrationResult.STORED
                409 -> RegistrationResult.STALE
                429 -> RegistrationResult.THROTTLED
                400, 403 -> RegistrationResult.REJECTED
                else -> RegistrationResult.UNAVAILABLE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Report only a generic counter/log; exception text may contain secrets.
            diag(recordFailure)
            RegistrationResult.UNAVAILABLE
        } finally { body.fill(0) }
    }

    /** ACCEPTED means only that the Worker accepted the request, not delivery. */
    suspend fun wake(request: PushWakeRequest): WakeResult {
        currentCoroutineContext().ensureActive()
        if (!request.isValid()) return WakeResult.REJECTED
        val body = request.toJsonBytes()
        return try {
            currentCoroutineContext().ensureActive()
            val status = transport.post(endpoint, "/v1/wake", body)
            currentCoroutineContext().ensureActive()
            when (status) {
                202 -> WakeResult.ACCEPTED
                429 -> WakeResult.THROTTLED
                400, 403 -> WakeResult.REJECTED
                else -> WakeResult.UNAVAILABLE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Report only a generic counter/log, never capabilities or tokens.
            diag(recordFailure)
            WakeResult.UNAVAILABLE
        } finally { body.fill(0) }
    }
}

/** A broken diagnostic counter must not mask cancellation or disclose HTTP data. */
internal suspend fun diag(recordFailure: () -> Unit) {
    currentCoroutineContext().ensureActive()
    try {
        recordFailure()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        Logger.getLogger("PushHttpClient").warning("Push diagnostics unavailable")
    }
    currentCoroutineContext().ensureActive()
}

/** An exact hostname allowlist plus strict HTTPS; redirects are never followed. */
internal class PushEndpoint private constructor(private val authority: String) {
    internal fun url(path: String): java.net.URL {
        require(path in setOf("/v1/registration", "/v1/wake", "/v1/mailbox/put", "/v1/mailbox/pull", "/v1/mailbox/ack"))
        return URI("https://$authority$path").toURL()
    }

    override fun toString(): String = "PushEndpoint(redacted)"

    companion object {
        private val HOST = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
        private val TLD = Regex("^[a-z][a-z0-9-]{0,62}$")

        fun approved(raw: String, allowedHosts: Set<String>): PushEndpoint? {
            return try {
                if (raw.length !in 1..512) return null
                val uri = URI(raw)
                val host = uri.host ?: return null
                if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                    uri.rawPath !in listOf("", "/") || uri.port !in listOf(-1, 443) ||
                    host.length > 253 || !HOST.matches(host) || !TLD.matches(host.substringAfterLast('.')) ||
                    host !in allowedHosts || uri.rawAuthority != host && uri.rawAuthority != "$host:443"
                ) return null
                PushEndpoint(host)
            } catch (_: URISyntaxException) { null } catch (_: IllegalArgumentException) { null }
        }
    }
}

/** Injectable to test requests without contacting Google or Cloudflare. */
internal fun interface PushPost {
    suspend fun post(endpoint: PushEndpoint, path: String, body: ByteArray): Int
}

/** No response body or provider details are needed for registration or wake. */
internal object HttpsPushPost : PushPost {
    override suspend fun post(endpoint: PushEndpoint, path: String, body: ByteArray): Int =
        SharedPushHttpTransport.post(endpoint, path, body, json = false).status
}

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val HEX32 = Regex("^[0-9a-f]{64}$")
private val HEX16 = Regex("^[0-9a-f]{32}$")
private val HEX64 = Regex("^[0-9a-f]{128}$")
private val TOKEN = Regex("^[A-Za-z0-9_:.-]{20,2048}$")

/** Strings are intentionally not a data class: autogenerated toString leaks tokens. */
internal class PushRegistrationRequest(
    private val action: String,
    private val owner: String,
    private val capability: String,
    private val device: String,
    private val sequence: Long,
    private val issuedAt: Long,
    private val token: String,
    private val signature: String,
) {
    internal fun isValid(): Boolean = action in setOf("register", "revoke") && validRoute(owner, capability) &&
        HEX16.matches(device) && device.any { it != '0' } && sequence in 1..MAX_SAFE_INTEGER &&
        issuedAt in 1..MAX_SAFE_INTEGER && (if (action == "register") TOKEN.matches(token) else token.isEmpty()) &&
        HEX64.matches(signature)

    // Every field is strictly ASCII/number validated before this is called;
    // none can contain JSON quoting or control characters.
    internal fun toJsonBytes(): ByteArray = (
        "{\"action\":\"$action\",\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\"," +
            "\"sequence\":$sequence,\"issuedAt\":$issuedAt,\"token\":\"$token\",\"signature\":\"$signature\"}"
        ).toByteArray(Charsets.US_ASCII)

    override fun toString(): String = "PushRegistrationRequest(redacted)"
}

internal class PushWakeRequest(private val owner: String, private val capability: String) {
    internal fun isValid(): Boolean = validRoute(owner, capability)
    internal fun toJsonBytes(): ByteArray = "{\"owner\":\"$owner\",\"capability\":\"$capability\"}".toByteArray(Charsets.US_ASCII)
    override fun toString(): String = "PushWakeRequest(redacted)"
}

private fun validRoute(owner: String, capability: String): Boolean =
    HEX32.matches(owner) && owner.any { it != '0' } && HEX32.matches(capability) && capability.any { it != '0' }
