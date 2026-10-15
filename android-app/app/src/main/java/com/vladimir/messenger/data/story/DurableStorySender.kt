package com.vladimir.messenger.data.story

import android.content.Context
import android.util.AtomicFile
import com.vladimir.messenger.data.diagnostics.readBoundedDiagnosticText
import com.vladimir.messenger.data.diagnostics.OperationalCounter
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Bounded phone-owned retry metadata. No HTTP, cleartext fallback or key creation. */
internal object DurableStorySender {
    private val lock = Any()
    private val pumpMutex = Mutex()
    private var file: AtomicFile? = null
    private var queue = StoryDeliveryQueue()
    private var status = "not_loaded"

    private fun load(context: Context) {
        if (file != null) return
        file = AtomicFile(File(context.noBackupFilesDir, "story-delivery-v1.json"))
        status = "ready"
        runCatching {
            val target = file!!
            val array = JSONArray(target.openRead().use { input ->
                require(target.baseFile.length() <= 262144)
                readBoundedDiagnosticText(input, 262144)
            })
            require(array.length() <= StoryDeliveryQueue.MAX_ENTRIES)
            queue = StoryDeliveryQueue((0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                StoryDeliveryEntry(row.getString("story"), row.getString("owner"), row.getString("peer"),
                    row.getLong("created"), row.optBoolean("revoke"), row.optInt("part"), row.optLong("due"))
            })
        }.onFailure {
            val target = file!!.baseFile
            if (target.exists() || File(target.path + ".bak").exists()) status = "read_failed"
        }
    }
    private fun save(): Boolean {
        val target = file ?: return false
        var stream: java.io.FileOutputStream? = null
        return try {
            val array = JSONArray()
            queue.snapshot().forEach { row -> array.put(JSONObject().put("story", row.storyId).put("owner", row.owner)
                .put("peer", row.peer).put("created", row.createdAtMs).put("revoke", row.revoke).put("part", row.nextPart).put("due", row.nextAttemptAtMs)) }
            val bytes = array.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= 262144)
            stream = target.startWrite(); stream.write(bytes); target.finishWrite(stream)
            status = "ready"; true
        } catch (_: Exception) { stream?.let { target.failWrite(it) }; status = "write_failed"; false }
    }
    fun enqueue(context: Context, story: StoryStore.Story, peers: List<String>, revoke: Boolean = false): Boolean = synchronized(lock) {
        load(context)
        if (status == "read_failed") return@synchronized false // Do not overwrite an unreadable queue silently.
        if (peers.isEmpty()) {
            OperationalDiagnostics.tracker.count(OperationalCounter.STORY_NO_AUDIENCE)
            TransferDiagnostics.recordWarning("story", "нет контактов для рассылки истории")
            return@synchronized false
        }
        queue.purge(System.currentTimeMillis())
        val accepted = queue.enqueue(story.id, story.authorId, peers, story.createdAtMs, revoke)
        val persisted = save()
        OperationalDiagnostics.tracker.count(if (accepted && persisted) OperationalCounter.STORY_ENQUEUED else OperationalCounter.STORY_QUEUE_FULL)
        accepted && persisted
    }

    suspend fun pump(context: Context, ownId: String, send: suspend (String, String, String) -> Boolean) = pumpMutex.withLock {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            load(context)
            if (status == "read_failed") return@withLock
            val expired = queue.purge(now)
            OperationalDiagnostics.tracker.count(OperationalCounter.STORY_EXPIRED, expired.toLong())
            if (expired > 0) save()
            queue.due(now, ownId)
        }
        // At most 16 packets per service pump, fair rotation across recipients.
        val packetCache = HashMap<String, List<String>>()
        for (entry in due) {
            if (!synchronized(lock) { queue.contains(entry) }) continue
            val packets = packetCache.getOrPut(entry.storyId + if (entry.revoke) ":revoke" else ":publish") {
                // Сборка пакетов может отказать (неканонический base64 снимка,
                // пакет больше 16 КиБ). Раньше такое исключение вылетало из
                // всего цикла досылки: одна испорченная запись останавливала
                // рассылку ОСТАЛЬНЫХ историй, и в журнале не было ни слова о
                // причине. Теперь отказ принадлежит одной записи.
                runCatching {
                    if (entry.revoke) listOf(StoryStore.PREFIX + JSONObject().put("id", entry.storyId)
                        .put("kind", StoryRevoke.KIND).put("ts", entry.createdAtMs))
                    else StoryStore.active(now).firstOrNull { it.mine && it.id == entry.storyId && it.authorId == ownId }
                        ?.let { StoryStore.encodePackets(it) }.orEmpty()
                }.getOrElse { error ->
                    TransferDiagnostics.recordWarning(
                        "story",
                        "пакеты истории не собрались (" + (error::class.simpleName ?: "ошибка") + "); запись досылки снята",
                    )
                    emptyList()
                }
            }
            if (packets.isEmpty() || entry.nextPart !in packets.indices) {
                synchronized(lock) { queue.remove(entry); save() }
                TransferDiagnostics.recordWarning("story", "исходная история недоступна для досылки; данные контактов не копируются")
                continue
            }
            val metrics = OperationalDiagnostics.tracker
            metrics.count(OperationalCounter.STORY_PACKET_ATTEMPTS)
            val accepted = try { send(entry.peer, packets[entry.nextPart], StoryOutbox.transportId(if (entry.revoke) "revoke:" + entry.storyId else entry.storyId, entry.nextPart)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            metrics.count(if (accepted) OperationalCounter.STORY_PACKET_ACCEPTED else OperationalCounter.STORY_PACKET_DEFERRED)
            synchronized(lock) {
                val current = queue.contains(entry)
                queue.result(entry, accepted, packets.size, System.currentTimeMillis())
                if (current && accepted && entry.nextPart + 1 == packets.size) metrics.count(OperationalCounter.STORY_TRANSPORT_ROUND_COMPLETE)
                save()
            }
        }
    }

    fun report(context: Context): String = synchronized(lock) {
        load(context)
        val rows = queue.snapshot()
        val oldest = rows.minOfOrNull { it.createdAtMs }?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
        "[доставка историй]\nstory_outbox=$status · pending_recipient_jobs=${rows.size} · max_jobs=256 · oldest_age_ms=${oldest ?: -1}\n" +
            "TTL=24ч; до 16 пакетов за цикл; retry=60с; очередь переживает рестарт отправителя. Транспортный успех не подтверждает показ/сохранение у друга.\n"
    }
}
