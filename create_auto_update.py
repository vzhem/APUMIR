# -*- coding: utf-8 -*-
import os

BASE = r"C:\APUMIR\p2p-messenger"

# ========== 1. GitHub Actions workflow ==========
GITHUB_DIR = os.path.join(BASE, ".github", "workflows")
os.makedirs(GITHUB_DIR, exist_ok=True)
WORKFLOW_FILE = os.path.join(GITHUB_DIR, "build-release.yml")

workflow_content = r'''name: Build Release APK

on:
  push:
    tags:
      - 'v*'  # Запускать при создании тега вида v1.0.0, v6.3.0 и т.д.

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
      
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
      
      - name: Grant execute permission for gradlew
        run: chmod +x ./android-app/gradlew
      
      - name: Build Release APK
        working-directory: ./android-app
        run: ./gradlew :app:assembleRelease --no-daemon
      
      - name: Get version from tag
        id: get_version
        run: echo "VERSION=${GITHUB_REF#refs/tags/}" >> $GITHUB_OUTPUT
      
      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          files: android-app/app/build/outputs/apk/release/app-release-unsigned.apk
          name: Release ${{ steps.get_version.outputs.VERSION }}
          body: |
            ## Что нового в ${{ steps.get_version.outputs.VERSION }}
            
            Автоматическая сборка из тега ${{ steps.get_version.outputs.VERSION }}
            
            ### Установка
            1. Скачайте `app-release-unsigned.apk`
            2. Разрешите установку из неизвестных источников
            3. Установите APK
          draft: false
          prerelease: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
'''

with open(WORKFLOW_FILE, "w", encoding="utf-8") as f:
    f.write(workflow_content)
print(f"OK: created {WORKFLOW_FILE}")

# ========== 2. UpdateChecker.kt ==========
UPDATE_CHECKER_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                                   "com", "vladimir", "messenger", "service", "UpdateChecker.kt")

update_checker_content = r'''package com.vladimir.messenger.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверяет наличие обновлений через GitHub Releases API.
 * Скачивает APK через DownloadManager.
 * Запускает установку через Intent.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API = "https://api.github.com/repos"
        private const val HTTP_TIMEOUT = 10000
        
        // TODO: заменить на реальные значения
        private const val GITHUB_USER = "your-username"
        private const val GITHUB_REPO = "p2p-messenger"
    }

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String,
    )

    /**
     * Проверить наличие новой версии.
     * @return ReleaseInfo если есть обновление, null если текущая версия актуальна
     */
    suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Checking for updates. Current: $currentVersion")
            
            val url = "$GITHUB_API/$GITHUB_USER/$GITHUB_REPO/releases/latest"
            val response = httpGet(url) ?: return@withContext null
            
            val json = JSONObject(response)
            val latestVersion = json.getString("tag_name")
            
            Log.i(TAG, "Latest version: $latestVersion")
            
            // Сравниваем версии (простое сравнение строк)
            if (isVersionNewer(currentVersion, latestVersion)) {
                Log.i(TAG, "New version available: $latestVersion")
                
                // Найти APK asset
                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                
                if (downloadUrl == null) {
                    Log.w(TAG, "No APK found in release")
                    return@withContext null
                }
                
                ReleaseInfo(
                    version = latestVersion,
                    downloadUrl = downloadUrl,
                    releaseNotes = json.optString("body", ""),
                    publishedAt = json.optString("published_at", "")
                )
            } else {
                Log.i(TAG, "Current version is up to date")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check for update failed", e)
            null
        }
    }

    /**
     * Скачать APK через DownloadManager.
     * @return ID загрузки
     */
    fun downloadApk(releaseInfo: ReleaseInfo): Long {
        val request = DownloadManager.Request(Uri.parse(releaseInfo.downloadUrl)).apply {
            setTitle("P2P Messenger Update")
            setDescription("Скачивание версии ${releaseInfo.version}")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "p2p-messenger-${releaseInfo.version}.apk")
        }
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        
        Log.i(TAG, "Download started: ID=$downloadId")
        return downloadId
    }

    /**
     * Установить скачанный APK.
     */
    fun installApk(downloadId: Long) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = downloadManager.getUriForDownloadedFile(downloadId)
        
        if (uri == null) {
            Log.e(TAG, "Cannot get URI for downloaded file")
            return
        }
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        
        context.startActivity(intent)
        Log.i(TAG, "Install intent started")
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        // Убираем 'v' если есть
        val c = current.removePrefix("v")
        val l = latest.removePrefix("v")
        
        // Простое сравнение: если строки разные и latest > current
        return l != c && l > c
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "P2P-Messenger-Update-Checker")
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        
        return try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "HTTP GET $url returned $code")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP GET failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
'''

with open(UPDATE_CHECKER_FILE, "w", encoding="utf-8") as f:
    f.write(update_checker_content)
print(f"OK: created UpdateChecker.kt")

# ========== 3. UpdateViewModel.kt ==========
UPDATE_VM_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                              "com", "vladimir", "messenger", "ui", "update", "UpdateViewModel.kt")

os.makedirs(os.path.dirname(UPDATE_VM_FILE), exist_ok=True)

update_vm_content = r'''package com.vladimir.messenger.ui.update

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.service.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val isChecking: Boolean = false,
    val updateAvailable: UpdateChecker.ReleaseInfo? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    application: Application,
    private val updateChecker: UpdateChecker,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    init {
        registerDownloadReceiver()
    }

    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null) }
            
            val release = updateChecker.checkForUpdate(currentVersion)
            
            _uiState.update { 
                it.copy(
                    isChecking = false,
                    updateAvailable = release
                )
            }
        }
    }

    fun downloadUpdate() {
        val release = _uiState.value.updateAvailable ?: return
        
        _uiState.update { it.copy(isDownloading = true) }
        
        downloadId = updateChecker.downloadApk(release)
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(updateAvailable = null) }
    }

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    _uiState.update { it.copy(isDownloading = false) }
                    updateChecker.installApk(downloadId)
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        getApplication<Application>().registerReceiver(downloadReceiver, filter)
    }

    override fun onCleared() {
        super.onCleared()
        downloadReceiver?.let {
            getApplication<Application>().unregisterReceiver(it)
        }
    }
}
'''

with open(UPDATE_VM_FILE, "w", encoding="utf-8") as f:
    f.write(update_vm_content)
print(f"OK: created UpdateViewModel.kt")

# ========== 4. UpdateDialog.kt ==========
UPDATE_DIALOG_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                                  "com", "vladimir", "messenger", "ui", "update", "UpdateDialog.kt")

update_dialog_content = r'''package com.vladimir.messenger.ui.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UpdateDialog(
    releaseInfo: com.vladimir.messenger.service.UpdateChecker.ReleaseInfo,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismissClick() },
        title = {
            Text(
                "Доступно обновление",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Версия: ${releaseInfo.version}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                if (releaseInfo.releaseNotes.isNotBlank()) {
                    Text(
                        "Что нового:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        releaseInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                if (isDownloading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Скачивание...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownloadClick,
                enabled = !isDownloading
            ) {
                Text(if (isDownloading) "Скачивается..." else "Обновить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissClick,
                enabled = !isDownloading
            ) {
                Text("Позже")
            }
        }
    )
}
'''

with open(UPDATE_DIALOG_FILE, "w", encoding="utf-8") as f:
    f.write(update_dialog_content)
print(f"OK: created UpdateDialog.kt")

# ========== 5. Добавить проверку обновлений в MainActivity ==========
MAIN_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "MainActivity.kt")

with open(MAIN_FILE, "r", encoding="utf-8") as f:
    content = f.read()

# Добавить импорты
imports_to_add = """import com.vladimir.messenger.service.UpdateChecker
import com.vladimir.messenger.ui.update.UpdateDialog
import com.vladimir.messenger.ui.update.UpdateViewModel"""

for imp in imports_to_add.split("\n"):
    if imp.strip() and imp.strip() not in content:
        content = content.replace(
            "import com.vladimir.messenger.service.BotApi",
            f"{imp.strip()}\nimport com.vladimir.messenger.service.BotApi"
        )

# Добавить UpdateViewModel
if "updateViewModel: UpdateViewModel" not in content:
    content = content.replace(
        "private val viewModel: MainViewModel by viewModels()",
        "private val viewModel: MainViewModel by viewModels()\n    private val updateViewModel: UpdateViewModel by viewModels()"
    )
    print("OK: added UpdateViewModel")

# Добавить проверку обновлений в onCreate
if "checkForUpdate" not in content:
    check_code = '''
        // Проверка обновлений (раз в день)
        val prefs = getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        
        if (now - lastCheck > oneDayMs) {
            val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "v0.0.0"
            updateViewModel.checkForUpdate(currentVersion)
            prefs.edit().putLong("last_update_check", now).apply()
        }
'''
    content = content.replace(
        "setContent {",
        check_code + "\n        setContent {"
    )
    print("OK: added update check in onCreate")

# Добавить UpdateDialog в setContent
if "UpdateDialog" not in content:
    dialog_code = '''
                // Диалог обновления
                val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
                updateState.updateAvailable?.let { release ->
                    UpdateDialog(
                        releaseInfo = release,
                        isDownloading = updateState.isDownloading,
                        onDownloadClick = { updateViewModel.downloadUpdate() },
                        onDismissClick = { updateViewModel.dismissUpdate() }
                    )
                }
'''
    content = content.replace(
        "P2PMessengerTheme {",
        "P2PMessengerTheme {\n" + dialog_code
    )
    print("OK: added UpdateDialog to setContent")

with open(MAIN_FILE, "w", encoding="utf-8") as f:
    f.write(content)

# ========== 6. Добавить REQUEST_INSTALL_PACKAGES в AndroidManifest ==========
MANIFEST_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "AndroidManifest.xml")

with open(MANIFEST_FILE, "r", encoding="utf-8") as f:
    manifest = f.read()

if "REQUEST_INSTALL_PACKAGES" not in manifest:
    manifest = manifest.replace(
        "<!-- РЈРІРµРґРѕРјР»РµРЅРёСЏ (Android 13+) -->",
        """<!-- Установка APK -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

    <!-- РЈРІРµРґРѕРјР»РµРЅРёСЏ (Android 13+) -->"""
    )
    print("OK: added REQUEST_INSTALL_PACKAGES permission")

with open(MANIFEST_FILE, "w", encoding="utf-8") as f:
    f.write(manifest)

print("\nDone. Build.")