package com.vladimir.messenger.ui.screens.groups

import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Что показывать в разделе «Сообщества»: всё подряд, только группы или только
 * каналы. Переключатель на экране, а сам отбор — здесь: его проверяют
 * JVM-тесты `CommunityListTest`, поэтому правила фильтра живут в одном месте.
 */
enum class CommunityFilter { ALL, GROUPS, CHANNELS }

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

    /**
     * Отбор по виду сообщества для своих групп и каналов: «Все», «Группы»,
     * «Каналы». Поиск к этому моменту уже применён вызывающим.
     */
    fun applyFilter(
        communities: List<GroupSummary>,
        filter: CommunityFilter,
    ): List<GroupSummary> = when (filter) {
        CommunityFilter.ALL -> communities
        CommunityFilter.GROUPS -> communities.filterNot { it.isChannel }
        CommunityFilter.CHANNELS -> communities.filter { it.isChannel }
    }

    /** Тот же переключатель для записей сетевого каталога: вид у них свой. */
    fun applyFilterToDirectory(
        entries: List<DirectoryEntity>,
        filter: CommunityFilter,
    ): List<DirectoryEntity> = when (filter) {
        CommunityFilter.ALL -> entries
        CommunityFilter.GROUPS -> entries.filterNot { it.isChannel }
        CommunityFilter.CHANNELS -> entries.filter { it.isChannel }
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
