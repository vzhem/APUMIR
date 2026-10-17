package com.vladimir.messenger.data.story

import android.content.Context
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Recipient-bound encrypted transfers. Audience is snapshotted, never expanded to later contacts. */
@Singleton
class StoryVideoDelivery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chats: ChatDao,
    private val transfers: FileTransferDao,
    private val preparation: OutgoingFilePreparationService,
    private val peers: com.vladimir.messenger.data.file.FileExchangePeerStore,
) {
    private val mutex = Mutex()
    private val prefs get() = context.getSharedPreferences("story-video-audience-v1", Context.MODE_PRIVATE)
    fun register(story: StoryStore.Story, peers: List<String>): Boolean {
        if (story.video == null) return true
        require(story.mine)
        val audience = peers.filter { it.isNotBlank() && it != story.authorId }.toSet()
        if (audience.isEmpty() || audience.size > 256) return false
        val key = "${story.authorId}/${story.id}"
        val existing = prefs.getStringSet(key, null)
        return if (existing != null) existing == audience else prefs.edit().putStringSet(key, audience).commit()
    }
    private fun audience(story: StoryStore.Story): Set<String> =
        prefs.getStringSet("${story.authorId}/${story.id}", emptySet()).orEmpty().toSet()

    private val acknowledgements = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Readiness is an explicit v1 capability signal; older clients receive only the cover. */
    fun receiveReady(peer: String, packet: String): Boolean {
        val json = runCatching { org.json.JSONObject(packet.removePrefix(StoryStore.PREFIX)) }.getOrNull() ?: return false
        if (json.optString("kind") != "video-ready") return false
        val story = StoryStore.active().firstOrNull { it.mine && it.id == json.optString("id") &&
            it.video?.sha256 == json.optString("sha256") && it.createdAtMs == json.optLong("ts") } ?: return true
        if (peer in audience(story)) context.getSharedPreferences("story-video-ready-v1", Context.MODE_PRIVATE)
            .edit().putBoolean("${story.authorId}/${story.id}/$peer", true).apply()
        return true
    }
    fun readyPacket(peer: String, packet: String): String? {
        val json = runCatching { org.json.JSONObject(packet.removePrefix(StoryStore.PREFIX)) }.getOrNull() ?: return null
        val story = StoryStore.active().firstOrNull { !it.mine && it.authorId == peer && it.id == json.optString("id") && it.video != null } ?: return null
        val key = "$peer/${story.id}"
        val now = System.currentTimeMillis()
        val last = acknowledgements.putIfAbsent(key, now)
        if (last != null) {
            if (now - last < 30_000) return null
            acknowledgements[key] = now
        }
        if (acknowledgements.size > 200) acknowledgements.entries.removeAll { now - it.value > StoryStore.LIFETIME_MS }
        return StoryStore.PREFIX + org.json.JSONObject().put("kind", "video-ready").put("id", story.id)
            .put("sha256", story.video!!.sha256).put("ts", story.createdAtMs).toString()
    }

    /** Called by the regular file pump, also after restart. At most one new encryption per tick. */
    suspend fun pump(drop: suspend (FileTransferEntity) -> Unit,
        completed: suspend (FileTransferEntity) -> Unit, requestKey: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            StoryStore.attach(context)
            StoryVideoFiles.sweepAbandonedDrafts(context)
            val active = StoryStore.active()
            StoryVideoStorage.sweep(context, active)
            val mine = active.filter { it.mine && it.video != null }
            val rows = transfers.getStoryVideoTransfers().toMutableList()
            val now = System.currentTimeMillis()
            for (row in rows.toList()) {
                val story = active.firstOrNull { it.id == StoryVideoWire.storyId(row.displayName) &&
                    (if (row.direction == "OUTGOING") it.mine && row.peerNodeId in audience(it) else it.authorId == row.peerNodeId) }
                if (story == null || row.expiresAtMs <= now || story.video?.sha256 != row.fileSha256 ||
                    (row.direction == "OUTGOING" && row.state in setOf("PREPARING", "FAILED") && now - row.updatedAtMs > 60_000)) {
                    drop(row)
                    rows.remove(row)
                } else if (row.state == "COMPLETE") completed(row)
            }
            val readyPrefs = context.getSharedPreferences("story-video-ready-v1", Context.MODE_PRIVATE)
            val validReady = mine.flatMap { story -> audience(story).map { "${story.authorId}/${story.id}/$it" } }.toSet()
            readyPrefs.all.keys.filter { it !in validReady }.forEach { readyPrefs.edit().remove(it).apply() }
            val keep = mine.map { "${it.authorId}/${it.id}" }.toSet()
            prefs.all.keys.filter { it !in keep }.forEach { prefs.edit().remove(it).apply() }
            // Bound simultaneous per-recipient ciphertext copies; completed entries deduplicate future pumps.
            if (rows.count { it.direction == "OUTGOING" && it.state !in setOf("COMPLETE", "FAILED", "CANCELLED") && it.expiresAtMs > now } >= 4) return@withLock
            for (story in mine) {
                val source = StoryVideoStorage.file(context, story) ?: continue
                for (peer in audience(story)) {
                    if (!readyPrefs.getBoolean("${story.authorId}/${story.id}/$peer", false)) continue
                    val existing = rows.firstOrNull { it.direction == "OUTGOING" && it.peerNodeId == peer && it.displayName == StoryVideoWire.fileName(story.id) }
                    if (existing != null) continue
                    val chat = chats.getChatsByContactId(peer).firstOrNull() ?: continue
                    if (peers.bindingFor(peer) == null) { requestKey(peer); continue }
                    try {
                        preparation.prepareFromFile(source, StoryVideoWire.fileName(story.id), "video/mp4",
                            "story-video:${story.id}:$peer", chat.id, peer, now,
                            expiresAtMs = story.createdAtMs + StoryStore.LIFETIME_MS)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        com.vladimir.messenger.data.diagnostics.TransferDiagnostics.recordWarning("story", "подготовка передачи видео отложена: ${failure.javaClass.simpleName}")
                    }
                    return@withLock
                }
            }
        }
    }
}
