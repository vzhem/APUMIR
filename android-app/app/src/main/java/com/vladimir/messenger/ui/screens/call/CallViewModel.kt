package com.vladimir.messenger.ui.screens.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vladimir.messenger.data.call.CallManager
import com.vladimir.messenger.data.call.CallStateMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Экран звонка: обёртка над синглтоном CallManager (сам звонок живёт глобально,
 * переживает навигацию — как в любом мессенджере).
 *
 * Маршрут call?peerId=…&peerName=… с заполненным peerId = исходящий: экран сам
 * стартует звонок один раз. Пустой peerId = нас позвали на входящий, машина уже
 * звонит — только показываем.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val callManager: CallManager,
) : ViewModel() {

    private val peerId: String = savedStateHandle.get<String>("peerId").orEmpty()
    private val peerName: String = savedStateHandle.get<String>("peerName").orEmpty().let { raw ->
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
    private var autoStarted = false

    val uiState: StateFlow<CallManager.CallUiState> = callManager.uiState

    /** Разовый автозапуск исходящего звонка при создании экрана. */
    fun ensureStarted() {
        if (autoStarted) return
        autoStarted = true
        if (peerId.isBlank()) return
        if (callManager.uiState.value.phase == CallStateMachine.Phase.IDLE) {
            callManager.startOutgoing(peerId, peerName)
        }
    }

    fun accept() = callManager.accept()
    fun hangupOrReject() = callManager.hangupOrReject()
    fun toggleMute() = callManager.toggleMute()
    fun toggleSpeaker() = callManager.toggleSpeaker()
    fun micDenied() = callManager.onMicPermissionDenied()
}
