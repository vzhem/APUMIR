package com.vladimir.messenger.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.RustBridge
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
    val proxyTunnelEnabled: Boolean = true,
    /** Сколько сердечек набрал мой профиль. */
    val heartCount: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proxyAutopilot: com.vladimir.messenger.service.ProxyAutopilot,
    private val fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter,
    private val hearts: com.vladimir.messenger.data.heart.HeartRepository,
    private val apkSeeder: com.vladimir.messenger.data.update.ApkSeeder,
    private val updateChecker: com.vladimir.messenger.service.UpdateChecker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    private var lastGossipTrigger: Long = 0L
    val uiState = _uiState.asStateFlow()

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
                }.getOrNull() ?: return@withContext quick.copy(error = "Не удалось открыть файл")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext quick.copy(error = "Не удалось открыть файл")
                    // Версия — из самого APK; если архив не читается — из имени.
                    val version = runCatching {
                        context.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)?.versionName
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: com.vladimir.messenger.data.update.ApkUpdate.versionFromName(quick.displayName)
                    quick.copy(version = version, tempPath = temp.absolutePath)
                } catch (error: Exception) {
                    temp.delete()
                    quick.copy(error = "Ошибка чтения: ${error.message}")
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
                .getOrElse { "Ошибка: ${it.message}" }
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

    /** Установить скачанное обновление (системный диалог). Ошибка — тостом. */
    fun onInstallUpdate() {
        viewModelScope.launch {
            val result = runCatching { apkSeeder.installReady() }
                .getOrElse { "Не удалось запустить установку: ${it.message}" }
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
            val release = runCatching { updateChecker.checkForUpdate(currentVersion) }.getOrNull()
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
                    "Соседи раздают новую версию — кнопка «Скачать» в карточке"
                asked > 0 -> "Спрошено у $asked соседей; новых объявлений пока нет"
                else -> "Обновлений не найдено (соседей в сети нет или они на этой же версии)"
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
            .onSuccess {
                android.widget.Toast.makeText(
                    context,
                    "Скачивание началось. Когда файл скачается, он сам появится в «Обновлениях» — " +
                        "установка и раздача соседям начнутся сами",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            .onFailure {
                android.widget.Toast.makeText(context, "Не удалось начать скачивание: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
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
            hearts.observeCount(me).collect { count ->
                _uiState.update { it.copy(heartCount = count) }
            }
        }
    }

    init {
        observeMyHearts()
        loadSettings()
        _uiState.update { it.copy(proxyTunnelEnabled = proxyTunnelEnabled()) }
    }

    /** «Любая сеть»: пользовательский выключатель прокси-туннеля (по умолчанию включён). */
    private fun proxyTunnelEnabled(): Boolean =
        context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getBoolean("proxy_tunnel_enabled", true)

    /** «Очистка зависших»: остановить все незавершённые отправки и убрать их очереди. */
    fun onCancelStalledTransfers() {
        viewModelScope.launch {
            val cancelled = runCatching { fileTransferRouter.cancelStalledOutgoing() }.getOrDefault(-1)
            val message = if (cancelled >= 0) "Остановлено зависших отправок: $cancelled" else "Не удалось выполнить очистку"
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Сколько чужих байт держим для получателей не в сети (этап 7 роя). */
    suspend fun custodyHeldBytes(): Long = fileTransferRouter.custodyHeldBytes()

    /** Освободить место: удалить локальные файлы завершённых передач. */
    fun onPurgeCompletedTransfers() {
        viewModelScope.launch {
            val purged = runCatching { fileTransferRouter.purgeCompletedTransfers() }.getOrDefault(-1)
            val message = if (purged >= 0) "Очищено завершённых передач: $purged" else "Не удалось выполнить очистку"
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

            // Публичный IP: каким нас видит интернет. Определяем внешним
            // сервисом; без сети честно пишем, что недоступен.
            val publicIp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL("https://api.ipify.org?text=true")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.inputStream.bufferedReader().use { it.readText().trim() }
                        .takeIf { it.isNotBlank() }
                }.getOrNull()
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
            Toast.makeText(context, "одождите $waitSec сек перед следующим запросом", Toast.LENGTH_SHORT).show()
            return
        }
        
        lastGossipTrigger = now
        Toast.makeText(context, "Собираю данные об абонентах...", Toast.LENGTH_SHORT).show()
        
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { RustBridge.triggerGossipDiscovery() }
            android.util.Log.i("SettingsVM", "Gossip trigger result: $ok")
            if (ok) {
                Toast.makeText(context, "Gossip запущен", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "шибка запуска gossip", Toast.LENGTH_SHORT).show()
            }
            // ерезагрузить UI чтобы показать обновлённое количество пиров
            loadSettings()
        }
    }

}
