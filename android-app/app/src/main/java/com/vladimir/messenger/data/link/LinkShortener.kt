package com.vladimir.messenger.data.link

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.service.BotApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * A short code only works in a browser if the worker has already registered
 * it. On the phone, first use our verified local mapping, then ask a directly
 * connected authenticated peer. The worker is the *last* option, never an
 * automatic probe merely because an old contact appears in the address book.
 *
 * APUSHORT1 is an on-demand control exchange over the existing sealed QUIC
 * payload. Peers answer only codes they have actually obtained from the worker
 * or another verified peer; they do not share invitation targets unsolicited.
 */
@Singleton
class LinkShortener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botApi: BotApi,
) {
    private val lock = Any()
    private val targetsByCode = java.util.LinkedHashMap<String, String>(MAX_CACHED, 0.75f, true)
    private val codesByTarget = HashMap<String, String>()
    private data class Pending(val code: String, val peers: Set<String>, val result: CompletableDeferred<String>)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val lastAnswerAt = ConcurrentHashMap<String, Long>()

    @Volatile private var downUntilMs = 0L

    init {
        runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CACHE, "[]") ?: "[]"
            val saved = JSONArray(raw)
            synchronized(lock) {
                for (i in 0 until saved.length()) {
                    val entry = saved.optJSONObject(i) ?: continue
                    val code = entry.optString("code")
                    val target = entry.optString("target")
                    if (ShortLinks.matchesWorkerCode(code, target)) {
                        targetsByCode[code] = target
                        codesByTarget[target] = code
                    }
                }
                trimCache()
            }
        }.onFailure { Log.w(TAG, "short link cache ignored: ${it.message}") }
    }

    private fun trimCache() {
        while (targetsByCode.size > MAX_CACHED) {
            val oldest = targetsByCode.entries.iterator()
            if (!oldest.hasNext()) break
            val removed = oldest.next()
            codesByTarget.remove(removed.value)
            oldest.remove()
        }
    }

    private fun remember(code: String, target: String) {
        if (!ShortLinks.matchesWorkerCode(code, target)) return
        synchronized(lock) {
            targetsByCode[code] = target
            codesByTarget[target] = code
            trimCache()
            val json = JSONArray()
            for ((c, t) in targetsByCode) json.put(JSONObject().put("code", c).put("target", t))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CACHE, json.toString()).apply()
        }
    }

    private fun local(code: String): String? = synchronized(lock) { targetsByCode[code] }

    /** Register a new browser-readable code only if the worker is the fallback. */
    suspend fun codeFor(target: String): String? = withContext(Dispatchers.IO) {
        synchronized(lock) { codesByTarget[target] }?.let { return@withContext it }
        if (!RustBridge.externalTransportAllowed() || System.currentTimeMillis() < downUntilMs) {
            return@withContext null
        }
        val code = runCatching { botApi.shortenLink(target) }.getOrNull()
        if (code == null || !ShortLinks.matchesWorkerCode(code, target)) {
            downUntilMs = System.currentTimeMillis() + RETRY_MS
            Log.w(TAG, "short link service unavailable, using long link")
            return@withContext null
        }
        remember(code, target)
        code
    }

    suspend fun shorten(target: String): String? = codeFor(target)?.let { ShortLinks.build(it) }

    /** Resolve by direct request before even considering the worker endpoint. */
    suspend fun expand(code: String): String? = withContext(Dispatchers.IO) {
        if (!ShortLinks.isValidCode(code)) return@withContext null
        local(code)?.let { return@withContext it }
        val live = RustBridge.liveSwarmPeerIds().take(MAX_ASK_PEERS).toSet()
        if (live.isNotEmpty() && pending.size < MAX_PENDING) {
            val nonce = UUID.randomUUID().toString().replace("-", "")
            val request = Pending(code, live, CompletableDeferred())
            pending[nonce] = request
            try {
                val wire = "$PREFIX|ask|$nonce|$code"
                for (peer in live) RustBridge.sendDirectPayload(peer, wire)
                val answer = withTimeoutOrNull(PEER_WAIT_MS) { request.result.await() }
                if (answer != null) return@withContext answer
            } finally {
                pending.remove(nonce)
            }
        }
        // A live route remains a strict barrier even if nobody knows this
        // code. If the route disappeared while waiting, the core authorizes
        // only the *needed* /short/<code> lookup, not a general poll.
        if (!RustBridge.externalTransportAllowed()) return@withContext null
        val target = runCatching { botApi.expandShortLink(code) }.getOrNull() ?: return@withContext null
        if (!ShortLinks.matchesWorkerCode(code, target)) return@withContext null
        remember(code, target)
        target
    }

    suspend fun expandIfShort(raw: String): String? {
        val code = ShortLinks.codeOf(raw) ?: return raw
        return expand(code)
    }

    /** Consume only our own sealed control frames, on both message ingress paths. */
    suspend fun routeIncoming(senderId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (!text.startsWith("$PREFIX|")) return@withContext false
        val parts = text.split('|', limit = 5)
        if (senderId !in RustBridge.liveSwarmPeerIds()) return@withContext true
        when (parts.getOrNull(1)) {
            "ask" -> {
                if (parts.size != 4 || !NONCE.matches(parts[2])) return@withContext true
                val code = parts[3]
                if (!ShortLinks.isValidCode(code)) return@withContext true
                val target = local(code) ?: return@withContext true
                val throttleKey = "$senderId|$code"
                val now = System.currentTimeMillis()
                if (now - (lastAnswerAt[throttleKey] ?: 0L) < ANSWER_INTERVAL_MS) return@withContext true
                if (lastAnswerAt.size > MAX_CACHED) lastAnswerAt.clear()
                lastAnswerAt[throttleKey] = now
                val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(target.toByteArray(Charsets.UTF_8))
                RustBridge.sendDirectPayload(senderId, "$PREFIX|give|${parts[2]}|$code|$encoded")
            }
            "give" -> {
                if (parts.size != 5 || !NONCE.matches(parts[2])) return@withContext true
                val requested = pending[parts[2]] ?: return@withContext true
                if (senderId !in requested.peers || parts[3] != requested.code ||
                    parts[4].length > MAX_WIRE_TARGET_CHARS) return@withContext true
                val target = runCatching {
                    String(java.util.Base64.getUrlDecoder().decode(parts[4]), Charsets.UTF_8)
                }.getOrNull() ?: return@withContext true
                if (ShortLinks.matchesWorkerCode(requested.code, target)) {
                    remember(requested.code, target)
                    requested.result.complete(target)
                }
            }
        }
        true
    }

    private companion object {
        const val TAG = "LinkShortener"
        const val PREFIX = "APUSHORT1"
        const val PREFS = "p2p_short_links"
        const val KEY_CACHE = "verified_mappings"
        const val MAX_CACHED = 128
        const val MAX_PENDING = 16
        const val MAX_ASK_PEERS = 4
        const val MAX_WIRE_TARGET_CHARS = 1_400
        const val PEER_WAIT_MS = 2_500L
        const val ANSWER_INTERVAL_MS = 10_000L
        const val RETRY_MS = 2L * 60 * 1000
        val NONCE = Regex("[0-9a-f]{32}")
    }
}
