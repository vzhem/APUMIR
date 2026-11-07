package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.group.GroupRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/** Доступ к группам из фоновой задачи (Hilt EntryPoint, как у сторожа службы). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GroupInfoResyncEntryPoint {
    fun groupRepository(): GroupRepository
}

/**
 * Фоновая досылка «карточка группы + темы + состав».
 *
 * Кнопку «Разослать темы и состав заново» владелец мог и не вспомнить, поэтому
 * досылка идёт сама: при открытии группы и, если приложение давно не открывали,
 * здесь — по расписанию. Работа ограничена по построению
 * (см. `GroupInfoResync`): не чаще раза в несколько часов на группу, не больше
 * порции участников за проход, с паузами между пакетами. Фоновая задача лишь
 * запускает проход — все числа и отметки живут в самом `GroupInfoResync`, так
 * что открытый экран и этот worker делят одно окно и не дублируют отправку.
 *
 * Сеть обязательна (иначе конверты осели бы в очереди ядра), батарея не ниже
 * порога: досылка — не срочная работа.
 */
class GroupInfoResyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            GroupInfoResyncEntryPoint::class.java,
        )
        val sent = entry.groupRepository().autoResyncAllGroups()
        Log.i(TAG, "background info resync sent=$sent")
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "background info resync failed: ${error.message}")
        Result.retry()
    }

    companion object {
        private const val TAG = "GroupInfoResyncWorker"
        const val WORK_NAME = "apu-group-info-resync"
    }
}
