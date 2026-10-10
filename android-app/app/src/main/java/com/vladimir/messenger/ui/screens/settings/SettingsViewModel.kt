package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.diagnostics.MqttLinkText
import com.vladimir.messenger.data.notification.NotificationMuteScope
import com.vladimir.messenger.data.notification.NotificationMuteStore
import com.vladimir.messenger.util.OwnInvite
import com.vladimir.messenger.data.repository.NetworkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

data class SettingsUiState(
    val displayName: String = "Anonymous",
    val fingerprint: String = "Loading...",
    val inviteLink: String = "",
    val connectionStatus: NetworkStatus = NetworkStatus.Disconnected,
    val connectedPeers: Int = 0,
    val publicIp: String? = null,
    val connectionMode: String = "Unknown",
    val appVersion: String = "0.1.0",
    val rustCoreVersion: String = "Loading...",
    /** Живая диагностика брокерной линии (пусто, пока движок не стартовал). */
    val mqttLink: String = "",
    /** Та же диагностика по-человечески (раунд 190): статус, путь, заминка. */
    val mqttHuman: String = "",
    /** Состояние нашего сервера: адрес, доступность и отклик. */
    val serverStatus: String = "проверяю…",
    /** Строка о резервной копии адресов (для карточки «Сервер»). */
    val addrBookLine: String = "",
    /** Последний итог ручных действий с копией (для диалога). */
    val addrBookMessage: String = "",
    val proxyTunnelEnabled: Boolean = true,
    /** Сколько сердечек набрал мой профиль. */
    val heartCount: Int = 0,
    /** Сколько отметок анти-рейтинга получил мой профиль. */
    val antiRatingCount: Int = 0,
    /** Действует ли временное предупреждение о всплеске жалоб на мой профиль. */
    val antiRatingWarning: Boolean = false,
    val antiRatingUntilMs: Long = 0,
    /** Раунд 249: никнейм «Защиты личности» — от него зависит текст окна выхода. */
    val protectedNick: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationMuteStore: NotificationMuteStore,
    private val proxyAutopilot: com.vladimir.messenger.service.ProxyAutopilot,
    private val fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter,
    private val hearts: com.vladimir.messenger.data.heart.HeartRepository,
    private val apkSeeder: com.vladimir.messenger.data.update.ApkSeeder,
    private val updateChecker: com.vladimir.messenger.service.UpdateChecker,
    private val addressBookBackup: com.vladimir.messenger.data.backup.AddressBookBackup,
    private val addressBookSwarm: com.vladimir.messenger.data.backup.AddressBookSwarmBackup,
    private val botApi: com.vladimir.messenger.service.BotApi,
    private val identityBackup: com.vladimir.messenger.data.security.IdentityBackup,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    private var lastGossipTrigger: Long = 0L
    val uiState = _uiState.asStateFlow()

    /** Живое состояние сроков глобальной и разделных пауз уведомлений. */
    val notificationMuteRevision = notificationMuteStore.revision

    fun notificationMuteUntil(scope: NotificationMuteScope): Long =
        notificationMuteStore.mutedUntilMs(scope)

    fun setNotificationMuteUntil(scope: NotificationMuteScope, untilMs: Long) {
        notificationMuteStore.setMutedUntil(scope, untilMs)
    }

    /**
     * Раунд 249: «Выйти из APU» - стереть локальные данные и вернуться на
     * экран входа, чтобы войти под другим логином.
     *
     * Стирается ВСЁ (как «очистить данные» в системных настройках): чаты,
     * контакты, ключи, настройки. Половинчатое состояние оставлять нельзя -
     * ядро продолжило бы работать под старым адресом, и «другой логин»
     * получил бы чужую переписку. Вернуться в СВОЙ профиль после выхода
     * можно только по никнейму и паролю («Защита личности»), поэтому экран
     * настроек предупреждает, если пароль не задан.
     */
    fun logout() {
        viewModelScope.launch {
            // Движок гасим первым: он держит открытыми базы, которые сейчас
            // сотрёт система.
            runCatching {
                context.stopService(
                    android.content.Intent(context, com.vladimir.messenger.service.CoreServerService::class.java),
                )
            }
            // Автоперезапуск: будильник переживает смерть процесса, поэтому
            // телефон сам откроет APU на экране входа - человек сразу может
            // войти под другим логином, не ища иконку.
            runCatching {
                val launch = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    val pending = android.app.PendingIntent.getActivity(
                        context,
                        0,
                        launch,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                    val alarm = context.getSystemService(Context.ALARM_SERVICE)
                        as android.app.AlarmManager
                    alarm.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 700L,
                        pending,
                    )
                }
            }
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            // Убивает процесс и стирает данные приложения; следующего кода
            // система уже не исполнит.
            am.clearApplicationUserData()
        }
    }

    /** Идёт ли ручная проверка обновлений (кнопка «Проверить»). */
    private val _updatesChecking = MutableStateFlow(false)
    val updatesChecking: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _updatesChecking.asStateFlow()

    /** Официальный релиз (GitHub), найденный проверкой; null — не найден. */
    private val _officialRelease = MutableStateFlow<com.vladimir.messenger.service.UpdateChecker.ReleaseInfo?>(null)
    val officialRelease: kotlinx.coroutines.flow.StateFlow<com.vladimir.messenger.service.UpdateChecker.ReleaseInfo?>
        get() = _officialRelease.asStateFlow()

    // ── Рой APK (docs/UPDATE_SEEDING.md): карточка «Обновления» ─────────────

    /** Моя раздача обновления (сидер — синглтон, потоки живые). */
    val apkSeed: kotlinx.coroutines.flow.StateFlow<com.vladimir.messenger.data.update.ApkSeeder.SeedUi?>
        get() = apkSeeder.seed

    /** Объявления соседей: только версии новее моей, лучшие первыми. */
    val apkOffers: kotlinx.coroutines.flow.StateFlow<List<com.vladimir.messenger.data.update.ApkSeeder.Offer>>
        get() = apkSeeder.offers

    /** Приём обновления: ход в байтах. */
    val apkDownload: kotlinx.coroutines.flow.StateFlow<com.vladimir.messenger.data.update.ApkSeeder.Download?>
        get() = apkSeeder.download

    /** Скачанное обновление: можно ставить, раздача уже идёт. */
    val apkReady: kotlinx.coroutines.flow.StateFlow<com.vladimir.messenger.data.update.ApkSeeder.Ready?>
        get() = apkSeeder.ready

    /** Принятые APK-файлы: кандидаты на «отметить как обновление». */
    val apkReceivedApks: kotlinx.coroutines.flow.StateFlow<List<com.vladimir.messenger.data.update.ApkSeeder.ReceivedApk>>
        get() = apkSeeder.receivedApks

    /** Раунд 133: предложения соседей — дифф-патчи для ровно моей версии. */
    val apkPatchOffers: kotlinx.coroutines.flow.StateFlow<List<com.vladimir.messenger.data.update.ApkSeeder.PatchOfferUi>>
        get() = apkSeeder.patchOffers

    /** Раунд 133: ход приёма дифф-патча. */
    val apkPatchDownload: kotlinx.coroutines.flow.StateFlow<com.vladimir.messenger.data.update.ApkSeeder.PatchDownload?>
        get() = apkSeeder.patchDownload

    /**
     * Выбранный в проводнике APK (SAF): имя берётся из самого файла, версия
     * читается из его AndroidManifest (пока читается — поле версии ждёт).
     */
    data class ApkPickUi(
        val displayName: String,
        val sizeBytes: Long,
        /** versionName из самого APK; null — ещё читаем или не разобрался. */
        val version: String? = null,
        /** Копия в кэше готова; null — ещё копируем. */
        val tempPath: String? = null,
        val error: String? = null,
    )

    private val _apkPick = MutableStateFlow<ApkPickUi?>(null)
    val apkPick: kotlinx.coroutines.flow.StateFlow<ApkPickUi?>
        get() = _apkPick.asStateFlow()

    /** Обозрел экран: перечитать принятые APK и догнать загрузки (сканы не чаще 5 минут в сидере). */
    fun onUpdatesAppeared() {
        apkSeeder.refreshReceivedApks()
        apkSeeder.pickupDownloadedApks()
    }

    /**
     * Файл выбран в проводнике: сразу показываем настоящее имя и размер
     * (ContentResolver), затем в фоне копируем во временный файл и читаем
     * версию прямо из APK — поле версии заполнится само.
     */
    fun onApkPicked(uri: android.net.Uri) {
        viewModelScope.launch {
            val quick = withContext(Dispatchers.IO) {
                runCatching {
                    var name = ""
                    var size = -1L
                    context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                        null, null, null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty()
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                        }
                    }
                    ApkPickUi(
                        displayName = name.ifBlank { "обновление.apk" },
                        sizeBytes = if (size >= 0L) size else 0L,
                    )
                }.getOrElse { ApkPickUi(displayName = "обновление.apk", sizeBytes = 0L) }
            }
            _apkPick.value = quick
            val prepared = withContext(Dispatchers.IO) {
                val temp = runCatching {
                    java.io.File.createTempFile("apu_pick_", ".apk", context.cacheDir)
                }.getOrNull() ?: return@withContext quick.copy(error = context.getString(R.string.st_open_failed))
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext quick.copy(error = context.getString(R.string.st_open_failed))
                    // Версия — из самого APK; если архив не читается — из имени.
                    val version = runCatching {
                        context.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)?.versionName
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: com.vladimir.messenger.data.update.ApkUpdate.versionFromName(quick.displayName)
                    quick.copy(version = version, tempPath = temp.absolutePath)
                } catch (error: Exception) {
                    temp.delete()
                    quick.copy(error = context.getString(R.string.st_read_error, error.message))
                }
            }
            prepared.error?.let { message ->
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            _apkPick.value = prepared
        }
    }

    /** Подтверждено: раздавать выбранный файл как версию [version]. */
    fun onApkPickConfirm(version: String) {
        val pick = _apkPick.value ?: return
        val tempPath = pick.tempPath ?: return // ещё копируется
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val temp = java.io.File(tempPath)
                try {
                    apkSeeder.markAsSeed(temp, version, autoReseed = true, displayName = pick.displayName)
                } finally {
                    temp.delete()
                }
            }
            _apkPick.value = null
            toastIf(result)
        }
    }

    /** Отмена выбора файла: убрать временную копию. */
    fun onApkPickCancel() {
        val pick = _apkPick.value ?: return
        pick.tempPath?.let { path -> java.io.File(path).delete() }
        _apkPick.value = null
    }

    /** Раздавать уже принятый APK (из чата) как версию [version]. */
    fun onMarkReceivedApkAsUpdate(transferId: String, version: String) {
        viewModelScope.launch {
            val result = runCatching { apkSeeder.markReceivedApkAsSeed(transferId, version, autoReseed = true) }
                .getOrElse { context.getString(R.string.st_error, it.message) }
            toastIf(result)
        }
    }

    /** Остановить раздачу: объявление и копия долой. */
    fun onStopUpdateSeed() {
        viewModelScope.launch { runCatching { apkSeeder.unmark() } }
    }

    /** Начать качать версию, которую раздаёт узел [nodeId]. */
    fun onDownloadUpdateFrom(nodeId: String) {
        viewModelScope.launch { runCatching { apkSeeder.requestUpdate(nodeId) } }
    }

    /** Остановить приём обновления. */
    fun onCancelUpdateDownload() {
        viewModelScope.launch { runCatching { apkSeeder.cancelDownload() } }
    }

    /** Раунд 133: качать у узла [nodeId] дифф-патч — только разницу версий. */
    fun onDownloadPatchFrom(nodeId: String) {
        viewModelScope.launch { runCatching { apkSeeder.requestPatchUpdate(nodeId) } }
    }

    /** Остановить приём дифф-патча. */
    fun onCancelPatchDownload() {
        viewModelScope.launch { runCatching { apkSeeder.cancelPatchDownload() } }
    }

    /** Установить скачанное обновление (системный диалог). Ошибка — тостом. */
    fun onInstallUpdate() {
        viewModelScope.launch {
            val result = runCatching { apkSeeder.installReady() }
                .getOrElse { context.getString(R.string.st_install_failed, it.message) }
            toastIf(result)
        }
    }

    /**
     * Кнопка «Проверить новую версию»: (1) спрашиваем соседей — `upask`
     * всем известным узлам, кто раздаёт новее, объявится `upk`; (2)
     * перечитываем принятые APK; (3) смотрим официальный релиз на GitHub.
     * Если нашлось и там, и там — в карточке появятся ДВЕ кнопки выбора,
     * откуда качать.
     */
    fun onCheckForUpdates() {
        if (_updatesChecking.value) return
        viewModelScope.launch {
            _updatesChecking.value = true
            apkSeeder.refreshReceivedApks()
            val asked = runCatching { apkSeeder.askNeighbors() }.getOrDefault(0)
            val currentVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            }.getOrDefault("0")
            val release = runCatching { updateChecker.checkForUpdate(currentVersion, manual = true) }.getOrNull()
            _officialRelease.value = release
            _updatesChecking.value = false
            // Соседи отвечают на `upask` не мгновенно: предложения доедут
            // через пару секунд и карточка обновится сама (StateFlow).
            val neighbors = apkOffers.value.size
            val message = when {
                release != null && neighbors > 0 ->
                    "Найдено в двух местах: официальный сайт v${release.version.removePrefix("v")} " +
                        "и $neighbors сосед(ей) по сети — выберите в карточке, откуда скачать"
                release != null ->
                    "Есть новая версия v${release.version.removePrefix("v")} на официальном сайте — кнопка в карточке"
                neighbors > 0 ->
                    context.getString(R.string.st_neighbors_offer)
                asked > 0 -> "Спрошено у $asked соседей; новых объявлений пока нет"
                else -> context.getString(R.string.st_no_updates)
            }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Скачать официальный релиз (DownloadManager). Когда файл докачается,
     * он САМ появится в этом разделе: версия прочитается из APK, карточка
     * «Обновить до vX» и раздача соседям начнутся без человека.
     */
    fun onDownloadOfficialRelease() {
        val release = _officialRelease.value ?: return
        runCatching { updateChecker.downloadApk(release) }
            .onSuccess { id ->
                if (id == -1L) {
                    // Раунд 132: дифф-патч - скачали разницу, установщик уже открыт.
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.st_update_delta_done),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.st_download_started),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .onFailure {
                android.widget.Toast.makeText(context, context.getString(R.string.toast_download_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
            }
    }

    private fun toastIf(message: String) {
        if (message.isBlank()) return
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Свои сердечки. Голоса приходят по сети в любой момент, поэтому счётчик
     * слушаем, а не читаем один раз при открытии экрана.
     */
    private fun observeMyHearts() {
        viewModelScope.launch {
            // nodeId() уходит в ядро и ждёт его внутренний замок: на главном
            // потоке это подвешивает отрисовку. Свой адрес лежит в настройках
            // с первого запуска - читаем оттуда, а к ядру идём лишь запасным
            // путём и уже в фоне.
            val me = withContext(Dispatchers.IO) {
                context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                    .getString("node_id", null)
                    ?.takeIf { it.isNotBlank() }
                    ?: com.vladimir.messenger.data.RustBridge.nodeId().orEmpty()
            }
            if (me.isBlank()) return@launch
            launch {
                hearts.observeCount(me).collect { count ->
                    _uiState.update { it.copy(heartCount = count) }
                }
            }
            launch {
                hearts.observeAntiState(me).collect { anti ->
                    _uiState.update {
                        it.copy(
                            antiRatingCount = anti.total,
                            antiRatingWarning = anti.warning,
                            antiRatingUntilMs = anti.warningUntilMs,
                        )
                    }
                }
            }
        }
    }

    /** Раунд 178: кэш публичного IP - сеть не дёргаем чаще раза в 10 минут. */
    private var lastPublicIp: String? = null
    private var lastPublicIpAt: Long = 0L
    private val publicIpTtlMs: Long = 10L * 60 * 1000

    init {
        observeMyHearts()
        loadSettings()
        // Раунд 177: имя в профиле должно стоять ПЕРВЫМ кадром. Владелец:
        // «быстро переходишь на вкладку профиля - показывает анонимус,
        // а потом подгружает». Одна строка из prefs читается мгновенно,
        // тяжёлая часть (ключ, ссылка, пиры, версии) остаётся в фоне.
        val instantName = runCatching {
            context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .getString("display_name", null)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (instantName != null) {
            _uiState.update { it.copy(displayName = instantName) }
        }
        _uiState.update { it.copy(proxyTunnelEnabled = proxyTunnelEnabled()) }
        // Раунд 249: для окна «Выйти из APU» важно сразу знать, защищён ли
        // профиль никнеймом и паролем - от этого зависит предупреждение.
        _uiState.update {
            it.copy(protectedNick = runCatching { identityBackup.protectedNickname(context) }.getOrNull())
        }
        refreshServerSection()
    }

    /** Раздел «Сервер»: доступность, отклик, состояние копии азбуки. */
    fun refreshServerSection() {
        viewModelScope.launch {
            // Статус сервера: /health с замером отклика.
            val health = runCatching { botApi.pingHealth() }.getOrNull()
            _uiState.update {
                it.copy(
                    serverStatus = when {
                        health == null -> context.getString(R.string.svm_server_unavailable)
                        health < 400 -> "доступен, ответ за ${health} мс"
                        else -> " отвечает с ошибкой ($health)"
                    },
                )
            }
            refreshAddrBookLine()
        }
    }

    private fun refreshAddrBookLine() {
        val at = addressBookBackup.lastBackupAtMs()
        val count = addressBookBackup.localEntryCount()
        val line = buildString {
            append("В телефоне: ${count} адресов. ")
            append(
                if (at > 0) {
                    "Копия на сервере от " + android.text.format.DateFormat.getTimeFormat(context)
                        .format(java.util.Date(at)) + ", " +
                        android.text.format.DateFormat.getDateFormat(context)
                            .format(java.util.Date(at)) + "."
                } else {
                    context.getString(R.string.st_no_server_copy)
                }
            )
            append(" Копия делается сама.")
            // Раунд 126 (владелец): без лимита - просто число сохранивших.
            val fresh = addressBookSwarm.freshAckCount()
            append(" Копий на других телефонах: $fresh.")
        }
        _uiState.update { it.copy(addrBookLine = line) }
    }

    /** Кнопка «Создать копию» (диалог карточки «Сервер»). */
    fun backupAddressBookNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(addrBookMessage = context.getString(R.string.st_saving)) }
            val result = runCatching { addressBookBackup.backupNow() }
                .getOrElse { context.getString(R.string.st_failed_msg, it.message) }
            _uiState.update { it.copy(addrBookMessage = result) }
            refreshAddrBookLine()
        }
    }

    /** Кнопка «Восстановить» (диалог карточки «Сервер»). */
    fun restoreAddressBookNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(addrBookMessage = context.getString(R.string.st_asking_swarm)) }
            val result = runCatching { addressBookBackup.restoreNowForce() }
                .getOrElse { context.getString(R.string.st_failed_msg, it.message) }
            _uiState.update { it.copy(addrBookMessage = result) }
            refreshAddrBookLine()
        }
    }

    /** «Любая сеть»: пользовательский выключатель прокси-туннеля (по умолчанию включён). */
    private fun proxyTunnelEnabled(): Boolean =
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getBoolean("proxy_tunnel_enabled", true)

    /** «Очистка зависших»: остановить все незавершённые отправки и убрать их очереди. */
    fun onCancelStalledTransfers() {
        viewModelScope.launch {
            val cancelled = runCatching { fileTransferRouter.cancelStalledOutgoing() }.getOrDefault(-1)
            val message = if (cancelled >= 0) context.getString(R.string.st_cancelled_n, cancelled) else context.getString(R.string.st_cleanup_failed)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Сколько чужих байт держим для получателей не в сети (этап 7 роя). */
    suspend fun custodyHeldBytes(): Long = fileTransferRouter.custodyHeldBytes()

    /** Освободить место: удалить локальные файлы завершённых передач. */
    fun onPurgeCompletedTransfers() {
        viewModelScope.launch {
            val purged = runCatching { fileTransferRouter.purgeCompletedTransfers() }.getOrDefault(-1)
            val message = if (purged >= 0) context.getString(R.string.st_purged_n, purged) else context.getString(R.string.st_cleanup_failed)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun onProxyTunnelToggle(enabled: Boolean) {
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("proxy_tunnel_enabled", enabled).apply()
        _uiState.update { it.copy(proxyTunnelEnabled = enabled) }
        viewModelScope.launch {
            if (enabled) {
                runCatching { proxyAutopilot.cycle() }
            } else {
                withContext(Dispatchers.IO) { RustBridge.clearMqttSocks5Proxy() }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Каждый вызов уходит в Rust-ядро и ждёт его внутренний замок.
            // Пока движок занят (раздача присутствия, старт), вызов подвисает,
            // а на главном потоке это приводит к «Приложение не отвечает».
            val core = withContext(Dispatchers.IO) {
                Triple(
                    RustBridge.nodeId() ?: "unknown",
                    RustBridge.publicKey() ?: "unknown",
                    RustBridge.networkStatus(),
                )
            }
            val nodeId = core.first
            val pubKey = core.second
            val shortFingerprint = if (pubKey.length > 8)
                pubKey.chunked(4).take(8).joinToString(" ")
            else pubKey

            val statusStr = core.third
            val networkStatus = when (statusStr.lowercase()) {
                "connected"    -> NetworkStatus.Connected
                "connecting"   -> NetworkStatus.Connecting
                "degraded"     -> NetworkStatus.Degraded
                else           -> NetworkStatus.Disconnected
            }

            // Получить актуальную версию приложения
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            // Публичный IP: каким нас видит интернет. Раунд 178 (аудит
            // нагрузки): сетевой запрос - не чаще раза в 10 минут, в
            // остальное время показываем свежий кэш; слабые телефоны и
            // мобильный трафик не дёргаем при каждом открытии профиля.
            val publicIp = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val cached = lastPublicIp
                if (cached != null && now - lastPublicIpAt < publicIpTtlMs) {
                    cached
                } else {
                    runCatching {
                        val conn = java.net.URL("https://api.ipify.org?text=true")
                            .openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.inputStream.bufferedReader().use { it.readText().trim() }
                            .takeIf { it.isNotBlank() }
                    }.getOrNull()?.also {
                        lastPublicIp = it
                        lastPublicIpAt = now
                    } ?: cached
                }
            }

            // Чтение настроек и сборка ссылки трогают диск - тоже в фон.
            val savedName = withContext(Dispatchers.IO) {
                context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                    .getString("display_name", null)
            }
            val displayName = savedName?.takeIf { it.isNotBlank() } ?: "Anonymous"
            val invite = withContext(Dispatchers.IO) {
                OwnInvite.link(context) ?: "p2p://invite/$pubKey"
            }
            val peers = withContext(Dispatchers.IO) { RustBridge.connectedPeers().toInt() }
            val coreInfo = withContext(Dispatchers.IO) { RustBridge.coreBuildInfo() }
            // Ядро присылает одним куском «ядро · сборка · брокеры · MQTT: …».
            // Диагностику MQTT показываем отдельной строкой «Сеть сообщений»,
            // чтобы карточка «Ядро» не превращалась в простыню.
            val mqttSeparator = " · MQTT: "
            val mqttSeparatorAt = coreInfo.indexOf(mqttSeparator)
            val coreLine = if (mqttSeparatorAt >= 0) coreInfo.take(mqttSeparatorAt) else coreInfo
            val mqttLine = if (mqttSeparatorAt >= 0) {
                "MQTT: " + coreInfo.substring(mqttSeparatorAt + mqttSeparator.length)
            } else {
                ""
            }
            // Раунд 190: человекочитаемая версия той же строки. Ядро не
            // трогаем - разбор на стороне приложения; если формат когда-
            // нибудь изменится, вернётся пусто и экран покажет сырую.
            val mqttHuman = MqttLinkText.humanize(mqttLine)

            _uiState.update {
                it.copy(
                    displayName      = displayName,
                    fingerprint      = shortFingerprint,
                    // Та же ссылка, что и во всём приложении: она несёт
                    // подписанный токен, поэтому QR-код из настроек поднимает
                    // ранг так же, как ссылка из раздела рангов. p2p://invite/
                    // остаётся запасным путём, пока узел не создан.
                    inviteLink       = invite,
                    connectionStatus = networkStatus,
                    connectedPeers   = peers,
                    connectionMode   = "P2P / QUIC",
                    appVersion       = appVersion,
                    rustCoreVersion  = coreLine,
                    mqttLink         = mqttLine,
                    mqttHuman        = mqttHuman,
                    publicIp         = publicIp,
                )
            }
        }
    }

    /**
     * Сменить своё имя. Пишем в те же p2p_prefs, откуда его берут движок,
     * ссылка-приглашение и групповые пакеты, поэтому после перезапуска службы
     * новое имя увидят и собеседники.
     */
    fun onDisplayNameChanged(newName: String) {
        val clean = newName.trim().take(50)
        if (clean.isEmpty()) return
        _uiState.update { it.copy(displayName = clean) }
        viewModelScope.launch(Dispatchers.IO) {
            context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("display_name", clean)
                .apply()
            // р246: живой профиль - имя уезжает второму устройству личности.
            com.vladimir.messenger.data.mirror.ProfileMirror.noteLocalChange(
                context, com.vladimir.messenger.data.mirror.ProfileMirror.FIELD_NAME,
            )
        }
        // Ссылка-приглашение несёт имя, поэтому пересобираем её сразу.
        loadSettings()
    }

    fun onRestartEngine() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RustBridge.onNetworkAvailable() }
            loadSettings()
        }
    }

    fun onTriggerGossipDiscovery() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastGossipTrigger
        
        // ащита от быстрых нажатий (debounce 5 секунд)
        if (elapsed < 5000L) {
            val waitSec = ((5000L - elapsed) / 1000.0).toInt() + 1
            Toast.makeText(context, context.getString(R.string.toast_wait_seconds, waitSec), Toast.LENGTH_SHORT).show()
            return
        }
        
        lastGossipTrigger = now
        Toast.makeText(context, context.getString(R.string.toast_collecting_peers), Toast.LENGTH_SHORT).show()
        
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { RustBridge.triggerGossipDiscovery() }
            android.util.Log.i("SettingsVM", "Gossip trigger result: $ok")
            if (ok) {
                Toast.makeText(context, context.getString(R.string.toast_gossip_started), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.toast_gossip_failed), Toast.LENGTH_SHORT).show()
            }
            // ерезагрузить UI чтобы показать обновлённое количество пиров
            loadSettings()
        }
    }

}
