package com.vladimir.messenger.data.heartgame

import android.content.Context
import android.os.SystemClock
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.rank.RankWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object HeartGameStore {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    val policy = HeartGamePolicy()
    private val lock = Mutex()
    fun ownId(): String? = RankWire.canonicalNodeId(RustBridge.nodeId())
    suspend fun ownKeyId(context: Context): String? = withContext(Dispatchers.IO) {
        com.vladimir.messenger.data.security.IdentitySigningKeyStore.existingVerifiedBinding(context)?.let(HeartGameWire::keyId)
    }
    private fun prefs(context: Context, id: String) = context.applicationContext.getSharedPreferences("heart-game-v1-$id", Context.MODE_PRIVATE)
    fun enabled(context: Context, id: String) = prefs(context, id).getBoolean("enabled", false)
    fun score(context: Context, id: String) = prefs(context, id).getLong("score", 0L).coerceIn(0, HeartGamePolicy.MAX_SCORE)
    suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            policy.cancel()
            prefs(context, id).edit().putBoolean("enabled", enabled).commit().also { if (it) changes.value++ }
        }
    }
    suspend fun catchHeart(context: Context, id: String, ticket: Long): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (ownId() != id || !enabled(context, id) || !policy.claim(ticket, SystemClock.elapsedRealtime())) return@withLock false
            val current = score(context, id)
            if (current >= HeartGamePolicy.MAX_SCORE) return@withLock false
            prefs(context, id).edit().putLong("score", current + 1).commit().also { if (it) changes.value++ }
        }
    }
    // Bounded to 500 signed rows, names are part of the signed self-report.
    private val boards = mutableMapOf<String, List<HeartGameWire.Entry>>()
    suspend fun board(context: Context, id: String): List<HeartGameWire.Entry> = withContext(Dispatchers.IO) {
        lock.withLock { load(context, id).filter { it.updated >= System.currentTimeMillis() - HeartGameWire.RETENTION_MS } }
    }
    private fun load(context: Context, id: String): List<HeartGameWire.Entry> = boards.getOrPut(id) {
        if (boards.size >= 2) boards.clear()
        val raw = prefs(context, id).getString("board", "").orEmpty()
        if (raw.length > 600_000) emptyList() else raw.lineSequence().take(500).mapNotNull {
            HeartGameWire.verify(it, System.currentTimeMillis(), { bytes -> uniffi.p2p_core.verifyIdentitySigningBinding(bytes) })
        }.toList()
    }
    suspend fun merge(context: Context, id: String, incoming: List<HeartGameWire.Entry>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val map = load(context, id).filter { it.updated >= now - HeartGameWire.RETENTION_MS }.associateBy { it.keyId }.toMutableMap()
            val ownKey = ownKeyId(context)
            incoming.filter { it.keyId != ownKey }.forEach { if (HeartGameWire.newer(map[it.keyId], it)) map[it.keyId] = it }
            val next = HeartGameWire.ranked(map.values).take(500)
            if (next != boards[id] && prefs(context, id).edit().putString("board", next.joinToString("\n") { it.record }).commit()) {
                boards[id] = next
                changes.value++
            }
        }
    }
}
