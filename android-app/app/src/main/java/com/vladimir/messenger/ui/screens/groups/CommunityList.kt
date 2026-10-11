package com.vladimir.messenger.ui.screens.groups

import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Данные раздела «Сообщества», без изменения групповых выборок других экранов. */
internal object CommunityList {
    private const val SUGGESTION_LIMIT = 12

    /** Оба потока Room нужны: выборка групп намеренно не содержит каналов. */
    fun observe(
        groups: Flow<List<GroupSummary>>,
        channels: Flow<List<GroupSummary>>,
    ): Flow<List<GroupSummary>> = combine(groups, channels) { myGroups, myChannels ->
        myGroups + myChannels
    }

    fun filter(communities: List<GroupSummary>, query: String): List<GroupSummary> {
        val q = query.trim()
        if (q.isEmpty()) return communities
        return communities.filter { it.title.contains(q, ignoreCase = true) }
    }

    fun directoryMatches(
        directory: List<DirectoryEntity>,
        communities: List<GroupSummary>,
        query: String,
    ): List<DirectoryEntity> {
        val q = query.trim()
        // Проверяем ВСЕ свои сообщества, а не только совпавшие с поиском:
        // подписанный канал не должен снова предлагаться с «Подписаться».
        val joinedIds = communities.map { it.id }.toSet()
        // Закрытые записи скрыты из общего каталога; вход по ссылке не меняется.
        val available = directory.filter { it.groupId !in joinedIds && !it.needsApproval }
        return if (q.isEmpty()) {
            available.sortedByDescending { it.updatedAtMs }.take(SUGGESTION_LIMIT)
        } else {
            available.filter { it.title.contains(q, ignoreCase = true) }
        }
    }
}
