package com.vladimir.messenger.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.NetworkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val displayName: String = "Anonymous",
    val fingerprint: String = "Loading...",
    val inviteLink: String = "",
    val connectionStatus: NetworkStatus = NetworkStatus.Disconnected,
    val connectedPeers: Int = 0,
    val publicIp: String? = null,
    val connectionMode: String = "Unknown",
    val appVersion: String = "0.1.0",
    val rustCoreVersion: String = "Loading...",
)

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val nodeId = RustBridge.nodeId() ?: "unknown"
            val pubKey = RustBridge.publicKey() ?: "unknown"
            val shortFingerprint = if (pubKey.length > 8)
                pubKey.chunked(4).take(8).joinToString(" ")
            else pubKey

            val statusStr = RustBridge.networkStatus()
            val networkStatus = when (statusStr.lowercase()) {
                "connected"    -> NetworkStatus.Connected
                "connecting"   -> NetworkStatus.Connecting
                "degraded"     -> NetworkStatus.Degraded
                else           -> NetworkStatus.Disconnected
            }

            _uiState.update {
                it.copy(
                    displayName      = "Anonymous",
                    fingerprint      = shortFingerprint,
                    inviteLink       = "p2p://invite/$pubKey",
                    connectionStatus = networkStatus,
                    connectedPeers   = RustBridge.connectedPeers().toInt(),
                    connectionMode   = "P2P / QUIC",
                    rustCoreVersion  = "Rust Core",
                )
            }
        }
    }

    fun onRestartEngine() {
        viewModelScope.launch {
            RustBridge.onNetworkAvailable()
            loadSettings()
        }
    }

    fun onExportKeys(password: String) {
        // Phase 3 - stub
    }
}
