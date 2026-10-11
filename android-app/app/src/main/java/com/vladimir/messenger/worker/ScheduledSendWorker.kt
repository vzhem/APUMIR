package com.vladimir.messenger.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.schedule.ScheduledSendStore
import com.vladimir.messenger.util.QuietMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Отправка отложенного сообщения в назначенное время. Берём запись из
 * [ScheduledSendStore]; при ошибке повторяем до [MAX_ATTEMPTS] раз.
 */
@HiltWorker
class ScheduledSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val item = ScheduledSendStore.get(applicationContext, id) ?: return Result.success()
        val content = if (item.quiet) QuietMessage.wrap(item.text) else item.text
        val sent = try {
            if (item.isGroup) {
                groupRepository.sendMessage(item.targetId, item.topicId, content).isSuccess
            } else {
                chatRepository.sendMessage(item.targetId, "", content).isSuccess
            }
        } catch (e: Exception) {
            false
        }
        if (sent || runAttemptCount + 1 >= MAX_ATTEMPTS) {
            ScheduledSendStore.remove(applicationContext, id)
            return Result.success()
        }
        return Result.retry()
    }

    companion object {
        const val KEY_ID = "scheduled_id"
        const val MAX_ATTEMPTS = 3

        /** Поставить отправку в очередь WorkManager на нужное время. */
        fun enqueue(context: Context, item: ScheduledSendStore.Item) {
            ScheduledSendStore.add(context, item)
            val delay = (item.sendAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val data = Data.Builder().putString(KEY_ID, item.id).build()
            val request = OneTimeWorkRequest.Builder(ScheduledSendWorker::class.java)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(item.id),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Отменить отложенное сообщение до отправки. */
        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
            ScheduledSendStore.remove(context, id)
        }

        private fun workName(id: String) = "scheduled_send_$id"
    }
}
