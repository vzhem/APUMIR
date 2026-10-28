package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.diagnostics.OperationalCounter
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.service.DeliveryAssurance
import com.vladimir.messenger.service.DeliveryWakePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сторож доставки на системном уровне: WorkManager раз в пятнадцать минут
 * проверяет, жива ли служба, и поднимает её, если метка жизни несвежая.
 *
 * Зачем это нужно, если есть будильник. Будильник — одноразовый: он
 * срабатывает, поднимает службу и назначает следующий. Но если система
 * запретила старт (Android 12+ не даёт поднять foreground-сервис из фона),
 * цепочка обрывается, пока приложение не откроют руками. WorkManager — это
 * единственный механизм, который система гарантированно исполняет сама:
 * он переживает перезапуск телефона, дозволяет работу по сети и не требует
 * разрешений. Он играет роль «системного толчка», который у Telegram делает
 * push: раз в пятнадцать минут проверяем и, если нужно, просыпаемся.
 *
 * Что работник НЕ делает: не держит соединение, не запускает второй экземпляр
 * службы (проверка [DeliveryWakePolicy.shouldWakeNow] по метке жизни), не
 * пишет в сеть и не делает retry-циклов — следующий тик и есть retry.
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val app = applicationContext
            if (!DeliveryAssurance.isEnabled(app)) {
                Log.i(TAG, "watchdog: delivery wake is off, nothing to do")
                return@withContext Result.success()
            }
            val status = DeliveryAssurance.status(app)
            val now = System.currentTimeMillis()
            if (!DeliveryWakePolicy.shouldWakeNow(status.conditions, status.lastAliveMs, now)) {
                // Служба жива (или уведомления выключены): тик отработал вхолостую.
                return@withContext Result.success()
            }
            OperationalDiagnostics.tracker.count(OperationalCounter.DELIVERY_WAKE_START)
            DeliveryAssurance.noteWake(app, now)
            val started = DeliveryAssurance.startService(app)
            if (started) {
                OperationalDiagnostics.recordWake(status.lastAliveMs, now)
                Log.i(TAG, "watchdog: service started, gap " + gap(status.lastAliveMs, now))
            } else {
                // Старт из фона запрещён: это не ошибка работника (повторять
                // бессмысленно до следующего тика), но человеку нужна причина —
                // она видна на экране «Уведомления приходят вовремя».
                OperationalDiagnostics.tracker.count(OperationalCounter.FOREGROUND_START_REFUSED)
                com.vladimir.messenger.data.diagnostics.TransferDiagnostics.recordWarning(
                    "delivery",
                    "сторож не смог поднять службу: система запретила старт в фоне",
                )
                Log.w(TAG, "watchdog: start refused by the system")
            }
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // Следующий тик — единственный retry: быстрый цикл не создаём.
            Log.e(TAG, "watchdog failed", error)
            Result.success()
        }
    }

    private fun gap(lastAliveMs: Long, nowMs: Long): String {
        if (lastAliveMs <= 0L) return "first start"
        val minutes = ((nowMs - lastAliveMs) / 60_000L).coerceAtLeast(0L)
        return "$minutes min without the service"
    }

    companion object {
        private const val TAG = "ServiceWatchdog"

        /** Имя уникальной периодической работы: повторная постановка не плодит копий. */
        const val WORK_NAME = "apu_service_watchdog"

        /**
         * Период работы. Пятнадцать минут — минимум, который разрешает
         * WorkManager, и он же совпадает с интервалом будильника-сторожа:
         * два независимых механизма страхуют друг друга.
         */
        const val REPEAT_MINUTES = DeliveryWakePolicy.WATCHDOG_MS / 60_000L
    }
}
