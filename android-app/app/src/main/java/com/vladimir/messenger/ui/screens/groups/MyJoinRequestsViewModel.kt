package com.vladimir.messenger.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.MyJoinRequestSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Экран «Мои заявки»: список своих заявок на вступление и действия над ними. */
@HiltViewModel
class MyJoinRequestsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
) : ViewModel() {

    val requests: StateFlow<List<MyJoinRequestSummary>> = groupRepository.observeMyJoinRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun resend(groupId: String) {
        viewModelScope.launch { groupRepository.resendMyJoinRequest(groupId) }
    }

    fun cancel(groupId: String) {
        viewModelScope.launch { groupRepository.cancelMyJoinRequest(groupId) }
    }
}
