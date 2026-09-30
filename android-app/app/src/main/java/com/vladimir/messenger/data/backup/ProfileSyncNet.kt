package com.vladimir.messenger.data.backup

// =============================================================================
// PROFILESYNCNET.KT — «Профили находят себя сами»: релейная передача копии
// =============================================================================
// Раунд 225 (владелец: «профили находили себя сами и сами синхронизировались;
// синхронизация через любые мобильные сети»).
//
// Как устройства находят друг друга БЕЗ ввода адресов:
//  - Оба устройства знают один НИК («Защита личности»). Полка в релее
//    адресуется отпечатком ника, поэтому второе устройство ищет копию само.
//  - Код забора выводится из ПАРОЛЯ копии на самом телефоне - вводить код
//    никуда не нужно, пароль по сети не ходит.
//
// Безопасность: по сети идут только зашифрованные байты (архив .apubak заперт
// паролем человека). Релей - передача, а не хранилище: копия стирается сразу
// после забора и в любом случае через сутки. Работает в любых сетях, где есть
// интернет, - Wi-Fi и мобильные.
// =============================================================================

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.GroupInviteLinks
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ProfileSyncNet {

    private const val TAG = "ProfileSyncNet"
    private const val HTTP_TIMEOUT = 30_000
    private const val READ_TIMEOUT = 120_000

    /** Лимит релея: 24 МБ base64 (копия без медиа обычно 1-5 МБ). */
    const val MAX_B64_CHARS = 24_000_000

    /** Итог ручной отправки. */
    sealed interface UploadResult {
        data class Ok(val bytes: Long) : UploadResult
        data object NoIdentity : UploadResult
        data object BadPassword : UploadResult
        data class TooBig(val megaBytes: Int) : UploadResult
        data class Failed(val reason: String) : UploadResult
    }

    /** Метка «в сети есть копия» (без скачивания). */
    data class NetMeta(
        val timeMs: Long,
        val sizeBytes: Long,
    )

    sealed interface FetchResult {
        /** Копия скачана (файл .apubak для [ProfileBackup.stage]). */
        data class Ready(val file: File) : FetchResult
        data object NotFound : FetchResult
        data object WrongPassword : FetchResult
        data class Failed(val reason: String) : FetchResult
    }

    private fun baseUrl() = "https://" + GroupInviteLinks.WEB_HOST + "/psync/"

    /** Полка: отпечаток ника (одинаков на обоих устройствах с тем же ником). */
    fun slot(nick: String): String? {
        val clean = nick.trim().removePrefix("@").lowercase()
        if (clean.isEmpty()) return null
        return sha256("apu-sync-slot-v1|" + clean).take(32)
    }

    /** Код забора выводится из пароля - его не нужно никуда переписывать. */
    fun code(nick: String, password: CharArray): String? {
        val clean = nick.trim().removePrefix("@").lowercase()
        if (clean.isEmpty() || password.size < BackupCipher.MIN_PASSWORD_LENGTH) return null
        return sha256("apu-sync-code-v1|" + clean + "|" + String(password)).take(16)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }

    private fun nickOf(context: Context): String =
        context.applicationContext
            .getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getString("my_username", "") ?: ""

    // ── Отправка ────────────────────────────────────────────────────────────

    /**
     * Собрать копию этого устройства и положить в релей. Долго - звать не с
     * главного потока. Пароль после вызова обнуляется.
     */
    fun uploadBlocking(context: Context, backup: ProfileBackup, password: CharArray): UploadResult {
        val nick = nickOf(context)
        val slot = slot(nick) ?: return UploadResult.NoIdentity
        val code = code(nick, password) ?: return UploadResult.BadPassword
        val temp = File(context.applicationContext.noBackupFilesDir, "profile_net_upload.apubak")
        temp.delete()
        val created = backup.createFile(temp, password, includeReceived = false)
        if (created !is ProfileBackup.CreateResult.Success) {
            temp.delete()
            return when (created) {
                ProfileBackup.CreateResult.NoIdentity -> UploadResult.NoIdentity
                ProfileBackup.CreateResult.BadPassword -> UploadResult.BadPassword
                is ProfileBackup.CreateResult.Failed -> UploadResult.Failed(created.reason)
                else -> UploadResult.Failed("копия не собралась")
            }
        }
        val bytes = temp.readBytes()
        temp.delete()
        if (bytes.isEmpty()) return UploadResult.Failed("копия пуста")
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (b64.length > MAX_B64_CHARS) {
            return UploadResult.TooBig((bytes.size / (1024L * 1024L)).toInt() + 1)
        }
        val conn = (URL(baseUrl() + slot).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
            setRequestProperty("X-Apu-Check", sha256(code))
            connectTimeout = HTTP_TIMEOUT
            readTimeout = READ_TIMEOUT
        }
        try {
            conn.outputStream.use { it.write(b64.toByteArray(Charsets.US_ASCII)) }
            val resp = conn.responseCode
            if (resp !in 200..299) return UploadResult.Failed("сервис ответил $resp")
        } catch (e: IOException) {
            return UploadResult.Failed(e.message ?: "сеть недоступна")
        } finally {
            conn.disconnect()
        }
        return UploadResult.Ok(bytes.size.toLong())
    }

    /** Есть ли копия в релее (без скачивания). */
    suspend fun meta(context: Context): NetMeta? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val slot = slot(nickOf(context)) ?: return@withContext null
            try {
                val conn = (URL(baseUrl() + slot + "?meta=1").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = HTTP_TIMEOUT
                    readTimeout = HTTP_TIMEOUT
                }
                try {
                    if (conn.responseCode != 200) return@withContext null
                    val json = org.json.JSONObject(
                        conn.inputStream.bufferedReader().use { it.readText() }
                    )
                    if (!json.optBoolean("exists", false)) null
                    else NetMeta(json.optLong("time", 0L), json.optLong("size", 0L))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "meta failed: ${e.message}")
                null
            }
        }

    /** Скачать копию из релея. Код выводится из пароля. */
    suspend fun fetch(context: Context, password: CharArray): FetchResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val nick = nickOf(context)
            val slot = slot(nick) ?: return@withContext FetchResult.Failed("задайте ник в «Защите личности»")
            val code = code(nick, password) ?: return@withContext FetchResult.WrongPassword
            val conn = (URL(baseUrl() + slot).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-Apu-Code", code)
                connectTimeout = HTTP_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            try {
                when (conn.responseCode) {
                    404 -> return@withContext FetchResult.NotFound
                    401, 403 -> return@withContext FetchResult.WrongPassword
                    !in 200..299 -> return@withContext FetchResult.Failed("сервис ответил ${conn.responseCode}")
                }
                val b64 = conn.inputStream.bufferedReader().use { it.readText() }
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                val dest = File(context.applicationContext.noBackupFilesDir, "profile_net.apubak")
                dest.writeBytes(bytes)
                FetchResult.Ready(dest)
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed: ${e.message}")
                FetchResult.Failed(e.message ?: "сеть недоступна")
            } finally {
                conn.disconnect()
            }
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Авто-проверка по расписанию: само ищет копию, само скачивает и готовит,
// уведомляет «копия готова к применению». Применение - всегда руками.
// ─────────────────────────────────────────────────────────────────────────────

object ProfileSyncAuto {

    private const val TAG = "ProfileSyncAuto"
    private const val CHANNEL_ID = "apu_sync_channel"
    private const val PREFS = "apu_profile_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WRAPPED = "wrapped_password_v1"
    const val WORK_NAME = "profile_sync_auto"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_profile_sync_pw_v2"

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** Включить авто-проверку (пароль запирается ключом этого телефона). */
    fun enable(context: Context, password: CharArray): Boolean {
        if (password.size < BackupCipher.MIN_PASSWORD_LENGTH) return false
        val wrapped = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val ct = cipher.doFinal(String(password).toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "wrap failed: ${e.message}")
            return false
        }
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_WRAPPED, wrapped)
            .apply()
        val request = PeriodicWorkRequestBuilder<ProfileSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        return true
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME) }
        prefs(app).edit().clear().commit()
    }

    /** Проверить сейчас (той же задачей, что по расписанию). */
    fun runNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME + "_now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ProfileSyncWorker>().build(),
            )
        }
    }

    /** Извлечь запертый пароль (зовёт авто-задача; пароль не покидает телефон). */
    fun unwrap(context: Context): CharArray? {
        val encoded = prefs(context).getString(KEY_WRAPPED, null) ?: return null
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, data, 0, 12))
            val plain = cipher.doFinal(data, 12, data.size - 12)
            String(plain, Charsets.UTF_8).toCharArray()
        } catch (e: Exception) {
            Log.w(TAG, "unwrap failed: ${e.message}")
            null
        }
    }

    fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Синхронизация аккаунта",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Копия профиля готова к переносу на это устройство"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyReady(context: Context, text: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 42100, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification =
            androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Синхронизация аккаунта")
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).notify(42100, notification)
        }.onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }
}

/** Точка входа для задачи без Hilt-фабрики. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProfileSyncEntryPoint {
    fun profileBackup(): ProfileBackup
}

/**
 * Периодическая авто-проверка: нашла копию - скачала, подготовила, сообщила.
 * Применение по-прежнему только руками (профиль молча не подменяем).
 */
class ProfileSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ProfileSyncAuto.isEnabled(app)) return Result.success()
        if (EntryPointAccessors.fromApplication(app, ProfileSyncEntryPoint::class.java)
                .profileBackup().hasStaged()
        ) {
            return Result.success() // уже ждёт подтверждения - не трогаем
        }
        val password = ProfileSyncAuto.unwrap(app) ?: return Result.success()
        val meta = ProfileSyncNet.meta(app) ?: return Result.success()
        when (val fetched = ProfileSyncNet.fetch(app, password)) {
            is ProfileSyncNet.FetchResult.Ready -> {
                val staged = EntryPointAccessors.fromApplication(app, ProfileSyncEntryPoint::class.java)
                    .profileBackup().stage(android.net.Uri.fromFile(fetched.file), password)
                if (staged is ProfileBackup.StageResult.Ready) {
                    ProfileSyncAuto.notifyReady(
                        app,
                        "Копия «${staged.manifest.displayName.ifBlank { "без имени" }}» готова " +
                            "к переносу. Настройки → Синхронизировать аккаунт → Применить.",
                    )
                } else {
                    EntryPointAccessors.fromApplication(app, ProfileSyncEntryPoint::class.java)
                        .profileBackup().discardStaged()
                }
            }
            else -> Unit // нет копии / не наш код - тихо ждать следующего раза
        }
        password.fill('\u0000')
        return Result.success()
    }
}
