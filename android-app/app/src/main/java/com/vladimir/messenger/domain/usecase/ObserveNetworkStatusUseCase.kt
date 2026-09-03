package com.vladimir.messenger.domain.usecase

import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Состояние сети опросом ядра раз в 3 секунды.
 *
 * `flowOn(Dispatchers.IO)` обязателен: без него опрос шёл на ГЛАВНОМ потоке -
 * поток собирателя задавал поток источника. Каждый вызов уходит в Rust и ждёт
 * внутренний замок ядра; на загруженном телефоне это давало «Приложение не
 * отвечает» каждые три секунды, на любом экране, где показан статус сети.
 *
 * `distinctUntilChanged` убирает лишние перерисовки: статус меняется редко, а
 * опрос идёт постоянно.
 */
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
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
}