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
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.service.CoreServerService
import com.vladimir.messenger.service.UpdateChecker
import com.vladimir.messenger.ui.update.UpdateDialog
import com.vladimir.messenger.MainViewModel
import com.vladimir.messenger.ui.navigation.Screen
import com.vladimir.messenger.ui.navigation.MessengerNavGraph
import com.vladimir.messenger.ui.theme.P2PMessengerTheme
import com.vladimir.messenger.ui.theme.ThemeModeHolder
import com.vladimir.messenger.ui.theme.WallpaperHolder
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
import com.vladimir.messenger.data.referral.PendingReferralStore
import com.vladimir.messenger.util.InviteLinkParser
import com.vladimir.messenger.util.VerifiedReferralInviteLink

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

    /** Ссылка-приглашение в группу: сначала спрашиваем разрешение, потом ведём в «Группы». */
    private var pendingGroupInviteLink by mutableStateOf<String?>(null)
    private var pendingGroupInvite by mutableStateOf<String?>(null)
    private var updateRelease by mutableStateOf<UpdateChecker.ReleaseInfo?>(null)
    private var lastHandledInviteUri: String? = null
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
        val uri: Uri = intent?.data ?: return
        val rawUri = uri.toString()
        if (rawUri == lastHandledInviteUri) return

        // Ссылка-приглашение в группу. Разбираем ДО личных приглашений: у них
        // общая схема p2pmessenger://, различается только host (add / group).
        val groupInvite = GroupInviteLinks.parseTarget(rawUri)
        if (groupInvite != null) {
            lastHandledInviteUri = rawUri
            pendingGroupInviteLink = rawUri
            Log.i("MainActivity", "Group invite link accepted")
            return
        }

        val verifiedReferral = VerifiedReferralInviteLink.verify(rawUri)
        if (verifiedReferral != null) {
            val pending = PendingReferralStore.saveVerified(applicationContext, verifiedReferral.token)
            if (pending == null) {
                Log.w("MainActivity", "Verified referral could not be persisted")
                return
            }
            lastHandledInviteUri = rawUri
            Log.i("MainActivity", "Verified referral link accepted")
            resolveInviteContact(pending.inviterNodeId, null, null)
            return
        }

        val invite = InviteLinkParser.parse(rawUri)
        if (invite == null) {
            Log.w("MainActivity", "Unsupported or invalid invite link")
            return
        }

        lastHandledInviteUri = rawUri
        Log.i("MainActivity", "Legacy contact-only invite accepted")
        resolveInviteContact(invite.nodeId, invite.publicKey, invite.displayName)
    }

    private fun resolveInviteContact(nodeId: String, publicKey: String?, displayName: String?) {
        val fallbackName = displayName ?: "Contact ${nodeId.takeLast(6)}"
        pendingContactInfo = Triple(nodeId, publicKey ?: nodeId, fallbackName)

        // Best effort enrichment: registry data can improve display only. It cannot
        // create or upgrade referral attribution, which remains tied to the signed token.
        lifecycleScope.launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext,
                    MainActivityEntryPoint::class.java
                )
                val nodeInfo = entryPoint.botApi().lookupNode(nodeId)
                if (nodeInfo != null) {
                    Log.i("MainActivity", "Invite registry lookup OK")
                    pendingContactInfo = Triple(
                        nodeInfo.nodeId,
                        nodeInfo.publicKey.ifBlank { publicKey ?: nodeInfo.nodeId },
                        nodeInfo.displayName.ifBlank { fallbackName }
                    )
                } else {
                    Log.i("MainActivity", "Invite registry lookup returned no data")
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Invite registry lookup failed: ${e.javaClass.simpleName}")
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

        ThemeModeHolder.init(this)
        WallpaperHolder.init(this)
        setContent {
            val themeMode by ThemeModeHolder.mode.collectAsStateWithLifecycle()
            P2PMessengerTheme(themeMode = themeMode) {
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

                val pendingGroupLink = pendingGroupInviteLink
                if (pendingGroupLink != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingGroupInviteLink = null },
                        title = { androidx.compose.material3.Text("Войти в группу?") },
                        text = {
                            androidx.compose.material3.Text(
                                "Открыта ссылка-приглашение в группу. " +
                                    "Отправить заявку на вступление?"
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                pendingGroupInviteLink = null
                                pendingGroupInvite = pendingGroupLink
                            }) {
                                androidx.compose.material3.Text("Войти")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { pendingGroupInviteLink = null }) {
                                androidx.compose.material3.Text("Отмена")
                            }
                        }
                    )
                }

                // иалог обновления
                val currentUpdate = updateRelease
                if (currentUpdate != null) {
                    UpdateDialog(
                        releaseInfo = currentUpdate,
                        isDownloading = false,
                        onDownloadClick = {
                            val entryPoint = EntryPointAccessors.fromApplication(
                                applicationContext,
                                MainActivityEntryPoint::class.java
                            )
                            val checker = entryPoint.updateChecker()
                            checker.downloadApk(currentUpdate)
                            updateRelease = null
                            
                            // Показать подсказку "откройте Downloads"
                            android.widget.Toast.makeText(
                                applicationContext,
                                "Скачивание началось. После завершения откройте Downloads для установки.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        },
                        onDismissClick = { updateRelease = null }
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
                            Screen.Onboarding.route,
                        initialGroupInvite = pendingGroupInvite,
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
                    updateRelease = release
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
