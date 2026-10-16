package com.vladimir.messenger.data.group

import java.util.Base64

/** Bounded image/preset values. No remote URL or sender-local URI is accepted on the wire. */
object GroupWallpaper {
    val presets = setOf("classic", "mint", "lavender", "peach", "sky", "rose", "sand", "olive", "graphite", "ocean")
    const val MAX_IMAGE_BYTES = 48 * 1024
    const val MAX_VALUE = 65542
    const val CHUNK = 2000
    const val MAX_PARTS = 33
    fun valid(value: String): Boolean = when {
        value.isEmpty() -> true
        value.startsWith("preset:") -> value.removePrefix("preset:") in presets
        value.startsWith("image:") && value.length <= MAX_VALUE -> runCatching {
            val raw = value.removePrefix("image:")
            val bytes = Base64.getDecoder().decode(raw)
            bytes.size in 4..MAX_IMAGE_BYTES && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                Base64.getEncoder().encodeToString(bytes) == raw
        }.getOrDefault(false)
        else -> false
    }
    fun validRevision(revision: String, now: Long): Boolean =
        Regex("[0-9]{13}-[0-9a-f]{32}").matches(revision) &&
            (revision.substringBefore('-').toLongOrNull() ?: Long.MAX_VALUE) <= now + 300_000
    fun canChange(role: String, permissions: Long, memberMask: Long, banned: Boolean): Boolean =
        !banned && GroupRole.isAdminOrOwner(role) && GroupPermissions.canChangeInfo(role, permissions, memberMask)
    fun resolve(personal: String?, shared: String?): String? = personal ?: shared?.takeIf { it.isNotEmpty() }
}

/** Authorized fragments only; one bounded assembly per group, expires after five minutes. */
class GroupWallpaperParts {
    private data class Pending(val sender: String, val revision: String, val total: Int, val at: Long,
        val parts: MutableMap<Int, String> = HashMap())
    private val pending = LinkedHashMap<String, Pending>()
    @Synchronized fun receive(group: String, sender: String, revision: String, index: Int, total: Int,
        data: String, now: Long): String? {
        pending.entries.removeAll { now - it.value.at > 300_000 }
        if (!GroupWallpaper.validRevision(revision, now) || total !in 1..GroupWallpaper.MAX_PARTS ||
            index !in 0 until total || data.length > GroupWallpaper.CHUNK || data.any { it.code !in 32..126 || it == '|' }) return null
        var p = pending[group]
        if (p != null && p.revision > revision) return null
        if (p == null || p.revision != revision) {
            if (pending.size >= 32) pending.remove(pending.keys.first())
            p = Pending(sender, revision, total, now)
            pending[group] = p
        }
        if (p.total != total) return null
        val previous = p.parts[index]
        if (previous != null && previous != data) { pending.remove(group); return null }
        p.parts[index] = data
        if (p.parts.size != total) return null
        pending.remove(group)
        val value = (0 until total).joinToString("") { p.parts.getValue(it) }
        return value.takeIf { GroupWallpaper.valid(it) }
    }
}
