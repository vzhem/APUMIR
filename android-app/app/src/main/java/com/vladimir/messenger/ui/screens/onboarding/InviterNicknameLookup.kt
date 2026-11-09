package com.vladimir.messenger.ui.screens.onboarding

import com.vladimir.messenger.data.local.entity.NicknameEntity

/** Select only an unambiguous, other owner from the locally known directory. */
internal object InviterNicknameLookup {
    fun candidates(entries: List<NicknameEntity>, ownId: String?, isSameDevice: (String) -> Boolean): List<NicknameEntity> =
        entries.filter { entry ->
            entry.ownerId.isNotBlank() &&
                !entry.ownerId.equals(ownId, ignoreCase = true) &&
                !isSameDevice(entry.ownerId)
        }.distinctBy { it.ownerId.lowercase() }
}
