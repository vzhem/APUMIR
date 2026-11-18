package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Подтверждаем приём кадра эпохи, который мы приняли и сохранили.
 *
 * Зачем. Кадр эпохи уходит обычным сообщением и живёт в постоянной очереди
 * отправителя, пока получатель не подтвердит приём. Мы его разбирали и
 * выбрасывали из чата — без подтверждения. Поэтому у отправителя он оставался
 * навсегда: в отчёте 536 при одном отправленном сообщении стояло
 * `своё ждёт получателя=317`, из них `пробуждение 28`, а `предел-своих=1000`
 * медленно заполнялся кадрами, которые уже никто не ждал. Каждая такая запись —
 * повторная отправка, пробуждения ядра и разряд батареи
 * (`core_wake_acquisitions=3234` за 39 минут).
 *
 * Подтверждение отправляется только после того, как эпоха принята и проверена
 * (`PushEpochIngress.accept` вернул true): отклонённый кадр не подтверждается
 * никогда, иначе отправитель считал бы доставленным то, что мы выбросили.
 */
internal fun acknowledgeEpochPacket(scope: CoroutineScope, messageId: String, senderId: String) {
    if (messageId.isBlank() || senderId.isBlank()) return
    scope.launch {
        runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
            .onFailure { Log.w("EpochInboundAck", "epoch ack failed: ${it.message}") }
    }
}
