package com.vladimir.messenger.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.util.OwnInvite
import com.vladimir.messenger.data.repository.NetworkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

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
    val proxyTunnelEnabled: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proxyAutopilot: com.vladimir.messenger.service.ProxyAutopilot,
    private val fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    private var lastGossipTrigger: Long = 0L
    val uiState = _uiState.asStateFlow()

    init {
        loadSettings()
        _uiState.update { it.copy(proxyTunnelEnabled = proxyTunnelEnabled()) }
    }

    /** «Любая сеть»: пользовательский выключатель прокси-туннеля (по умолчанию включён). */
    private fun proxyTunnelEnabled(): Boolean =
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getBoolean("proxy_tunnel_enabled", true)

    /** «Очистка зависших»: остановить все незавершённые отправки и убрать их очереди. */
    fun onCancelStalledTransfers() {
        viewModelScope.launch {
            val cancelled = runCatching { fileTransferRouter.cancelStalledOutgoing() }.getOrDefault(-1)
            val message = if (cancelled >= 0) "Остановлено зависших отправок: $cancelled" else "Не удалось выполнить очистку"
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Освободить место: удалить локальные файлы завершённых передач. */
    fun onPurgeCompletedTransfers() {
        viewModelScope.launch {
            val purged = runCatching { fileTransferRouter.purgeCompletedTransfers() }.getOrDefault(-1)
            val message = if (purged >= 0) "Очищено завершённых передач: $purged" else "Не удалось выполнить очистку"
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun onProxyTunnelToggle(enabled: Boolean) {
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("proxy_tunnel_enabled", enabled).apply()
        _uiState.update { it.copy(proxyTunnelEnabled = enabled) }
        viewModelScope.launch {
            if (enabled) {
                runCatching { proxyAutopilot.cycle() }
            } else {
                RustBridge.clearMqttSocks5Proxy()
            }
        }
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

            // Получить актуальную версию приложения
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            // Публичный IP: каким нас видит интернет. Определяем внешним
            // сервисом; без сети честно пишем, что недоступен.
            val publicIp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL("https://api.ipify.org?text=true")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.inputStream.bufferedReader().use { it.readText().trim() }
                        .takeIf { it.isNotBlank() }
                }.getOrNull()
            }

            // Load display name from SharedPreferences
            val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString("display_name", null)
            val displayName = savedName?.takeIf { it.isNotBlank() } ?: "Anonymous"

            _uiState.update {
                it.copy(
                    displayName      = displayName,
                    fingerprint      = shortFingerprint,
                    // Та же ссылка, что и во всём приложении: она несёт
                    // подписанный токен, поэтому QR-код из настроек поднимает
                    // ранг так же, как ссылка из раздела рангов. p2p://invite/
                    // остаётся запасным путём, пока узел не создан.
                    inviteLink       = OwnInvite.link(context) ?: "p2p://invite/$pubKey",
                    connectionStatus = networkStatus,
                    connectedPeers   = RustBridge.connectedPeers().toInt(),
                    connectionMode   = "P2P / QUIC",
                    appVersion       = appVersion,
                    rustCoreVersion  = "Rust Core",
                    publicIp         = publicIp,
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

    fun onTriggerGossipDiscovery() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastGossipTrigger
        
        // ащита от быстрых нажатий (debounce 5 секунд)
        if (elapsed < 5000L) {
            val waitSec = ((5000L - elapsed) / 1000.0).toInt() + 1
            Toast.makeText(context, "одождите $waitSec сек перед следующим запросом", Toast.LENGTH_SHORT).show()
            return
        }
        
        lastGossipTrigger = now
        Toast.makeText(context, "Собираю данные об абонентах...", Toast.LENGTH_SHORT).show()
        
        viewModelScope.launch {
            val ok = RustBridge.triggerGossipDiscovery()
            android.util.Log.i("SettingsVM", "Gossip trigger result: $ok")
            if (ok) {
                Toast.makeText(context, "Gossip запущен", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "шибка запуска gossip", Toast.LENGTH_SHORT).show()
            }
            // ерезагрузить UI чтобы показать обновлённое количество пиров
            loadSettings()
        }
    }

    fun onExportKeys(password: String) {
        // Phase 3 - stub
    }
}
