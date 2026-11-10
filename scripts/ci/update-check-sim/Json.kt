package org.json

// A tiny fixture decoder, not a production JSON parser. The HTTP harness
// controls every input and exercises UpdateChecker's decisions, not JSON syntax.
class JSONObject private constructor(private val fields: Map<String, Any>) {
    constructor(fixture: String) : this(decode(fixture))

    fun optString(key: String, fallback: String): String = fields[key] as? String ?: fallback
    fun optBoolean(key: String, fallback: Boolean): Boolean = fields[key] as? Boolean ?: fallback
    fun optJSONArray(key: String): JSONArray? = fields[key] as? JSONArray

    companion object {
        private fun decode(fixture: String): Map<String, Any> {
            val values = fixture.split('|')
            val version = values.getOrElse(1) { "" }
            val sha = values.getOrElse(2) { "" }
            return when (values.first()) {
                "github" -> mapOf(
                    "tag_name" to version,
                    "body" to "New release",
                    "published_at" to "2026-11-09T00:00:00Z",
                    "assets" to JSONArray(listOf(JSONObject(mapOf(
                        "name" to "app-release.apk",
                        "browser_download_url" to "https://github.com/test/app-release.apk",
                        "digest" to if (sha.isNotEmpty()) "sha256:$sha" else "",
                    )))),
                )
                "relay" -> buildMap {
                    put("tag_name", version)
                    put("notes", "New release")
                    put("published_at", "2026-11-09T00:00:00Z")
                    put("apk_sha256", sha)
                    put("apk_url", "https://p2p-relay.apuchat.workers.dev/update/apk")
                    when (values.getOrElse(3) { "absent" }) {
                        "true" -> put("metadata_stale", true)
                        "false" -> put("metadata_stale", false)
                    }
                }
                "site" -> mapOf("version" to version, "sha256" to sha)
                else -> error("unknown fixture: $fixture")
            }
        }
    }
}
class JSONArray(private val items: List<JSONObject>) {
    fun length(): Int = items.size
    fun optJSONObject(index: Int): JSONObject? = items.getOrNull(index)
}
