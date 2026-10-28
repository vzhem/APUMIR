package com.vladimir.messenger.data.security

import android.content.Context
import android.util.Log
import com.vladimir.messenger.util.readBytesAtMost
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Программный ключ-обёртка - запасной путь для прошивок, где Keystore не даёт
 * создать или применить AES-ключ.
 *
 * Зачем. На части устройств (дешёвые MediaTek/CUBOT, отдельные сборки
 * Android 14) KeyMint отвечает на генерацию ключа ошибкой
 * `MEMORY_ALLOCATION_FAILED` или «without explicit attestation key». Тогда
 * приложение оставалось без ключа личности: личные сообщения и истории честно
 * не отправлялись открытым текстом, и телефон молчал (сводка владельца
 * 2026-10-28: «ключ этого телефона не читается», `KeyUnavailableException` в
 * раздаче APK, отказ ключа ретрансляции при каждом старте).
 *
 * Такой же запасной путь уже принят для ключей передачи файлов
 * (`FileTransferKeyVault`, раунд 255). Здесь он повторён для семени подписи и
 * ключа обмена - с той же ценой: ключ лежит в закрытой области приложения
 * (`noBackupFilesDir`), не покидает телефон и не попадает в Auto Backup.
 *
 * Что важно: фолбэк только ДОБАВЛЯЕТ путь. Блоб, который не открывается ни
 * ключом Keystore, ни программным ключом, по-прежнему не перезаписывается и не
 * «ротируется» молча - решение остаётся за человеком.
 */
object SoftwareWrapKey {
    private const val TAG = "SoftwareWrapKey"
    private const val DIR = "apu_software_wrap"
    private const val KEY_BYTES = 32
    private const val MAX_DOMAIN = 48

    /** Существующий программный ключ домена; null - его нет или он испорчен. */
    fun existing(context: Context, domain: String): SecretKey? = try {
        val file = fileFor(context, domain)
        if (!file.isFile) {
            null
        } else {
            val bytes = file.readBytesAtMost(KEY_BYTES.toLong() + 1)
            if (bytes.size != KEY_BYTES) {
                Log.w(TAG, "software wrap key has unexpected length")
                null
            } else {
                SecretKeySpec(bytes, "AES")
            }
        }
    } catch (error: Exception) {
        Log.w(TAG, "software wrap key unreadable: " + error.javaClass.simpleName)
        null
    }

    /** Создать программный ключ домена; null - записать не удалось. */
    @Synchronized
    fun create(context: Context, domain: String): SecretKey? = try {
        val file = fileFor(context, domain)
        file.parentFile?.mkdirs()
        val bytes = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        file.writeBytes(bytes)
        // Файл читает только это приложение: без рута до него не добраться.
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        Log.i(TAG, "software wrap key created for " + domain)
        SecretKeySpec(bytes, "AES")
    } catch (error: Exception) {
        Log.w(TAG, "software wrap key creation failed: " + error.javaClass.simpleName)
        null
    }

    /** Один домен - один файл: ключи подписи и обмена не должны пересекаться. */
    private fun fileFor(context: Context, domain: String): File {
        val safe = domain.take(MAX_DOMAIN)
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '_' }
            .joinToString("")
        return File(File(context.applicationContext.noBackupFilesDir, DIR), safe + ".key")
    }
}
