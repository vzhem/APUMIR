package com.vladimir.messenger

// =============================================================================
// MAINACTIVITY.KT — Точка входа UI
// =============================================================================
// Единственная Activity в приложении (Single-Activity Architecture).
// Вся навигация — через NavGraph внутри Compose.
//
// Задачи:
//   1. Запуск Foreground Service (Rust ядро)
//   2. Определение стартового экрана (онбординг или чаты)
//   3. Запрос разрешений (уведомления, батарея)
//   4. Установка Compose контента
// =============================================================================

import android.Manifest
import android.content.Intent
import android.content.Context
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.launch
import dagger.hilt.components.SingletonComponent
import dagger.hilt.InstallIn
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.service.UpdateChecker
import com.vladimir.messenger.ui.update.UpdateDialog
import com.vladimir.messenger.ui.update.UpdateViewModel
import com.vladimir.messenger.service.BotApi
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.service.CoreServerService
import com.vladimir.messenger.ui.navigation.MessengerNavGraph
import com.vladimir.messenger.ui.navigation.Screen
import com.vladimir.messenger.ui.theme.P2PMessengerTheme
import dagger.hilt.android.AndroidEntryPoint

// @AndroidEntryPoint: Hilt может инжектировать зависимости в эту Activity

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MainActivityEntryPoint {
    fun botApi(): BotApi
    fun contactRepository(): ContactRepository
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i("MainActivity", "Notification permission granted")
        } else {
            Log.w("MainActivity", "Notification permission denied")
        }
    }

    private var pendingContactInfo by androidx.compose.runtime.mutableStateOf<Triple<String, String, String>?>(null)  // (nodeId, publicKey, displayName)

    override fun onResume() {
        super.onResume()
        handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri == null) return
        
        Log.i("MainActivity", "=== DEEP LINK: $uri ===")
        
        // Поддержка двух схем:
        // 1. https://t.me/p2p_messenger_relay_bot?start=NODE_ID
        // 2. p2pmessenger://add?node_id=NODE_ID
        
        var nodeId: String? = null
        
        if (uri.scheme == "https" && uri.host == "t.me" && 
            uri.path?.startsWith("/p2p_messenger_relay_bot") == true) {
            nodeId = uri.getQueryParameter("start")
            Log.i("MainActivity", "Parsed from t.me: nodeId=$nodeId")
        }
        else if (uri.scheme == "p2pmessenger" && uri.host == "add") {
            nodeId = uri.getQueryParameter("node_id")
            Log.i("MainActivity", "Parsed from custom scheme: nodeId=$nodeId")
        }
        
        if (nodeId.isNullOrBlank()) {
            Log.w("MainActivity", "No node_id in deep link, ignoring")
            intent.data = null
            return
        }
        
        Log.i("MainActivity", "Looking up node: $nodeId")
        
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            MainActivityEntryPoint::class.java
        )
        val botApi = entryPoint.botApi()
        val contactRepo = entryPoint.contactRepository()
        
        lifecycleScope.launch {
            try {
                val info = botApi.lookupNode(nodeId)
                Log.i("MainActivity", "Lookup result: ${if (info != null) "FOUND ${info.displayName}" else "NOT FOUND"}")
                
                if (info != null) {
                    Log.i("MainActivity", "Setting pendingContactInfo: ${info.displayName}")
                    runOnUiThread {
                        pendingContactInfo = Triple(info.nodeId, info.publicKey, info.displayName)
                        Log.i("MainActivity", "pendingContactInfo SET - dialog should render")
                    }
                } else {
                    Log.w("MainActivity", "Node not found in registry: $nodeId")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Lookup failed", e)
            }
        }
        
        intent.data = null
    }


    // ViewModel для определения стартового экрана
    private val viewModel: MainViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    // Запрос разрешения на уведомления (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.i("MainActivity", "Notification permission granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: контент рисуется под системными барами
        enableEdgeToEdge()

        // Запрашиваем разрешение на уведомления (Android 13+)
        requestNotificationPermission()
        requestIgnoreBatteryOptimizations()
        // Запрос на отключение battery optimization (для push в Doze mode)


        // Запускаем Foreground Service с Rust ядром
        startCoreService()

        
        // Запросить разрешение на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        
        // Обработка deep link (открытие чата из notification)
        val chatId = intent.getStringExtra("extra_chat_id")
        if (chatId != null) {
            Log.i("MainActivity", "Opening chat from notification: $chatId")
            // TODO: передать chatId в NavGraph для навигации
            // Пока просто логируем
        }

        
        // Проверка обновлений (раз в день)
        val prefs = getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        
        if (true) {  // проверять при каждом запуске
            try {
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "v0.0.0"
                updateViewModel.checkForUpdate(currentVersion)
                prefs.edit().putLong("last_update_check", now).apply()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Update check failed", e)
            }
        }

        setContent {
            P2PMessengerTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                // Ждём пока ViewModel определит стартовый экран
                if (!uiState.isLoading) {
                    
                // Диалог подтверждения добавления контакта из deep link
                val pendingContact = pendingContactInfo
                Log.d("MainActivity", "Checking dialog: pendingContact=${pendingContact != null}")
                if (pendingContact != null) {
                    Log.i("MainActivity", "Rendering dialog for: ${pendingContact.third}")
                    AlertDialog(
                        onDismissRequest = { pendingContactInfo = null },
                        title = { androidx.compose.material3.Text("Добавить контакт?") },
                        text = { 
                            androidx.compose.material3.Text(
                                "Найдено: ${pendingContact.third}\n\nДобавить в список контактов?"
                            ) 
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val (nodeId, publicKey, displayName) = pendingContact
                                pendingContactInfo = null
                                
                                val entryPoint = EntryPointAccessors.fromApplication(
                                    applicationContext,
                                    MainActivityEntryPoint::class.java
                                )
                                val contactRepo = entryPoint.contactRepository()
                                
                                lifecycleScope.launch {
                                    val result = contactRepo.addContact(displayName, nodeId)
                                    if (result.isSuccess) {
                                        Log.i("MainActivity", "Contact added: $displayName")
                                    } else {
                                        Log.e("MainActivity", "Failed to add contact: ${result.exceptionOrNull()?.message}")
                                    }
                                }
                            }) {
                                androidx.compose.material3.Text("Добавить")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingContactInfo = null }) {
                                androidx.compose.material3.Text("Отмена")
                            }
                        }
                    )
                }

                // Диалог обновления
                val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
                Log.d("MainActivity", "UpdateDialog check: isChecking=${updateState.isChecking}, updateAvailable=${updateState.updateAvailable != null}")
                updateState.updateAvailable?.let { release ->
                    Log.i("MainActivity", "Rendering UpdateDialog for ${release.version}")
                    UpdateDialog(
                        releaseInfo = release,
                        isDownloading = updateState.isDownloading,
                        onDownloadClick = { updateViewModel.downloadUpdate() },
                        onDismissClick = { updateViewModel.dismissUpdate() }
                    )
                }

                MessengerNavGraph(
                        startDestination = if (uiState.hasIdentity)
                            Screen.ChatList.route
                        else
                            Screen.Onboarding.route,
                    )
                }
            }
        }
    }

    // Запуск CoreServerService как Foreground Service
    private fun startCoreService() {
        val intent = Intent(this, CoreServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // Запрос разрешения на уведомления
    
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Показываем системный диалог запроса
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Log.i("MainActivity", "Requested ignore battery optimizations")
            } else {
                Log.d("MainActivity", "Already ignoring battery optimizations")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to request battery optimization exemption", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}