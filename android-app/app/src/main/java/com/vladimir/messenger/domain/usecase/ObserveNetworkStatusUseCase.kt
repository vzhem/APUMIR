package com.vladimir.messenger.domain.usecase

import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.NetworkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class ObserveNetworkStatusUseCase @Inject constructor() {
    operator fun invoke(): Flow<NetworkStatus> = flow {
        while (true) {
            val statusStr = RustBridge.networkStatus()
            val status = when (statusStr.lowercase()) {
                "connected"  -> NetworkStatus.Connected
                "connecting" -> NetworkStatus.Connecting
                "degraded"   -> NetworkStatus.Degraded
                else         -> NetworkStatus.Disconnected
            }
            emit(status)
            delay(3_000)
        }
    }
}