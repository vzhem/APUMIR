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
    @javax.inject.Inject lateinit var obsoleteSameDeviceRegistrationCleanup:
        com.vladimir.messenger.data.security.ObsoleteSameDeviceRegistrationCleanup

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
        // Политика пути доставки и kill-switch IPv6 — ДО старта службы: ядро
        // применяет запомненное в момент привязки сокетов, а не после первого
        // колбэка сети. Иначе выключенный IPv6 успевал подняться на `[::]`
        // до того, как `NetworkMonitor` узнает о сети.
        com.vladimir.messenger.data.swarm.NetworkPathSettings.applyToCore(this)
        com.vladimir.messenger.data.pairing.DevicePairingController.attach(this)
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
        // Backfill the same-device identity ledger for profiles created before
        // this feature.  The raw id remains in ordinary prefs only while the
        // current profile is active; the history marker is a Keystore alias.
        runCatching {
            val currentNode = getSharedPreferences("p2p_prefs", MODE_PRIVATE)
                .getString("node_id", null)
            if (!currentNode.isNullOrBlank()) {
                com.vladimir.messenger.data.security.DeviceIdentityHistory
                    .remember(applicationContext, currentNode)
            }
        }.onFailure { Log.w("MessengerApp", "identity history backfill skipped", it) }
        // On an app update, remove every obsolete identity proven to have been
        // created by this installation before the core can receive old gossip.
        // The Keystore owner marker makes this idempotent and survives logout.
        runCatching {
            val result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                obsoleteSameDeviceRegistrationCleanup.runForCurrentBuild()
            }
            if (result.removedOwners > 0 || result.failedOwners > 0) {
                Log.i(
                    "MessengerApp",
                    "same-device obsolete identity cleanup: " +
                        "removed=${result.removedOwners}, failed=${result.failedOwners}",
                )
            }
        }.onFailure { Log.w("MessengerApp", "same-device identity cleanup skipped", it) }
        com.vladimir.messenger.data.diagnostics.OperationalDiagnostics.install(this)
        com.vladimir.messenger.data.diagnostics.RuntimeDiagnostics.install(this)
        // р236: черновики сообщений (недописанный текст поля ввода).
        com.vladimir.messenger.data.draft.DraftStore.attach(applicationContext)
        createNotificationChannels()
        com.vladimir.messenger.service.UpdateNotifier(applicationContext).ensureChannel()
        scheduleUpdateChecks()
        scheduleBoundedRelayWake()
        scheduleServiceWatchdog()
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
        // Раунд 260: ядро стартует как можно раньше, но только после того,
        // как на этой установке действительно создана identity. Раньше этот
        // безусловный старт поднимал Anonymous-движок прямо на экране
        // онбординга. CreateIdentityUseCase затем видел уже работающий движок,
        // забирал его временный адрес и launcher получал другую/пустую identity
        // вместо аккаунта телефона.
        val identityPrefs = getSharedPreferences("p2p_prefs", MODE_PRIVATE)
        val localIdentityReady = identityPrefs.getBoolean("identity_created", false) &&
            DeviceIdentityMarker.isPresent(applicationContext)
        if (localIdentityReady) {
            // Ещё до отрисовки первого экрана, чтобы к открытию чатов движок
            // уже поднимался. На фоновых пробуждениях систему может попросить
            // сервис снова; его собственная проверка identity остаётся второй
            // линией защиты от запуска в онбординге.
            runCatching {
                val coreIntent = android.content.Intent(this, com.vladimir.messenger.service.CoreServerService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(coreIntent)
                } else {
                    startService(coreIntent)
                }
            }.onFailure { android.util.Log.w("MessengerApp", "early core start skipped: ${it.message}") }
        } else {
            android.util.Log.i("MessengerApp", "early core start deferred: local identity is not ready")
        }

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

    /**
     * Сторож доставки: раз в пятнадцать минут проверить, жива ли служба, и
     * поднять её, если приложение убили. Это то, что у Telegram делает push:
     * системный механизм, который работает без открытого приложения и
     * переживает перезапуск телефона (решение владельца 2026-10-28).
     */
    private fun scheduleServiceWatchdog() {
        try {
            val request = PeriodicWorkRequestBuilder<com.vladimir.messenger.worker.ServiceWatchdogWorker>(
                com.vladimir.messenger.worker.ServiceWatchdogWorker.REPEAT_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                com.vladimir.messenger.worker.ServiceWatchdogWorker.WORK_NAME,
                // UPDATE, а не KEEP: интервал сторожа — часть поведения
                // доставки, и после обновления приложения он должен
                // применяться сразу, а не через старый период.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i("MessengerApp", "delivery watchdog scheduled every " +
                com.vladimir.messenger.worker.ServiceWatchdogWorker.REPEAT_MINUTES + " min")
        } catch (error: Exception) {
            Log.e("MessengerApp", "Failed to schedule delivery watchdog", error)
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
            // SOS: тревожные сообщения из групп. Отдельный канал с сигналом
            // будильника и длинной вибрацией - тревога обязана разбудить
            // телефон, а не прозвучать как обычное сообщение. Звук и
            // вибрация на новых Android принадлежат каналу, поэтому каналу
            // и задаём тревожный характер.
            val sos = NotificationChannel(
                CHANNEL_SOS_ID,
                "SOS — тревога в группах",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Тревожные сообщения SOS: звучат даже при выключенном звуке группы"
                setShowBadge(true)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 250, 700, 250, 700)
                // Если владелец сам даст приложению доступ сквозь «Не
                // беспокоить» - тревога пройдёт и сквозь него.
                setBypassDnd(true)
            }
            manager.createNotificationChannel(sos)
        }
    }

    companion object {
        const val CHANNEL_ID = "p2p_messenger_service"

        /** Раунд 142: отдельный тихий канал для плашки фоновой службы. */
        const val CHANNEL_SERVICE_ID = "apu_service_status"

        /** Тревожные сообщения SOS из групп: отдельный громкий канал. */
        const val CHANNEL_SOS_ID = "apu_sos_alerts"
    }


}
