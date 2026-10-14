package com.vladimir.messenger.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.schedule.ScheduledSendStore
import com.vladimir.messenger.data.schedule.DeferredDelivery
import com.vladimir.messenger.data.schedule.DeferredInboxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vladimir.messenger.util.QuietMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Предварительная доставка и отдельный таймер совместимой отправки.
 * Ошибки не удаляют запись: повторяем, пока сообщение остаётся в очереди.
 */
@HiltWorker
class ScheduledSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository,
    private val deferredDelivery: DeferredDelivery,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val item = ScheduledSendStore.get(applicationContext, id) ?: return Result.success()
        if (item.preDeliver) {
            return try {
                val done = deferredDelivery.pump(id, inputData.getBoolean(KEY_EARLY, false))
                if (done) Result.success() else Result.retry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.IO) {
                    ScheduledSendStore.update(applicationContext, id) {
                        it.copy(lastError = error.message?.take(180) ?: "Ошибка подготовки отправки")
                    }
                }
                Result.retry()
            }
        }
        if (System.currentTimeMillis() < item.sendAtMs) return Result.retry()
        val content = if (item.quiet) QuietMessage.wrap(item.text) else item.text
        val sent = try {
            if (item.isGroup) {
                groupRepository.sendMessage(item.targetId, item.topicId, content).isSuccess
            } else {
                chatRepository.sendMessage(item.targetId, "", content).isSuccess
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            false
        }
        if (sent) {
            ScheduledSendStore.remove(applicationContext, id)
            return Result.success()
        }
        return Result.retry()
    }

    companion object {
        const val KEY_ID = "scheduled_id"
        private const val KEY_EARLY = "early"

        /** New schedules pre-deliver immediately; a separate timer still owns due-time fallback. */
        fun enqueue(context: Context, item: ScheduledSendStore.Item) {
            ScheduledSendStore.add(context, item.copy(ownerId = DeferredInboxStore.owner(context)))
            scheduleWork(context, item)
        }

        fun restore(context: Context) {
            ScheduledSendStore.load(context)
            ScheduledSendStore.items.value.forEach { scheduleWork(context, it) }
        }

        private fun scheduleWork(context: Context, item: ScheduledSendStore.Item) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (item.cancelRequested) {
                enqueueCancellation(context, item.id)
                return
            }
            if (item.preDeliver && item.sendAtMs > System.currentTimeMillis()) {
                val early = OneTimeWorkRequest.Builder(ScheduledSendWorker::class.java)
                    .setInputData(Data.Builder().putString(KEY_ID, item.id).putBoolean(KEY_EARLY, true).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build()
                manager.enqueueUniqueWork("scheduled_early_${item.id}", ExistingWorkPolicy.KEEP, early)
            }
            val request = OneTimeWorkRequest.Builder(ScheduledSendWorker::class.java)
                .setInitialDelay((item.sendAtMs - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putString(KEY_ID, item.id).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniqueWork(workName(item.id), ExistingWorkPolicy.KEEP, request)
        }

        /** Cancellation remains available after transfer. Persist intent first, then send/retry it. */
        fun cancel(context: Context, id: String) {
            val pending = ScheduledSendStore.requestCancel(context, id)
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(workName(id))
            manager.cancelUniqueWork("scheduled_early_$id")
            if (pending != null) enqueueCancellation(context, id)
        }

        private fun enqueueCancellation(context: Context, id: String) {
            val request = OneTimeWorkRequest.Builder(ScheduledSendWorker::class.java)
                .setInputData(Data.Builder().putString(KEY_ID, id).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("scheduled_cancel_$id", ExistingWorkPolicy.KEEP, request)
        }

        private fun workName(id: String) = "scheduled_send_$id"
    }
}
