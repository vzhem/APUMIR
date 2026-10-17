package com.vladimir.messenger.data.story

import org.json.JSONObject

/** Small descriptor only. Video bytes travel through the encrypted resumable file protocol. */
object StoryVideoWire {
    const val FILE_PREFIX = "apu-story-video-"
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun fileName(id: String): String {
        require(uuid.matches(id))
        return "$FILE_PREFIX$id.mp4"
    }
    fun storyId(name: String): String? = name.removePrefix(FILE_PREFIX).removeSuffix(".mp4")
        .takeIf { name.startsWith(FILE_PREFIX) && name.endsWith(".mp4") && uuid.matches(it) }
    fun reserved(name: String): Boolean = name.startsWith(FILE_PREFIX)
    fun encode(media: StoryVideoMedia): JSONObject = JSONObject()
        .put("v", 1).put("sha256", media.sha256).put("bytes", media.sizeBytes)
        .put("ms", media.durationMs).put("width", media.width).put("height", media.height).put("audio", media.hasAudio)
    fun decode(json: JSONObject): StoryVideoMedia {
        require(json.getInt("v") == 1)
        return StoryVideoMedia(json.getString("sha256"), json.getLong("bytes"), json.getLong("ms"),
            json.getInt("width"), json.getInt("height"), json.getBoolean("audio"))
    }
    fun matches(story: StoryStore.Story, author: String, name: String, sha: String, bytes: Long, now: Long): Boolean =
        story.authorId == author && story.id == storyId(name) && now - story.createdAtMs in 0 until StoryStore.LIFETIME_MS &&
            story.video?.let { it.sha256 == sha && it.sizeBytes == bytes } == true
}
