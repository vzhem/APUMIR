package com.vladimir.messenger

import android.app.Application
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.worker.ProxyCollectorWorker
import com.vladimir.messenger.worker.RelayWakeWorker
import com.vladimir.messenger.worker.UpdateCheckWorker
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MessengerApplication : Application(), coil.ImageLoaderFactory, androidx.work.Configuration.Provider {
    @javax.inject.Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Общий ImageLoader Coil С декодером GIF: без него AsyncImage рисует
     * только первый кадр. Гифки в темах групп (v11.74.14) оживают сами.
     */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            // Coil downloads GIF catalog previews independently of BotApi.
            // Intercept before DNS/connect, not at an already-connected socket;
            // deny redirects so they cannot escape the same gate.
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .addInterceptor { chain ->
                        // Image URLs in messages/catalogs are untrusted. A host
                        // spelling like "10.evil.example" must not pass as a
                        // LAN address, and resolving names here would itself
                        // leak DNS. Local pictures use file/content/byte data,
                        // not HTTP; refuse *all* automatic HTTP previews while
                        // an authenticated swarm route exists.
                        if (!com.vladimir.messenger.data.RustBridge.externalTransportAllowed()) {
                            throw java.io.IOException("swarm-first: automatic HTTP image deferred")
                        }
                        chain.proceed(chain.request())
                    }
                    .build()
            }
            .components {
                // API 28+: системный декодер (GIF и анимированный WebP);
                // младше: встроенный декодер GIF (minSdk 26-27).
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                }
                add(coil.decode.GifDecoder.Factory())
            }
            .build()


    override fun onCreate() {
        super.onCreate()
        com.vladimir.messenger.data.story.StoryStore.attach(this)
        // Отклики на истории (значки и комментарии) живут своим снимком и
        // поднимаются вместе с лентой: до attach книга откликов пуста.
        com.vladimir.messenger.data.story.StoryFeedbackStore.attach(this)
        // Отметка в журнале «Логов»: владелец видит, с какого момента
        // собраны события, и не путает свежий запуск со старым.
        com.vladimir.messenger.data.diagnostics.TransferDiagnostics.record(
            "app",
            "процесс приложения запущен",
        )
        // Резервная копия, подготовленная к восстановлению, ложится на место
        // здесь - до Room, сервисов и воркеров, пока базу никто не открыл.
        if (com.vladimir.messenger.data.backup.ProfileBackup.applyStagedIfAny(applicationContext)) {
            Log.i("MessengerApp", "Profile restored from backup on startup")
        }
        // Must run before Room/services/workers can observe restored stale state.
        DeviceIdentityMarker.discardIfRestored(applicationContext)
        com.vladimir.messenger.data.diagnostics.OperationalDiagnostics.install(this)
        com.vladimir.messenger.data.diagnostics.RuntimeDiagnostics.install(this)
        // р236: черновики сообщений (недописанный текст поля ввода).
        com.vladimir.messenger.data.draft.DraftStore.attach(applicationContext)
        createNotificationChannels()
        com.vladimir.messenger.service.UpdateNotifier(applicationContext).ensureChannel()
        scheduleUpdateChecks()
        scheduleBoundedRelayWake()
        runCatching { com.vladimir.messenger.data.maintenance.AutomaticMaintenance.schedule(this) }
            .onFailure { Log.w("MessengerApp", "Maintenance scheduling deferred", it) }
        // Restore work after a crash between committing a hidden letter and enqueueing its timer.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.vladimir.messenger.worker.ScheduledSendWorker.restore(applicationContext)
                com.vladimir.messenger.data.schedule.DeferredInboxStore.pending(applicationContext).forEach { (key, at) ->
                    com.vladimir.messenger.worker.DeferredRevealWorker.enqueue(applicationContext, key, at)
                }
            }.onFailure { Log.w("MessengerApp", "Deferred work recovery failed", it) }
        }
        // Раунд 260: ядро стартует как можно раньше - ещё до отрисовки
        // первого экрана, чтобы к открытию чатов движок уже поднимался.
        // На фоновых пробуждениях система может запретить - там сервис
        // поднимут будильник и приёмник сети, как и раньше.
        runCatching {
            val coreIntent = android.content.Intent(this, com.vladimir.messenger.service.CoreServerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(coreIntent)
            } else {
                startService(coreIntent)
            }
        }.onFailure { android.util.Log.w("MessengerApp", "early core start skipped: ${it.message}") }

        // Automatic collection is an Organizer (20 qualified referrals) entitlement.
        try {
            val qualified = ReferralRankStore.qualifiedDirectCount(applicationContext)
            val workManager = WorkManager.getInstance(applicationContext)
            if (FileTransferRankPolicy.canUseAutomaticProxy(qualified)) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val collectRequest = PeriodicWorkRequestBuilder<ProxyCollectorWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    ProxyCollectorWorker.WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    collectRequest
                )
                Log.i("MessengerApp", "Rank-entitled proxy collector scheduled")
            } else {
                workManager.cancelUniqueWork(ProxyCollectorWorker.WORK_NAME)
                Log.i("MessengerApp", "Automatic proxy collector locked below rank 20")
            }
        } catch (e: Exception) {
            Log.e("MessengerApp", "Failed to apply proxy collector entitlement", e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.vladimir.messenger.data.diagnostics.RuntimeDiagnostics.memoryTrim(level)
    }

    /**
     * Проверка релиза в фоне: телефон узнаёт о новой версии и тогда, когда
     * приложение не открывали. WorkManager соблюдает сеть и сам переживает
     * перезапуск; повторное уведомление об одной версии отсекает notifier.
     */
    private fun scheduleUpdateChecks() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                12L,
                TimeUnit.HOURS,
                3L,
                TimeUnit.HOURS,
            )
                .setInitialDelay(15L, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                UpdateCheckWorker.WORK_NAME,
                // Не перестраиваем отсчёт при каждом холодном старте: иначе
                // телефон, который открывают часто и ненадолго, мог бы вечно
                // не дойти до фоновой проверки.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } catch (error: Exception) {
            Log.e("MessengerApp", "Failed to schedule update checks", error)
        }
    }

    /** M8-E slice 1: OS-managed bounded wake, без exact alarm/вечного foreground. */
    private fun scheduleBoundedRelayWake() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<RelayWakeWorker>(
                RelayWakeWorker.REPEAT_HOURS,
                TimeUnit.HOURS,
                RelayWakeWorker.FLEX_HOURS,
                TimeUnit.HOURS,
            )
                .setInitialDelay(RelayWakeWorker.INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                RelayWakeWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(
                "MessengerApp",
                "M8 bounded relay wake scheduled: every ${RelayWakeWorker.REPEAT_HOURS}h " +
                    "flex=${RelayWakeWorker.FLEX_HOURS}h window=${RelayWakeWorker.ACTIVE_WINDOW_MS}ms"
            )
        } catch (error: Exception) {
            Log.e("MessengerApp", "Failed to schedule M8 bounded relay wake", error)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Сообщения: высокий приоритет - всплывают и звучат.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
            // Раунд 142: служебная плашка «на связи» - МИНИМАЛЬНАЯ важность:
            // без иконки в статус-баре, без звука, в самом низу шторки
            // (владелец: «это уведомление нужно спрятать от абонента, нужны
            // только уведомления о сообщениях»). Важность существующего
            // канала менять нельзя, поэтому службе - отдельный канал.
            val service = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Фоновая связь (служебное)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Признак работающей фоновой связи APU"
                setShowBadge(false)
            }
            manager.createNotificationChannel(service)
        }
    }

    companion object {
        const val CHANNEL_ID = "p2p_messenger_service"

        /** Раунд 142: отдельный тихий канал для плашки фоновой службы. */
        const val CHANNEL_SERVICE_ID = "apu_service_status"
    }


}
