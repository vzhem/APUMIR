package com.vladimir.messenger.data.backup

// =============================================================================
// PROFILESYNC.KT — «Синхронизировать аккаунт»: копия профиля в облаке
// =============================================================================
// Раунд 223 (владелец: вошёл на другом устройстве под своим профилем - нет
// ни ранга, ни чатов; нужна кнопка «Синхронизировать аккаунт» и
// автосинхронизация с выбором периодичности).
//
// Как это устроено (всё из готовых механизмов, ничего нового в ядре):
//  1. Копия профиля - тот же зашифрованный архив `.apubak`, что делает
//     [ProfileBackup] (чаты, контакты, сообщества, ранг, ключи, настройки;
//     паролем человека). Полученные медиа-файлы НЕ включаем - копия должна
//     влезать в облако, а файлы остаются на телефонах.
//  2. Облако - наш сервис: PUT/GET /profile/<полка> в KV. Полка адресуется
//     отпечатком никнейма (как сундук личности), содержимое зашифровано
//     паролем - сервер не может его прочитать.
//  3. Пароль для авто-загрузки хранится ТОЛЬКО завёрнутым ключом Android
//     Keystore этого телефона (тот же приём, что в авто-копии в файл).
//  4. Восстановление - штатный путь файла-копии: stage -> подтверждение ->
//     перезапуск -> applyStagedIfAny. Случайный запуск ничего не подменяет.
// =============================================================================

import android.content.Context
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
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ProfileSync {

    private const val TAG = "ProfileSync"
    private const val PREFS = "apu_profile_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PERIOD_HOURS = "period_hours"
    private const val KEY_WRAPPED_PASSWORD = "wrapped_password_v1"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at_ms"
    private const val KEY_LAST_UPLOAD_BYTES = "last_upload_bytes"
    private const val KEY_LAST_ERROR = "last_error"

    const val WORK_NAME = "profile_sync_auto"
    const val WORK_NAME_NOW = "profile_sync_now"

    /** Периодичности авто-синхронизации (владелец: «выбрать какую периодичность»). */
    enum class Period(val hours: Long, val title: String) {
        H6(6, "6 часов"),
        H12(12, "12 часов"),
        DAILY(24, "сутки"),
        WEEKLY(168, "неделя");

        companion object {
            fun fromHours(hours: Long): Period =
                entries.firstOrNull { it.hours == hours } ?: DAILY
        }
    }

    /** Лимит облака: 24 МБ base64 (KV выше не примет). Копия без медиа обычно 1-5 МБ. */
    private const val MAX_B64_CHARS = 24_000_000
    private const val HTTP_TIMEOUT = 30_000

    /** Что показывать в настройках. */
    data class State(
        val enabled: Boolean = false,
        val period: Period = Period.DAILY,
        val lastUploadAtMs: Long = 0L,
        val lastUploadBytes: Long = 0L,
        val lastError: String? = null,
    )

    /** Что лежит в облаке (без скачивания). */
    data class CloudInfo(
        val exists: Boolean = false,
        val timeMs: Long = 0L,
        val sizeBytes: Long = 0L,
    )

    sealed interface UploadResult {
        /** Копия загружена: размер файла копии. */
        data class Ok(val bytes: Long) : UploadResult
        data object NoIdentity : UploadResult
        data object BadPassword : UploadResult
        /** Копия не влезает в облако. */
        data class TooBig(val megaBytes: Int) : UploadResult
        data class Failed(val reason: String) : UploadResult
    }

    sealed interface DownloadResult {
        data class Ready(val file: File, val cloudTimeMs: Long) : DownloadResult
        data object NotFound : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): State {
        val p = prefs(context)
        return State(
            enabled = p.getBoolean(KEY_ENABLED, false),
            period = Period.fromHours(p.getLong(KEY_PERIOD_HOURS, Period.DAILY.hours)),
            lastUploadAtMs = p.getLong(KEY_LAST_UPLOAD_AT, 0L),
            lastUploadBytes = p.getLong(KEY_LAST_UPLOAD_BYTES, 0L),
            lastError = p.getString(KEY_LAST_ERROR, null),
        )
    }

    /**
     * Полка копии: отпечаток никнейма (как у сундука личности). По ней нельзя
     * понять, чей профиль, а содержимое к тому же зашифровано паролем.
     */
    fun shelf(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val nick = prefs.getString("my_username", "")?.trim()?.removePrefix("@")?.lowercase() ?: ""
        if (nick.isEmpty()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("apu-profile-sync-v1:shelf:" + nick).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }

    private fun url(context: Context, shelf: String) =
        "https://" + GroupInviteLinks.WEB_HOST + "/profile/" + shelf

    // ── Включение/расписание ────────────────────────────────────────────────

    /** Включить авто-синхронизацию: нужен пароль копии - заворачиваем на этом телефоне. */
    fun enable(context: Context, password: CharArray, period: Period): Boolean {
        if (password.size < BackupCipher.MIN_PASSWORD_LENGTH) return false
        val wrapped = try {
            wrapPassword(password)
        } catch (e: Exception) {
            Log.w(TAG, "wrap failed: ${e.message}")
            return false
        } ?: return false
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_PERIOD_HOURS, period.hours)
            .putString(KEY_WRAPPED_PASSWORD, wrapped)
            .remove(KEY_LAST_ERROR)
            .apply()
        schedule(context, period)
        return true
    }

    fun setPeriod(context: Context, period: Period) {
        prefs(context).edit().putLong(KEY_PERIOD_HOURS, period.hours).apply()
        if (prefs(context).getBoolean(KEY_ENABLED, false)) schedule(context, period)
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME) }
        prefs(app).edit().clear().commit()
    }

    private fun schedule(context: Context, period: Period) {
        val request = PeriodicWorkRequestBuilder<ProfileSyncWorker>(period.hours, TimeUnit.HOURS)
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
    }

    /** Загрузить сейчас, не дожидаясь срока (той же задачей, что по расписанию). */
    fun runNow(context: Context) {
        if (!prefs(context).getBoolean(KEY_ENABLED, false)) return
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ProfileSyncWorker>().build(),
            )
        }
    }

    /** Пароль из защищённого хранилища телефона; null - не сохранили/потеряли. */
    fun storedPassword(context: Context): CharArray? {
        val encoded = prefs(context).getString(KEY_WRAPPED_PASSWORD, null) ?: return null
        return try {
            unwrapPassword(encoded)
        } catch (e: Exception) {
            Log.w(TAG, "unwrap failed: ${e.message}")
            null
        }
    }

    fun recordSuccess(context: Context, bytes: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_UPLOAD_AT, System.currentTimeMillis())
            .putLong(KEY_LAST_UPLOAD_BYTES, bytes)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordFailure(context: Context, error: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, error.take(300)).apply()
    }

    // ── Сеть ────────────────────────────────────────────────────────────────

    /**
     * Собрать копию профиля и загрузить в облако. Долго (снимок базы, шифрование,
     * сеть) - звать не с главного потока. Пароль после вызова обнуляется.
     */
    fun uploadBlocking(context: Context, backup: ProfileBackup, password: CharArray): UploadResult {
        if (password.size < BackupCipher.MIN_PASSWORD_LENGTH) return UploadResult.BadPassword
        val shelf = shelf(context) ?: return UploadResult.NoIdentity
        val temp = File(context.applicationContext.noBackupFilesDir, "profile_sync_upload.apubak")
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
        val conn = (URL(url(context, shelf)).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        try {
            conn.outputStream.use { it.write(b64.toByteArray(Charsets.US_ASCII)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                return UploadResult.Failed("сервис ответил $code")
            }
        } catch (e: IOException) {
            return UploadResult.Failed(e.message ?: "сеть недоступна")
        } finally {
            conn.disconnect()
        }
        recordSuccess(context, bytes.size.toLong())
        return UploadResult.Ok(bytes.size.toLong())
    }

    /** Что лежит в облаке: дата и размер (без скачивания копии). */
    suspend fun cloudInfo(context: Context): CloudInfo {
        val shelf = shelf(context) ?: return CloudInfo()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = (URL(url(context, shelf) + "?meta=1").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = HTTP_TIMEOUT
                    readTimeout = HTTP_TIMEOUT
                }
                try {
                    if (conn.responseCode != 200) return@withContext CloudInfo()
                    val json = org.json.JSONObject(
                        conn.inputStream.bufferedReader().use { it.readText() }
                    )
                    CloudInfo(
                        exists = json.optBoolean("exists", false),
                        timeMs = json.optLong("time", 0L),
                        sizeBytes = json.optLong("size", 0L),
                    )
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "cloudInfo failed: ${e.message}")
                CloudInfo()
            }
        }
    }

    /**
     * Скачать копию из облака в служебный файл. Дальше - штатный путь
     * восстановления: [ProfileBackup.stage] по этому файлу, подтверждение,
     * перезапуск.
     */
    suspend fun download(context: Context): DownloadResult {
        val shelf = shelf(context) ?: return DownloadResult.Failed("задайте никнейм в «Защите личности»")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = (URL(url(context, shelf)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = HTTP_TIMEOUT
                    readTimeout = HTTP_TIMEOUT
                }
                try {
                    if (conn.responseCode == 404) return@withContext DownloadResult.NotFound
                    if (conn.responseCode != 200) {
                        return@withContext DownloadResult.Failed("сервис ответил ${conn.responseCode}")
                    }
                    val b64 = conn.inputStream.bufferedReader().use { it.readText() }
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    val target = File(
                        context.applicationContext.noBackupFilesDir,
                        "profile_sync_download.apubak",
                    )
                    target.writeBytes(bytes)
                    val time = conn.getHeaderField("X-Apu-Time")?.toLongOrNull() ?: 0L
                    DownloadResult.Ready(target, time)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                DownloadResult.Failed(e.message ?: "сеть недоступна")
            }
        }
    }

    // ── Заворачивание пароля ключом телефона (как в авто-копии в файл) ─────

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_profile_sync_pw_v1"

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

    private fun wrapPassword(password: CharArray): String? {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(String(password).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun unwrapPassword(encoded: String): CharArray? {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(128, data, 0, 12),
        )
        val plain = cipher.doFinal(data, 12, data.size - 12)
        return String(plain, Charsets.UTF_8).toCharArray()
    }
}

/** Точка входа для задачи без Hilt-фабрики (как у авто-копии в файл). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProfileSyncEntryPoint {
    fun profileBackup(): ProfileBackup
}

/**
 * Периодическая задача авто-синхронизации: собирает копию и льёт в облако
 * тем же паролем, завёрнутым ключом этого телефона.
 */
class ProfileSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val password = ProfileSync.storedPassword(app)
        if (password == null) {
            ProfileSync.recordFailure(app, "нет сохранённого пароля - включите синхронизацию заново")
            return Result.success()
        }
        val backup = EntryPointAccessors.fromApplication(app, ProfileSyncEntryPoint::class.java)
            .profileBackup()
        val outcome = ProfileSync.uploadBlocking(app, backup, password)
        when (outcome) {
            is ProfileSync.UploadResult.Ok -> Log.i(TAG2, "auto upload ok: ${outcome.bytes} байт")
            is ProfileSync.UploadResult.TooBig ->
                ProfileSync.recordFailure(app, "копия ${outcome.megaBytes} МБ - не влезает в облако")
            is ProfileSync.UploadResult.Failed ->
                ProfileSync.recordFailure(app, outcome.reason)
            else -> ProfileSync.recordFailure(app, outcome.toString())
        }
        return Result.success()
    }

    private companion object {
        const val TAG2 = "ProfileSyncWorker"
    }
}
