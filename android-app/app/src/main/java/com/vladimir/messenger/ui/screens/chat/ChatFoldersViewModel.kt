package com.vladimir.messenger.ui.screens.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.folder.ChatFolder
import com.vladimir.messenger.data.folder.ChatFolderStore
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Одна строка выбора чата в редакторе папки. */
data class FolderChatOption(
    val key: String,
    val title: String,
    val isChannel: Boolean,
    val isGroup: Boolean,
)

/**
 * Экран «Папки с чатами»: сами папки берутся из [ChatFolderStore] (общий
 * поток с главным списком), а список чатов для редактора — из тех же
 * источников, что и главный экран.
 */
@HiltViewModel
class ChatFoldersViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val chatRepository: ChatRepository,
    private val groupDao: GroupDao,
) : ViewModel() {

    val folders: StateFlow<List<ChatFolder>> = ChatFolderStore.folders

    val options: StateFlow<List<FolderChatOption>> = combine(
        chatRepository.observeChats(),
        groupDao.observeGroups(),
        groupDao.observeChannels(),
    ) { chats, groups, channels ->
        val personal = chats.map { chat ->
            FolderChatOption(
                key = ChatFolder.personalKey(chat.id),
                title = chat.contactName,
                isChannel = false,
                isGroup = false,
            )
        }
        val groupOptions = (groups + channels)
            .filter { !it.isLeft && !it.archived }
            .map { group ->
                FolderChatOption(
                    key = ChatFolder.groupKey(group.id),
                    title = group.title,
                    isChannel = group.isChannel,
                    isGroup = !group.isChannel,
                )
            }
        personal + groupOptions
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        ChatFolderStore.load(appContext)
    }

    fun addRecommended(rule: ChatFolder.Rule, name: String) {
        if (folders.value.any { it.rule == rule }) return
        ChatFolderStore.add(appContext, ChatFolder(ChatFolderStore.newId(), name, rule))
    }

    fun create(name: String, keys: Set<String>) {
        ChatFolderStore.add(
            appContext,
            ChatFolder(ChatFolderStore.newId(), name, ChatFolder.Rule.CUSTOM, keys),
        )
    }

    fun update(folder: ChatFolder, name: String, keys: Set<String>) {
        ChatFolderStore.update(appContext, folder.copy(name = name, chatKeys = keys))
    }

    fun delete(id: String) = ChatFolderStore.delete(appContext, id)

    /** Перетаскивание ручки: соседние папки меняются местами по одной. */
    fun move(fromIndex: Int, toIndex: Int) {
        val current = folders.value
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val mutable = current.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        ChatFolderStore.save(appContext, mutable)
    }
}
