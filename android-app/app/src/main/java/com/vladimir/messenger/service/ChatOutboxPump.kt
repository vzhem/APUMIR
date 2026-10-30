package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Досылка офлайн-очереди в своём ритме.
 *
 * Раньше очередь сливалась из общего файлового насоса: раз в три круга по
 * двадцать секунд, то есть примерно раз в минуту. Сообщение, не ушедшее с
 * первого раза, ждало до минуты, а после неудачной попытки — ещё минуту:
 * отсюда «идёт с задержкой». Аватарку это не касалось: она уходила на живое
 * соединение и применялась сразу.
 *
 * Здесь свой цикл: пока очередь пуста — редкая проверка счётчиком, пока есть
 * что досылать — короткий шаг. Ритм считает [OutboxCadence] (чистая
 * арифметика, покрыта тестом), попытки идут вне обработчика событий, поэтому
 * входящие больше не стоят в очереди за исходящими.
 *
 * Отдельный файл, а не правка `CoreServerService`: тот на потолке god-файла
 * (AGENTS.md п. 8), и новый цикл туда не пишется.
 */
class ChatOutboxPump(
    private val messageDao: MessageDao,
    private val chatRepository: ChatRepository,
) {

    private var job: Job? = null

    /**
     * Запустить цикл в области службы. Повторный вызов ничего не делает:
     * перезапуск службы не должен давать два насоса на одну очередь.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                var queued = 0
                var sent = 0
                try {
                    queued = messageDao.queuedOfflineCount()
                    if (queued > 0) {
                        sent = chatRepository.pumpQueuedOffline()
                        DeliveryCadence.touch()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Outbox pump cycle failed: ${error.message}")
                }
                failures = OutboxCadence.nextFailures(failures, sent)
                delay(
                    OutboxCadence.nextDelayMs(
                        hasQueued = queued > 0,
                        sent = sent,
                        failures = failures,
                        onScreen = DeliveryCadence.isForeground(),
                    ),
                )
            }
        }
    }

    /** Остановить цикл при остановке службы. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "ChatOutboxPump"
    }
}
