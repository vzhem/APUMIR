package com.vladimir.messenger.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.vladimir.messenger.worker.AutoBackupWorker
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Автоматическое обновление резервной копии.
 *
 * Человек один раз сохранил копию в файл - дальше телефон сам перезаписывает
 * этот же файл тем же паролем раз в день, неделю или месяц
 * ([AutoBackupWorker] через WorkManager: без сети, не при низком заряде,
 * момент выбирает система). Ничего нового никуда не уезжает: файл лежит там,
 * куда его положил человек, и открывается только его паролем.
 *
 * Что хранится (prefs `apu_backup_schedule`, в копию НЕ входят - см.
 * [BackupLayout.PREFS_NAMES]): SAF-адрес файла с постоянным правом записи
 * (`takePersistableUriPermission`), имя файла для экрана, период, флаг
 * «с полученными файлами», адрес профиля (чтобы после восстановления чужого
 * профиля не затереть его копию), пароль - завёрнутый ключом Android Keystore
 * этого телефона, - и итог последнего запуска.
 */
object BackupSchedule {
    enum class Period(val days: Long, val flexHours: Long, val title: String, val short: String) {
        DAILY(1, 6, "каждый день", "День"),
        WEEKLY(7, 24, "раз в неделю", "Неделя"),
        MONTHLY(30, 72, "раз в месяц", "Месяц");

        companion object {
            fun fromDays(days: Long): Period = entries.firstOrNull { it.days == days } ?: WEEKLY
        }
    }

    data class State(
        val enabled: Boolean,
        val period: Period,
        val targetName: String,
        val includeReceived: Boolean,
        val enabledAtMs: Long,
        val lastOkAtMs: Long,
        val lastOkBytes: Long,
        val lastAttemptAtMs: Long,
        val lastError: String?,
    ) {
        /** Когда ждать следующего обновления (ориентир: система может сдвинуть в пределах окна). */
        val nextDueAtMs: Long
            get() {
                val base = maxOf(lastOkAtMs, lastAttemptAtMs, enabledAtMs)
                return if (base <= 0L) 0L else base + TimeUnit.DAYS.toMillis(period.days)
            }
    }

    sealed interface EnableResult {
        data object Ok : EnableResult
        /** Хранилище не даёт постоянного доступа к файлу - автообновление туда невозможно. */
        data object NoPersistentAccess : EnableResult
        data class Failed(val reason: String) : EnableResult
    }

    const val WORK_NAME = "profile_backup_auto"
    const val WORK_NAME_NOW = "profile_backup_now"

    private const val TAG = "BackupSchedule"
    private const val PREFS = "apu_backup_schedule"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET_URI = "target_uri"
    private const val KEY_TARGET_NAME = "target_name"
    private const val KEY_PERIOD_DAYS = "period_days"
    private const val KEY_INCLUDE_RECEIVED = "include_received"
    private const val KEY_NODE_ID = "node_id"
    private const val KEY_WRAPPED_PASSWORD = "wrapped_password_v1"
    private const val KEY_ENABLED_AT = "enabled_at_ms"
    private const val KEY_LAST_OK_AT = "last_ok_at_ms"
    private const val KEY_LAST_OK_BYTES = "last_ok_bytes"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at_ms"
    private const val KEY_LAST_ERROR = "last_error"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_ALIAS = "apu_backup_password_wrap_v1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val AAD = "apu-backup-password-v1".toByteArray(Charsets.US_ASCII)
    private const val URI_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): State {
        val p = prefs(context)
        return State(
            enabled = p.getBoolean(KEY_ENABLED, false),
            period = Period.fromDays(p.getLong(KEY_PERIOD_DAYS, Period.WEEKLY.days)),
            targetName = p.getString(KEY_TARGET_NAME, "") ?: "",
            includeReceived = p.getBoolean(KEY_INCLUDE_RECEIVED, true),
            enabledAtMs = p.getLong(KEY_ENABLED_AT, 0L),
            lastOkAtMs = p.getLong(KEY_LAST_OK_AT, 0L),
            lastOkBytes = p.getLong(KEY_LAST_OK_BYTES, 0L),
            lastAttemptAtMs = p.getLong(KEY_LAST_ATTEMPT_AT, 0L),
            lastError = p.getString(KEY_LAST_ERROR, null),
        )
    }

    /**
     * Включить обновление файла [target] (URI из ACTION_CREATE_DOCUMENT, в
     * который копия только что записана). Не с главного потока: спрашивает
     * имя у провайдера документов и заворачивает пароль ключом Keystore.
     */
    fun enable(
        context: Context,
        target: Uri,
        password: CharArray,
        includeReceived: Boolean,
        period: Period,
    ): EnableResult {
        val app = context.applicationContext
        if (password.size < BackupCipher.MIN_PASSWORD_LENGTH) return EnableResult.Failed("пароль короче допустимого")
        val nodeId = app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).let {
            it.getString("node_id", null) ?: it.getString("existing_public_key", null)
        } ?: return EnableResult.Failed("профиль ещё не создан")

        try {
            app.contentResolver.takePersistableUriPermission(target, URI_FLAGS)
        } catch (e: SecurityException) {
            Log.w(TAG, "persistable permission refused: ${e.message}")
            return EnableResult.NoPersistentAccess
        }
        if (!hasWriteAccess(app, target)) return EnableResult.NoPersistentAccess

        val wrapped = try {
            val bytes = String(password).toByteArray(Charsets.UTF_8)
            try { wrap(bytes) } finally { bytes.fill(0) }
        } catch (e: Exception) {
            Log.w(TAG, "password wrap failed: ${e.javaClass.simpleName}: ${e.message}")
            return EnableResult.Failed("не удалось сохранить пароль в защищённом хранилище телефона")
        }

        // Прежний файл (если был другой) больше не наш - отпускаем право на него.
        val previous = target(app)
        if (previous != null && previous != target) releaseQuietly(app, previous)

        val ok = prefs(app).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_TARGET_URI, target.toString())
            .putString(KEY_TARGET_NAME, displayName(app, target))
            .putLong(KEY_PERIOD_DAYS, period.days)
            .putBoolean(KEY_INCLUDE_RECEIVED, includeReceived)
            .putString(KEY_NODE_ID, nodeId)
            .putString(KEY_WRAPPED_PASSWORD, wrapped)
            .putLong(KEY_ENABLED_AT, System.currentTimeMillis())
            .remove(KEY_LAST_OK_AT)
            .remove(KEY_LAST_OK_BYTES)
            .remove(KEY_LAST_ATTEMPT_AT)
            .remove(KEY_LAST_ERROR)
            .commit()
        if (!ok) return EnableResult.Failed("настройки не записались")
        schedule(app, period)
        Log.i(TAG, "auto backup enabled: ${period.name} received=$includeReceived")
        return EnableResult.Ok
    }

    /** Сменить период у включённого расписания. Отсчёт начинается заново. */
    fun setPeriod(context: Context, period: Period) {
        val app = context.applicationContext
        val p = prefs(app)
        p.edit().putLong(KEY_PERIOD_DAYS, period.days).apply()
        if (p.getBoolean(KEY_ENABLED, false)) {
            p.edit().putLong(KEY_ENABLED_AT, System.currentTimeMillis()).apply()
            schedule(app, period)
            Log.i(TAG, "auto backup period: ${period.name}")
        }
    }

    /** Выключить: снять задачу, отпустить файл, стереть пароль. */
    fun disable(context: Context) {
        val app = context.applicationContext
        runCatching {
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        }.onFailure { Log.w(TAG, "cancel failed: ${it.message}") }
        target(app)?.let { releaseQuietly(app, it) }
        prefs(app).edit().clear().commit()
        Log.i(TAG, "auto backup disabled")
    }

    /** Обновить копию сейчас, не дожидаясь срока (той же задачей, что и по расписанию). */
    fun runNow(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean(KEY_ENABLED, false)) return
        runCatching {
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoBackupWorker>().build(),
            )
        }.onFailure { Log.w(TAG, "run now failed: ${it.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Для задачи
    // ─────────────────────────────────────────────────────────────────────

    fun target(context: Context): Uri? =
        prefs(context).getString(KEY_TARGET_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun boundNodeId(context: Context): String? = prefs(context).getString(KEY_NODE_ID, null)

    /** Право записи в файл всё ещё за нами (человек мог отозвать его или удалить файл). */
    fun hasWriteAccess(context: Context, target: Uri): Boolean =
        context.applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri == target && it.isWritePermission
        }

    /** Пароль копии; null, если ключ Keystore или запись пропали. Обнулить после использования. */
    fun password(context: Context): CharArray? {
        val encoded = prefs(context).getString(KEY_WRAPPED_PASSWORD, null) ?: return null
        return try {
            val bytes = unwrap(encoded) ?: return null
            try { String(bytes, Charsets.UTF_8).toCharArray() } finally { bytes.fill(0) }
        } catch (e: Exception) {
            Log.w(TAG, "password unwrap failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun recordAttempt(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis()).commit()
    }

    fun recordSuccess(context: Context, bytes: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_OK_AT, System.currentTimeMillis())
            .putLong(KEY_LAST_OK_BYTES, bytes)
            .remove(KEY_LAST_ERROR)
            .commit()
    }

    fun recordFailure(context: Context, error: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, error.take(300)).commit()
    }

    /** Остановить расписание, оставив на экране причину (например, сменился профиль). */
    fun disableWithError(context: Context, error: String) {
        val app = context.applicationContext
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME) }
        target(app)?.let { releaseQuietly(app, it) }
        prefs(app).edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_TARGET_URI)
            .remove(KEY_WRAPPED_PASSWORD)
            .putString(KEY_LAST_ERROR, error.take(300))
            .commit()
        Log.w(TAG, "auto backup stopped: $error")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Внутреннее
    // ─────────────────────────────────────────────────────────────────────

    private fun schedule(app: Context, period: Period) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        // Гибкое окно в конце периода: первое обновление - через период после
        // включения (копия только что сделана руками), момент выбирает система.
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            period.days, TimeUnit.DAYS,
            period.flexHours, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }

    private fun displayName(app: Context, target: Uri): String {
        val fromProvider = runCatching {
            app.contentResolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !c.isNull(index)) c.getString(index) else null
                } else null
            }
        }.getOrNull()
        return (fromProvider ?: target.lastPathSegment ?: "копия").take(120)
    }

    private fun releaseQuietly(app: Context, uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, URI_FLAGS) }
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, ensureWrapKey())
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES)
        val sealed = cipher.doFinal(plain)
        return Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String): ByteArray? {
        val key = existingWrapKey() ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size < GCM_IV_BYTES + GCM_TAG_BITS / 8) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
    }

    private fun existingWrapKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (store.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun ensureWrapKey(): SecretKey {
        existingWrapKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
