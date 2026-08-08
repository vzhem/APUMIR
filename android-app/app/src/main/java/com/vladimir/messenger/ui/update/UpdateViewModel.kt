package com.vladimir.messenger.ui.update

import android.app.Application
import android.os.Build
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.service.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val isChecking: Boolean = false,
    val updateAvailable: UpdateChecker.ReleaseInfo? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    application: Application,
    private val updateChecker: UpdateChecker,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    init {
        registerDownloadReceiver()
    }

    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isChecking = true, error = null) }
                
                val release = updateChecker.checkForUpdate(currentVersion)
                
                _uiState.update { 
                    it.copy(
                        isChecking = false,
                        updateAvailable = release
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("UpdateViewModel", "checkForUpdate failed", e)
                _uiState.update { it.copy(isChecking = false, error = e.message) }
            }
        }
    }

    fun downloadUpdate() {
        val release = _uiState.value.updateAvailable ?: return
        
        _uiState.update { it.copy(isDownloading = true) }
        
        downloadId = updateChecker.downloadApk(release)
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(updateAvailable = null) }
    }

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    android.util.Log.i("UpdateViewModel", "Download complete, opening installer")
                    _uiState.update { it.copy(isDownloading = false) }
                    updateChecker.installApk(downloadId)
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 14+: требуется флаг RECEIVER_EXPORTED/NOT_EXPORTED
            getApplication<Application>().registerReceiver(
                downloadReceiver, filter, Context.RECEIVER_EXPORTED
            )
        } else {
            getApplication<Application>().registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadReceiver?.let {
            getApplication<Application>().unregisterReceiver(it)
        }
    }
}
