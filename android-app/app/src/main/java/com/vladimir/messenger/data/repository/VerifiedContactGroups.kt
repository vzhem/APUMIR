package com.vladimir.messenger.data.repository

/**
 * Split same-name candidates into independently verified device groups.
 * A name is only a search hint: every pair in a group must pass the supplied
 * identity check, including when a local ledger gives incomplete information.
 */
internal object VerifiedContactGroups {
    fun partition(ids: List<String>, sameDevice: (String, String) -> Boolean): List<List<String>> {
        val groups = mutableListOf<MutableList<String>>()
        for (id in ids.distinct()) {
            val group = groups.firstOrNull { existing -> existing.all { sameDevice(it, id) } }
            if (group == null) groups += mutableListOf(id) else group += id
        }
        return groups.filter { it.size > 1 }
    }
}
