package com.vladimir.messenger.ui.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.domain.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Группа или канал в списке «Пригласить в группу». */
data class InvitableGroup(
    val id: String,
    val title: String,
    val isChannel: Boolean,
    val memberCount: Int,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val groupDao: GroupDao,
) : ViewModel() {

    val contacts: StateFlow<List<Contact>> = contactRepository
        .observeContacts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Группы и каналы, куда можно позвать человека. Чтение базы уводим с
     * главного потока: холодный поток исполняется на потоке собирателя.
     */
    val invitableGroups: StateFlow<List<InvitableGroup>> = groupDao
        .observeInvitable()
        .map { list ->
            list.map {
                InvitableGroup(
                    id = it.id,
                    title = it.title,
                    isChannel = it.isChannel,
                    memberCount = it.memberCount,
                )
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Собрать ссылки-приглашения для выбранных групп и отдать их экрану.
     * Группы без пригласительной ссылки молча пропускаем.
     */
    fun buildGroupInvites(
        groupIds: Collection<String>,
        onReady: (List<Pair<String, String>>) -> Unit,
    ) {
        if (groupIds.isEmpty()) return
        viewModelScope.launch {
            val invites = withContext(Dispatchers.IO) {
                groupIds.mapNotNull { id ->
                    val group = runCatching { groupDao.getGroupById(id) }.getOrNull()
                        ?: return@mapNotNull null
                    val slug = group.inviteSlug
                    if (slug.isBlank()) return@mapNotNull null
                    group.title to GroupInviteLinks.build(
                        slug = slug,
                        groupId = group.id,
                        ownerId = group.ownerId,
                        isChannel = group.isChannel,
                        requestApproval = !group.isPublic,
                    )
                }
            }
            if (invites.isNotEmpty()) onReady(invites)
        }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            contactRepository.deleteContact(contactId)
        }
    }
}
