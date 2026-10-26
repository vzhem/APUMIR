package com.vladimir.messenger.data.mirror

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small durable journal for the pairing hand-off.
 *
 * The live channel is intentionally disposable: Android may kill the service while a phone is
 * offline or while a pairing packet is in flight. The journal keeps only bounded control metadata
 * and sealed frame envelopes; message/file payloads remain in their normal stores. Replaying a
 * journal entry is safe because the receiver uses stable ids and offsets.
 */
internal object MirrorSyncStore {
    private const val PREFS = "apu_mirror_sync_v1"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SESSION = "session"
    private const val KEY_PEER_NODE = "peer_node"
    private const val KEY_PEER_DEVICE = "peer_device"
    private const val KEY_FRAMES = "frames"
    private const val MAX_FRAMES = 256
    private const val MAX_FRAME_CHARS = 70 * 1024

    data class Frame(
        val id: String,
        val json: String,
        val attempts: Int = 0,
        val wssAttempts: Int = 0,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    data class State(
        val active: Boolean = false,
        val sessionId: String = "",
        val peerNodeId: String = "",
        val peerDeviceId: ByteArray? = null,
        val frames: List<Frame> = emptyList(),
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): State {
        val p = prefs(context)
        val peerDevice = runCatching {
            p.getString(KEY_PEER_DEVICE, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val frames = runCatching {
            val array = JSONArray(p.getString(KEY_FRAMES, "[]"))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val json = item.optString("json")
                    if (id.isBlank() || json.isBlank() || json.length > MAX_FRAME_CHARS) continue
                    add(
                        Frame(
                            id = id,
                            json = json,
                            attempts = item.optInt("a", 0).coerceAtLeast(0),
                            wssAttempts = item.optInt("w", 0).coerceAtLeast(0),
                            createdAtMs = item.optLong("at", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        return State(
            active = p.getBoolean(KEY_ACTIVE, false),
            sessionId = p.getString(KEY_SESSION, "").orEmpty(),
            peerNodeId = p.getString(KEY_PEER_NODE, "").orEmpty(),
            peerDeviceId = peerDevice,
            frames = frames.takeLast(MAX_FRAMES),
        )
    }

    @Synchronized
    fun savePeer(context: Context, nodeId: String, deviceId: ByteArray) {
        if (nodeId.isBlank() || deviceId.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_PEER_NODE, nodeId)
            .putString(KEY_PEER_DEVICE, Base64.encodeToString(deviceId, Base64.NO_WRAP))
            .commit()
    }

    @Synchronized
    fun start(context: Context, sessionId: String, nodeId: String, deviceId: ByteArray) {
        if (sessionId.isBlank()) return
        val old = load(context)
        val editor = prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SESSION, sessionId)
        if (nodeId.isNotBlank() && deviceId.isNotEmpty()) {
            editor.putString(KEY_PEER_NODE, nodeId)
                .putString(KEY_PEER_DEVICE, Base64.encodeToString(deviceId, Base64.NO_WRAP))
        } else if (old.peerNodeId.isNotBlank()) {
            editor.putString(KEY_PEER_NODE, old.peerNodeId)
        }
        editor.commit()
    }

    @Synchronized
    fun putFrame(context: Context, frame: Frame) {
        if (frame.id.isBlank() || frame.json.isBlank() || frame.json.length > MAX_FRAME_CHARS) return
        val frames = load(context).frames.filterNot { it.id == frame.id }.toMutableList()
        frames += frame
        writeFrames(context, frames.takeLast(MAX_FRAMES))
    }

    @Synchronized
    fun removeFrame(context: Context, id: String) {
        if (id.isBlank()) return
        writeFrames(context, load(context).frames.filterNot { it.id == id })
    }

    @Synchronized
    fun clearFrames(context: Context) {
        prefs(context).edit().remove(KEY_FRAMES).commit()
    }

    @Synchronized
    fun finish(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_SESSION)
            .remove(KEY_FRAMES)
            .commit()
    }

    private fun writeFrames(context: Context, frames: List<Frame>) {
        val array = JSONArray()
        frames.takeLast(MAX_FRAMES).forEach { frame ->
            array.put(
                JSONObject()
                    .put("id", frame.id)
                    .put("json", frame.json)
                    .put("a", frame.attempts)
                    .put("w", frame.wssAttempts)
                    .put("at", frame.createdAtMs),
            )
        }
        prefs(context).edit().putString(KEY_FRAMES, array.toString()).commit()
    }
}
