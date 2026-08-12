package com.vladimir.messenger.ui.screens.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.service.BotApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

data class ShareProfileUiState(
    val displayName: String = "",
    val nodeId: String = "",
    val shareLink: String = "",
    val alternativeLink: String = "",
    val installLink: String = "https://github.com/vzhem/APUMIR/releases/latest",
    val isLoading: Boolean = true,
)

@HiltViewModel
class ShareProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botApi: BotApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareProfileUiState())
    val uiState: StateFlow<ShareProfileUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val nodeId = prefs.getString("node_id", "") ?: ""
        val displayName = prefs.getString("display_name", "Me") ?: "Me"
        val encodedNodeId = nodeId.urlEncode()
        val encodedName = displayName.urlEncode()
        val shareLink = "p2pmessenger://add?node_id=$encodedNodeId&name=$encodedName"
        val alternativeLink = botApi.generateShareLink(nodeId)

        _uiState.update {
            it.copy(
                displayName = displayName,
                nodeId = nodeId,
                shareLink = shareLink,
                alternativeLink = alternativeLink,
                isLoading = false
            )
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
}
