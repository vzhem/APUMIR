package com.vladimir.messenger.ui.screens.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import com.vladimir.messenger.service.BotApi
import com.vladimir.messenger.util.ReferralInviteLink
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareProfileUiState(
    val displayName: String = "",
    val nodeId: String = "",
    val shareLink: String = "",
    val legacyLink: String = "",
    val alternativeLink: String = "",
    val installLink: String = "https://github.com/vzhem/APUMIR/releases/latest",
    val isSignedReferral: Boolean = false,
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
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            val nodeId = prefs.getString("node_id", "") ?: ""
            val displayName = prefs.getString("display_name", "Me") ?: "Me"
            val encodedNodeId = nodeId.urlEncode()
            val encodedName = displayName.urlEncode()
            val legacyLink = "p2pmessenger://add?node_id=$encodedNodeId&name=$encodedName"
            val alternativeLink = botApi.generateShareLink(nodeId)

            val signedLink = if (ReferralInviteLink.DEPLOYMENT_ENABLED &&
                nodeId.isNotBlank() &&
                IdentitySigningKeyStore.installIntoCore(context, nodeId) != null
            ) {
                IdentitySigningKeyStore.createSignedReferralToken(context)?.let { token ->
                    ReferralInviteLink.create(token)
                }
            } else {
                null
            }

            _uiState.update {
                it.copy(
                    displayName = displayName,
                    nodeId = nodeId,
                    shareLink = signedLink ?: legacyLink,
                    legacyLink = legacyLink,
                    alternativeLink = alternativeLink,
                    isSignedReferral = signedLink != null,
                    isLoading = false,
                )
            }
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
}
