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

    // nodeId личности ОДИНАКОВ на всех её телефонах, поэтому по нему нельзя
    // понять, кто выложил копию. Этот идентификатор установки хранится отдельно
    // от переносимого профиля (BackupLayout его не экспортирует).
    private const val DEVICE_PREFS = "apu_profile_sync_device"
    private const val DEVICE_ID_KEY = "device_id_v1"

    /** Лимит релея: 24 МБ base64 (копия без медиа обычно 1-5 МБ). */
    const val MAX_B64_CHARS = 24_000_000

    /** Итог ручной отправки. fp - отпечаток данных отправленной копии. */
    sealed interface UploadResult {
        data class Ok(val bytes: Long, val fp: String = "") : UploadResult
        data object NoIdentity : UploadResult
        data object BadPassword : UploadResult
        data class TooBig(val megaBytes: Int) : UploadResult
        data class Failed(val reason: String) : UploadResult
    }

    /** Метка «в сети есть копия» (без скачивания). dev - чьё устройство выложило. */
    data class NetMeta(
        val timeMs: Long,
        val sizeBytes: Long,
        val dev: String = "",
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

    /**
     * Идентификатор именно этой установки, а не аккаунта. nodeId у всех
     * телефонов одной личности одинаковый; если помечать им копию, каждое
     * устройство будет принимать чужую копию за свою и молча её игнорировать.
     * SharedPreferences `apu_profile_sync_device` не входит в BackupLayout,
     * поэтому при переносе профиля на новый телефон создаётся новый маркер.
     */
    fun deviceIdOf(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val current = prefs.getString(DEVICE_ID_KEY, null)
            if (current != null && ProfileSyncDeviceId.isValid(current)) return current
            val fresh = ProfileSyncDeviceId.newId()
            // commit синхронный: два одновременных опроса не должны выдать
            // этому телефону разные id отправителя.
            prefs.edit().putString(DEVICE_ID_KEY, fresh).commit()
            return fresh
        }
    }

    /** nodeId личности нужен только для распознавания меток старых сборок. */
    fun accountNodeIdOf(context: Context): String =
        context.applicationContext
            .getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .let { prefs -> prefs.getString("node_id", null) ?: prefs.getString("existing_public_key", null) }
            ?: ""

    /** Это наша копия: новый dev-id либо legacy nodeId старой версии. */
    fun isOwnDevice(meta: NetMeta?, thisDeviceId: String, accountNodeId: String): Boolean {
        val owner = meta?.dev?.takeIf { it.isNotBlank() } ?: return false
        return owner == thisDeviceId || (owner.startsWith("pk_") && owner == accountNodeId)
    }

    /** Есть копия от другого телефона; пустые/неизвестные метки не скачиваем. */
    fun isForeignDevice(meta: NetMeta?, thisDeviceId: String, accountNodeId: String): Boolean {
        val owner = meta?.dev?.takeIf { it.isNotBlank() } ?: return false
        return if (owner.startsWith("d_")) owner != thisDeviceId
        else owner.startsWith("pk_") && owner != accountNodeId
    }

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
        // Выкладываем с меткой устройства: авто-режим отличит свою копию от чужой.
        val conn = (URL(baseUrl() + slot).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
            setRequestProperty("X-Apu-Check", sha256(code))
            setRequestProperty("X-Apu-Device", deviceIdOf(context))
            connectTimeout = HTTP_TIMEOUT
            readTimeout = READ_TIMEOUT
        }
        try {
            conn.outputStream.use { it.write(b64.toByteArray(Charsets.US_ASCII)) }
            val resp = conn.responseCode
            if (resp !in 200..299) {
                // Раунд 256: тело ошибки релея ({"error": …}) - в сообщение,
                // чтобы по скриншоту была видна серверная причина.
                val detail = runCatching {
                    conn.errorStream?.readBytes()?.decodeToString()?.trim()?.take(140)
                }.getOrNull()
                return UploadResult.Failed(
                    "сервис ответил $resp" + (detail?.let { ": $it" } ?: ""),
                )
            }
        } catch (e: IOException) {
            return UploadResult.Failed(e.message ?: "сеть недоступна")
        } finally {
            conn.disconnect()
        }
        return UploadResult.Ok(bytes.size.toLong(), created.dataFp)
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
                    else NetMeta(json.optLong("time", 0L), json.optLong("size", 0L), dev = json.optString("dev", ""))
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
                    !in 200..299 -> {
                        // Раунд 256: серверная причина - в сообщение.
                        val detail = runCatching {
                            conn.errorStream?.readBytes()?.decodeToString()?.trim()?.take(140)
                        }.getOrNull()
                        return@withContext FetchResult.Failed(
                            "сервис ответил ${conn.responseCode}" + (detail?.let { ": $it" } ?: ""),
                        )
                    }
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

/** Уникальный 128-битный маркер установки; чистая JVM-логика покрыта unit-тестом. */
internal object ProfileSyncDeviceId {
    private val pattern = Regex("^d_[0-9a-f]{32}$")

    fun newId(): String = "d_" + java.util.UUID.randomUUID().toString().replace("-", "")

    fun isValid(value: String): Boolean = pattern.matches(value)
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

    private const val KEY_EVER_FETCHED = "ever_fetched"
    private const val KEY_EVER_APPLIED = "ever_applied"
    private const val KEY_APPLIED_FP = "applied_fp"
    private const val KEY_MY_FP = "my_upload_fp"

    /** Тянули чужую копию, но ещё не применили: свою на полку не класть. */
    fun everFetched(context: Context): Boolean = prefs(context).getBoolean(KEY_EVER_FETCHED, false)

    fun everApplied(context: Context): Boolean = prefs(context).getBoolean(KEY_EVER_APPLIED, false)

    fun noteFetchedShelf(context: Context) {
        prefs(context).edit().putBoolean(KEY_EVER_FETCHED, true).apply()
    }

    /** Копия применена: запомнить отпечаток (одинаковые больше не дёргают). */
    fun markApplied(context: Context, fingerprint: String) {
        prefs(context).edit()
            .putBoolean(KEY_EVER_APPLIED, true)
            .putString(KEY_APPLIED_FP, fingerprint ?: "")
            .apply()
    }

    fun appliedFp(context: Context): String = prefs(context).getString(KEY_APPLIED_FP, "") ?: ""

    fun myUploadFp(context: Context): String = prefs(context).getString(KEY_MY_FP, "") ?: ""

    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"

    /** Раунд 257: когда последний раз выкладывали копию (троттлинг записей KV). */
    fun lastUploadAtMs(context: Context): Long = prefs(context).getLong(KEY_LAST_UPLOAD_AT, 0L)

    fun noteMyUpload(context: Context, fingerprint: String) {
        prefs(context).edit()
            .putString(KEY_MY_FP, fingerprint ?: "")
            .putLong(KEY_LAST_UPLOAD_AT, System.currentTimeMillis())
            .apply()
    }

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_profile_sync_pw_v2"
    // Раунд 256: префиксы обёртки - чтобы понимать, каким ключом запирали.
    // На телефонах с битым Keystore (KeyMint MEMORY_ALLOCATION_FAILED)
    // пароль запирается программным AES в приватных настройках.
    private const val WRAP_KEYSTORE = "ks:"
    private const val WRAP_SOFTWARE = "sw:"
    private const val KEY_SOFTWARE_WRAP = "software_wrap_v1"

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

    private fun softwareKey(context: Context): SecretKey {
        val stored = prefs(context).getString(KEY_SOFTWARE_WRAP, null)
        if (stored != null) {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size == 32) return javax.crypto.spec.SecretKeySpec(raw, "AES")
        }
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_SOFTWARE_WRAP, Base64.encodeToString(raw, Base64.NO_WRAP))
            .apply()
        return javax.crypto.spec.SecretKeySpec(raw, "AES")
    }

    /** Раунд 256: запереть пароль; Keystore, а при отказе - программным ключом. */
    private fun wrapPassword(context: Context, password: CharArray): String? {
        val plain = String(password).toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val ct = cipher.doFinal(plain)
            WRAP_KEYSTORE + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "keystore wrap failed, software fallback: ${e.message}")
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, softwareKey(context))
                val ct = cipher.doFinal(plain)
                WRAP_SOFTWARE + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
            } catch (second: Exception) {
                Log.w(TAG, "software wrap failed: ${second.message}")
                null
            }
        }
    }

    /**
     * Раунд 256: запомнить пароль входа без включения расписания - окно
     * переноса подставит его само, человеку вводить ничего не нужно.
     */
    fun rememberPassword(context: Context, password: CharArray): Boolean {
        if (password.size < BackupCipher.MIN_PASSWORD_LENGTH) return false
        val wrapped = wrapPassword(context, password) ?: return false
        prefs(context).edit().putString(KEY_WRAPPED, wrapped).apply()
        return true
    }

    fun hasStoredPassword(context: Context): Boolean =
        prefs(context).getString(KEY_WRAPPED, null) != null

    /** Включить авто-проверку (пароль запирается ключом этого телефона). */
    fun enable(context: Context, password: CharArray): Boolean {
        if (password.size < BackupCipher.MIN_PASSWORD_LENGTH) return false
        val wrapped = wrapPassword(context, password) ?: return false
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
        val body = when {
            encoded.startsWith(WRAP_KEYSTORE) -> encoded.substring(WRAP_KEYSTORE.length)
            encoded.startsWith(WRAP_SOFTWARE) -> encoded.substring(WRAP_SOFTWARE.length)
            else -> encoded // старые версии писали без префикса (Keystore)
        }
        val candidates = mutableListOf<SecretKey>()
        if (!encoded.startsWith(WRAP_SOFTWARE)) {
            runCatching { candidates += keystoreKey() }
        }
        if (!encoded.startsWith(WRAP_KEYSTORE)) {
            runCatching { candidates += softwareKey(context) }
        }
        val data = try {
            Base64.decode(body, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "unwrap: bad base64: ${e.message}")
            return null
        }
        for (key in candidates) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
                val plain = cipher.doFinal(data, 12, data.size - 12)
                return String(plain, Charsets.UTF_8).toCharArray()
            } catch (e: Exception) {
                // не этот ключ - пробуем следующий
            }
        }
        Log.w(TAG, "unwrap failed: no candidate key decrypted")
        return null
    }

    fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Перенос профиля",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Полная копия готова к переносу на новое устройство"
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
                .setContentTitle("Перенос профиля")
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
        val backup = EntryPointAccessors.fromApplication(app, ProfileSyncEntryPoint::class.java)
            .profileBackup()
        if (backup.hasStaged()) return Result.success() // уже ждёт подтверждения - не трогаем
        val password = ProfileSyncAuto.unwrap(app) ?: return Result.success()
        try {
            val meta = ProfileSyncNet.meta(app)
            val myDeviceId = ProfileSyncNet.deviceIdOf(app)
            val accountNodeId = ProfileSyncNet.accountNodeIdOf(app)
            val foreign = ProfileSyncNet.isForeignDevice(meta, myDeviceId, accountNodeId)
            if (foreign) {
                // Чужая копия на полке: сами качаем, сами готовим. Применение - руками.
                when (val fetched = ProfileSyncNet.fetch(app, password)) {
                    is ProfileSyncNet.FetchResult.Ready -> {
                        ProfileSyncAuto.noteFetchedShelf(app)
                        when (val staged = backup.stage(android.net.Uri.fromFile(fetched.file), password)) {
                            is ProfileBackup.StageResult.Ready -> {
                                val fp = staged.manifest.dataFp
                                val known = fp.isNotBlank() &&
                                    (fp == ProfileSyncAuto.appliedFp(app) ||
                                        fp == ProfileSyncAuto.myUploadFp(app))
                                if (known) {
                                    backup.discardStaged() // такие данные уже есть - без спама
                                } else {
                                    ProfileSyncAuto.notifyReady(
                                        app,
                                        "Копия «${staged.manifest.displayName.ifBlank { "без имени" }}» готова " +
                                            "к переносу. Настройки → Перенос профиля → Применить.",
                                    )
                                }
                            }
                            else -> backup.discardStaged()
                        }
                    }
                    else -> Unit // нет копии / не наш код / сеть - тихо ждать следующего раза
                }
            } else {
                // Полка пуста (или там наша же старая копия): сами выкладываем свежую,
                // чтобы второе устройство нашло нас без всяких нажатий. Приёмник, ещё
                // не применивший чужую копию, полку не трогает - не затирать её пустым.
                val stale = ProfileSyncNet.isOwnDevice(meta, myDeviceId, accountNodeId) &&
                    System.currentTimeMillis() - (meta?.timeMs ?: 0L) > 12L * 60 * 60 * 1000
                val blocked = ProfileSyncAuto.everFetched(app) && !ProfileSyncAuto.everApplied(app)
                // Раунд 257: выкладка = 4 записи KV на реле (дневной лимит на
                // бесплатном плане) - не чаще раза в 2 часа даже при пустой полке.
                val uploadThrottled =
                    System.currentTimeMillis() - ProfileSyncAuto.lastUploadAtMs(app) < 2L * 60 * 60 * 1000
                if ((meta == null || stale) && !blocked && !uploadThrottled) {
                    when (val up = ProfileSyncNet.uploadBlocking(app, backup, password)) {
                        is ProfileSyncNet.UploadResult.Ok -> ProfileSyncAuto.noteMyUpload(app, up.fp)
                        else -> Unit // нет профиля / сеть - тихо
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ProfileSyncWorker", "auto tick failed: ${e.message}")
        } finally {
            password.fill('\u0000')
        }
        return Result.success()
    }
}
