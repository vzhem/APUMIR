package com.vladimir.messenger.data.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vladimir.messenger.service.BotApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Облачная резервная копия азбуки адресов (просьба владельца, 2026-09-19 —
 * «это самое главное»).
 *
 * Файл азбуки живёт в `filesDir/apu_peer_addresses.json` (ядро читает его
 * ОДИН раз при создании движка — восстановление обязано случиться ДО
 * [com.vladimir.messenger.data.RustBridge.initialize]).
 *
 * Шифрование: ключ = SHA-256(приватный ключ узла + доменная строка), AES/GCM.
 * Наружу уходят непрозрачные байты; открыть копию может только та же
 * личность — восстановление работает после восстановления личности с полки.
 * Автокопия: раз в 6 часов, если файл менялся; плюс кнопка в «Настройки →
 * Сервер».
 */
@Singleton
class AddressBookBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botApi: BotApi,
) {
    companion object {
        private const val TAG = "AddressBookBackup"
        private const val PREFS = "p2p_prefs"
        private const val FILE_NAME = "apu_peer_addresses.json"
        private const val MAGIC = "APUADDBK1"
        private const val KEY_LAST_BACKUP_AT = "addrbook_backup_at"
        private const val KEY_LAST_BACKUP_SHA = "addrbook_backup_sha"
        private const val KEY_LAST_RESTORE_AT = "addrbook_restore_at"
        private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000

        fun shelfFor(nodeId: String): String = "addrbook|$nodeId"

        fun bookFile(context: Context): File =
            File(context.filesDir, FILE_NAME)
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBackupAtMs(): Long = prefs().getLong(KEY_LAST_BACKUP_AT, 0L)

    fun lastRestoreAtMs(): Long = prefs().getLong(KEY_LAST_RESTORE_AT, 0L)

    /** Сколько адресов в локальной азбуке (0 — файл пуст/битый). */
    fun localEntryCount(): Int = runCatching {
        val file = bookFile(context)
        if (!file.isFile) return 0
        JSONObject(file.readText()).optJSONArray("entries")?.length() ?: 0
    }.getOrDefault(0)

    private fun privateKey(): String? =
        prefs().getString("existing_private_key", null)
            ?: prefs().getString("node_id", null)

    private fun deriveKey(privateKey: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest((privateKey + ":apu-addrbook-v1").toByteArray(Charsets.UTF_8))
    }

    private fun encrypt(plain: ByteArray, privateKey: String): String? = runCatching {
        val key = SecretKeySpec(deriveKey(privateKey), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        val out = ByteArray(1 + iv.size + sealed.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(sealed, 0, out, 1 + iv.size, sealed.size)
        MAGIC + "|" + Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(envelope: String, privateKey: String): ByteArray? = runCatching {
        if (!envelope.startsWith("$MAGIC|")) return null
        val raw = Base64.decode(envelope.substringAfter('|'), Base64.NO_WRAP)
        val ivLen = raw[0].toInt() and 0xFF
        val key = SecretKeySpec(deriveKey(privateKey), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 1, ivLen))
        cipher.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen)
    }.getOrNull()

    private fun fileSha(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        hex
    }.getOrDefault("")

    /**
     * Скопировать азбуку на сервер. Возвращает понятный человеку итог.
     */
    suspend fun backupNow(): String = withContext(Dispatchers.IO) {
        val nodeId = prefs().getString("node_id", null)
            ?: return@withContext "Личность ещё не создана"
        val privateKey = privateKey() ?: return@withContext "Личность ещё не создана"
        val file = bookFile(context)
        if (!file.isFile || file.length() < 4) return@withContext "Азбука пуста — копировать нечего"
        val plain = runCatching { file.readText() }.getOrNull()
            ?: return@withContext "Азбука не читается"
        val sealed = encrypt(plain.toByteArray(Charsets.UTF_8), privateKey)
            ?: return@withContext "Не удалось зашифровать копию"
        val ok = botApi.storeAddressBook(shelfFor(nodeId), sealed)
        if (ok) {
            prefs().edit()
                .putLong(KEY_LAST_BACKUP_AT, System.currentTimeMillis())
                .putString(KEY_LAST_BACKUP_SHA, fileSha(file))
                .apply()
            "Копия сохранена на сервере (${localEntryCount()} адресов)"
        } else {
            "Сервер недоступен — попробую снова позже"
        }
    }

    /**
     * Восстановить азбуку с сервера. Вызывается ДО создания движка
     * ([com.vladimir.messenger.data.RustBridge.initialize]) на свежей
     * установке: существующий файл не перезаписываем.
     */
    suspend fun restoreBeforeStart(): String = withContext(Dispatchers.IO) {
        val file = bookFile(context)
        if (file.isFile && file.length() >= 4) return@withContext ""
        val nodeId = prefs().getString("node_id", null)
            ?: prefs().getString("existing_public_key", null)
            ?: return@withContext ""
        val privateKey = privateKey() ?: return@withContext ""
        val sealed = botApi.fetchAddressBook(shelfFor(nodeId)) ?: return@withContext ""
        val plainBytes = decrypt(sealed, privateKey)
            ?: return@withContext "copy-decrypt-failed"
        val plainText = String(plainBytes, Charsets.UTF_8)
        val parsed = runCatching { JSONObject(plainText) }.getOrNull()
        if (parsed == null || !parsed.has("entries")) return@withContext "copy-invalid"
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(plainText)
        if (!tmp.renameTo(file)) {
            file.writeText(plainText)
            tmp.delete()
        }
        prefs().edit().putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis()).apply()
        Log.i(TAG, "Address book restored from server (${parsed.optJSONArray("entries")?.length() ?: 0} entries)")
        ""
    }

    /**
     * Ручное восстановление из настроек: ТОЛЬКО по явному нажатию —
     * существующий файл замещается копией с сервера. Движок подхватит
     * азбуку при следующем старте (пересеивание адресов в память).
     */
    suspend fun restoreNowForce(): String = withContext(Dispatchers.IO) {
        val nodeId = prefs().getString("node_id", null)
            ?: return@withContext "Личность ещё не создана"
        val privateKey = privateKey() ?: return@withContext "Личность ещё не создана"
        val sealed = botApi.fetchAddressBook(shelfFor(nodeId))
            ?: return@withContext "На сервере копии нет (или сервер недоступен)"
        val plainBytes = decrypt(sealed, privateKey)
            ?: return@withContext "Копия не открылась вашим ключом"
        val plainText = String(plainBytes, Charsets.UTF_8)
        val parsed = runCatching { JSONObject(plainText) }.getOrNull()
        if (parsed == null || !parsed.has("entries")) return@withContext "Копия повреждена"
        val file = bookFile(context)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(plainText)
        if (!tmp.renameTo(file)) {
            file.writeText(plainText)
            tmp.delete()
        }
        prefs().edit().putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis()).apply()
        "Восстановлено ${parsed.optJSONArray("entries")?.length() ?: 0} адресов — вступит в силу после перезапуска приложения"
    }

    /**
     * Автокопия: не чаще [AUTO_INTERVAL_MS] и только если файл менялся.
     * Вызывается из фоновой петли сервиса.
     */
    suspend fun backupIfDue(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val file = bookFile(context)
        if (!file.isFile || file.length() < 4) return@withContext null
        val now = System.currentTimeMillis()
        val last = prefs().getLong(KEY_LAST_BACKUP_AT, 0L)
        val sha = fileSha(file)
        val unchanged = prefs().getString(KEY_LAST_BACKUP_SHA, null) == sha
        if (!force && unchanged && now - last < AUTO_INTERVAL_MS) return@withContext null
        if (unchanged && last > 0) return@withContext null
        backupNow().takeIf { it.startsWith("Копия") }
    }
}
