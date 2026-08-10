package com.vladimir.messenger

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.vladimir.messenger.service.UpdateChecker
import com.vladimir.messenger.MainViewModel
import com.vladimir.messenger.ui.navigation.Screen
import com.vladimir.messenger.ui.navigation.MessengerNavGraph
import com.vladimir.messenger.ui.theme.P2PMessengerTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.service.BotApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MainActivityEntryPoint {
    fun botApi(): BotApi
    fun contactRepository(): ContactRepository
    fun updateChecker(): UpdateChecker
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

    private var pendingContactInfo by mutableStateOf<Triple<String, String, String>?>(null)
    private val viewModel: MainViewModel by viewModels()

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

        val scheme = uri.scheme ?: return
        val host = uri.host ?: ""

        Log.i("MainActivity", "Deep link received: $uri")

        when {
            scheme == "p2pmessenger" && host == "add" -> {
                val nodeId = uri.getQueryParameter("node_id") ?: uri.getQueryParameter("nodeId") ?: return
                val publicKey = uri.getQueryParameter("public_key") ?: uri.getQueryParameter("publicKey") ?: nodeId
                val displayName = uri.getQueryParameter("name") ?: "Contact ${nodeId.takeLast(6)}"
                pendingContactInfo = Triple(nodeId, publicKey, displayName)
            }
            scheme == "https" && host == "t.me" -> {
                // t.me бот ссылки
                val path = uri.path?.removePrefix("/") ?: ""
                if (path.startsWith("P2PMessengerBot")) {
                    val startParam = uri.getQueryParameter("start") ?: return
                    if (startParam.startsWith("add_")) {
                        val nodeId = startParam.removePrefix("add_")
                        pendingContactInfo = Triple(nodeId, nodeId, "Contact ${nodeId.takeLast(6)}")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()
        requestIgnoreBatteryOptimizations()
        startCoreService()
        checkForUpdates()

        setContent {
            P2PMessengerTheme {
                val pendingContact = pendingContactInfo
                Log.d("MainActivity", "Checking dialog: pendingContact=${pendingContact != null}")
                
                if (pendingContact != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingContactInfo = null },
                        title = { androidx.compose.material3.Text("Добавить контакт?") },
                        text = {
                            androidx.compose.material3.Text(
                                "Найдено: ${pendingContact.third}\n\nДобавить в список контактов?"
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
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
                            androidx.compose.material3.TextButton(onClick = { pendingContactInfo = null }) {
                                androidx.compose.material3.Text("Отмена")
                            }
                        }
                    )
                }

                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                if (uiState.isLoading) {
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else {
                    MessengerNavGraph(
                        startDestination = if (uiState.hasIdentity)
                            Screen.ChatList.route
                        else
                            Screen.Onboarding.route
                    )
                }
            }
        }
    }

    private fun startCoreService() {
        val intent = Intent(this, CoreServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
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


    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext,
                    MainActivityEntryPoint::class.java
                )
                val updateChecker = entryPoint.updateChecker()
                val appVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "v0.0.0"
                    } catch (_: Exception) { "v0.0.0" }
                    val release = updateChecker.checkForUpdate(appVersion)
                if (release != null) {
                    Log.i("MainActivity", "New version available: ${release.version}")
                    // TODO: Show update dialog
                } else {
                    Log.d("MainActivity", "App is up to date")
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Update check failed: ${e.message}")
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
