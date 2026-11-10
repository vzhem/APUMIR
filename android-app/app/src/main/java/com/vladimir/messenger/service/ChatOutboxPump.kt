package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.RelayCustodyReconciler
import com.vladimir.messenger.data.repository.UnconfirmedOutboxPump
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
    chatDao: ChatDao,
    private val chatRepository: ChatRepository,
    private val wakeLock: CoreWorkWakeLock? = null,
) {
    private val unconfirmedOutbox = UnconfirmedOutboxPump(messageDao, chatDao, chatRepository::sendViaRust)
    private val custodyReconciler = RelayCustodyReconciler(RustBridge::relayCustodySnapshot, messageDao::terminalOutgoingIds)
    private var job: Job? = null
    private var unconfirmedJob: Job? = null
    private var reconciliationJob: Job? = null

    /**
     * Запустить цикл в области службы. Повторный вызов ничего не делает:
     * перезапуск службы не должен давать два насоса на одну очередь.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        // Repair the window between a Room ACK commit and native cleanup,
        // including after restart, without scanning all historical messages.
        reconciliationJob = scope.launch(Dispatchers.IO) {
            var nextDelay = RECONCILE_INITIAL_DELAY_MS
            while (isActive) {
                delay(nextDelay)
                nextDelay = RECONCILE_SWEEP_DELAY_MS
                try {
                    val more = if (wakeLock != null) wakeLock.withLock { custodyReconciler.reconcile() }
                        else custodyReconciler.reconcile()
                    if (more) nextDelay = RECONCILE_PAGE_DELAY_MS
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Relay reconciliation deferred: ${error.javaClass.simpleName}")
                }
            }
        }
        // Unconfirmed direct sends are retried separately and less often than
        // new/offline messages. The fair scanner visits other conversations
        // even if an older peer cannot be reached.
        unconfirmedJob = scope.launch(Dispatchers.IO) {
            var nextDelay = UNCONFIRMED_INITIAL_DELAY_MS
            while (isActive) {
                delay(nextDelay)
                nextDelay = UNCONFIRMED_SWEEP_DELAY_MS
                try {
                    val pending = messageDao.countPendingOutgoing() > 0
                    val progress = if (wakeLock != null) wakeLock.withLock { unconfirmedOutbox.pump(20) }
                        else unconfirmedOutbox.pump(20)
                    if (progress.moreToScan) {
                        // Still make progress if another chat is permanently
                        // queued, but give new messages most network turns.
                        nextDelay = if (progress.attempted == 0) UNCONFIRMED_SCAN_ONLY_PAGE_DELAY_MS
                            else if (pending) UNCONFIRMED_BUSY_PAGE_DELAY_MS else UNCONFIRMED_PAGE_DELAY_MS
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Unconfirmed outbox cycle failed: ${error.message}")
                }
            }
        }
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                var queued = 0
                var sent = 0
                var moreToScan = false
                try {
                    queued = messageDao.countPendingOutgoing()
                    if (queued > 0) {
                        val progress = if (wakeLock != null) {
                            wakeLock.withLock { chatRepository.pumpQueuedOffline() }
                        } else {
                            chatRepository.pumpQueuedOffline()
                        }
                        sent = progress.sent
                        moreToScan = progress.moreToScan
                        DeliveryCadence.touch()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Outbox pump cycle failed: ${error.message}")
                }
                failures = OutboxCadence.nextFailures(failures, sent, moreToScan)
                delay(
                    OutboxCadence.nextDelayMs(
                        hasQueued = queued > 0,
                        sent = sent,
                        failures = failures,
                        onScreen = DeliveryCadence.isForeground(),
                        moreToScan = moreToScan,
                    ),
                )
            }
        }
    }

    /** Остановить цикл при остановке службы. */
    fun stop() {
        job?.cancel()
        unconfirmedJob?.cancel()
        reconciliationJob?.cancel()
        job = null
        unconfirmedJob = null
        reconciliationJob = null
    }

    private companion object {
        const val TAG = "ChatOutboxPump"
        const val RECONCILE_INITIAL_DELAY_MS = 15_000L
        const val RECONCILE_PAGE_DELAY_MS = 1_000L
        const val RECONCILE_SWEEP_DELAY_MS = 60_000L
        const val UNCONFIRMED_INITIAL_DELAY_MS = 15_000L
        const val UNCONFIRMED_SCAN_ONLY_PAGE_DELAY_MS = 100L
        const val UNCONFIRMED_PAGE_DELAY_MS = 3_000L
        const val UNCONFIRMED_BUSY_PAGE_DELAY_MS = 15_000L
        const val UNCONFIRMED_SWEEP_DELAY_MS = 60_000L
    }
}
