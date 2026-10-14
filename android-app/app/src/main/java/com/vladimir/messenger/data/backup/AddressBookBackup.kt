package com.vladimir.messenger.data.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vladimir.messenger.data.RustBridge
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
    private val swarm: AddressBookSwarmBackup,
) {
    companion object {
        private const val TAG = "AddressBookBackup"
        private const val PREFS = "p2p_prefs"
        private const val FILE_NAME = "apu_peer_addresses.json"
        const val MAGIC = "APUADDBK1"
        private const val KEY_LAST_BACKUP_AT = "addrbook_backup_at"
        private const val KEY_LAST_BACKUP_SHA = "addrbook_backup_sha"
        private const val KEY_LAST_RESTORE_AT = "addrbook_restore_at"
        private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000
        /** Retry a missing book when a *new* signed neighbour becomes reachable. */
        private const val KEY_LAST_SWARM_RESTORE_AT = "addrbook_swarm_restore_at"
        private const val KEY_LAST_SWARM_RESTORE_PEERS = "addrbook_swarm_restore_peers"
        private const val SWARM_RESTORE_RETRY_MS = 10L * 60 * 1000
        private const val KEY_LAST_SERVER_RESTORE_AT = "addrbook_server_restore_at"
        private const val SERVER_RESTORE_RETRY_MS = 30L * 60 * 1000

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
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        val sealed = encrypt(plainBytes, privateKey)
            ?: return@withContext "Не удалось зашифровать копию"
        val snapshotSha = MessageDigest.getInstance("SHA-256").digest(plainBytes)
            .joinToString("") { "%02x".format(it) }
        // A send being queued is not storage. Wait briefly for a holder's ACK
        // naming this exact ciphertext before concluding that this snapshot
        // exists in the swarm. The owner keeps retrying if no one confirms.
        val extra = runCatching { swarm.distribute(sealed, force = true, snapshotSha = snapshotSha) }
            .getOrNull().orEmpty()
        if (swarm.awaitConfirmedSnapshot(sealed)) {
            prefs().edit().putLong(KEY_LAST_BACKUP_AT, System.currentTimeMillis())
                .putString(KEY_LAST_BACKUP_SHA, snapshotSha).apply()
            return@withContext "Копия сохранена у хранителя роя (${localEntryCount()} адресов)"
        }
        if (!RustBridge.externalTransportAllowed()) {
            return@withContext "Копия $extra; подтверждения ещё нет, сервер не трогаем, пока рой доступен"
        }
        val ok = botApi.storeAddressBook(shelfFor(nodeId), sealed)
        if (ok) {
            prefs().edit().putLong(KEY_LAST_BACKUP_AT, System.currentTimeMillis())
                .putString(KEY_LAST_BACKUP_SHA, snapshotSha).apply()
            "Копия сохранена на резервном сервере (${localEntryCount()} адресов); $extra"
        } else {
            "Копия $extra; запасной сервер недоступен — повторю позже"
        }
    }

    /**
     * Startup must NOT fetch the server before even attempting mDNS and
     * verified saved addresses. Start the empty core; autoRestoreIfEmpty then
     * asks actual neighbours and only falls back after the direct-search window.
     * Its merged hints enter the running engine through importAddressBookJson.
     */
    suspend fun restoreBeforeStart(): String = ""

    /**
     * Ручное восстановление из настроек: ТОЛЬКО по явному нажатию —
     * существующий файл замещается копией с сервера. Движок подхватит
     * азбуку при следующем старте (пересеивание адресов в память).
     */
    suspend fun restoreNowForce(): String = withContext(Dispatchers.IO) {
        val nodeId = prefs().getString("node_id", null)
            ?: return@withContext "Личность ещё не создана"
        val privateKey = privateKey() ?: return@withContext "Личность ещё не создана"
        // Раунд 194 (план разгрузки, шаг 3): рой - ОСНОВНОЙ путь восстановления,
        // сервер - запасной (он же нужен только для самой первой загрузки).
        // Обычное восстановление больше не тратит запросы лимитированного воркера.
        val fromSwarm = askSwarm(privateKey)
        if (fromSwarm != null) return@withContext fromSwarm
        if (!RustBridge.externalTransportAllowed()) {
            return@withContext "Рой пока доступен или поиск ещё идёт; резервный сервер не опрашиваем"
        }
        val sealed = botApi.fetchAddressBook(shelfFor(nodeId))
        if (sealed == null) {
            return@withContext "Рой молчал, на сервере копии нет — попробуйте позже"
        }
        val parsed = parseEnvelope(sealed, privateKey)
            ?: return@withContext "Копия не открылась вашим ключом"
        // Раунд 125 (владелец): азбука на телефоне ОДНА - копия в неё
        // вливается (добавляются только те адреса, которых не было).
        val added = mergeIntoLocalBook(parsed)
        if (added == 0) {
            return@withContext "Нового ничего: все ${localEntryCount()} адресов уже в азбуке"
        }
        "В азбуку добавлено $added адресов (стало ${localEntryCount()}) — " +
            if (RustBridge.isRunning()) "прямой поиск уже запущен" else "вступит в силу после перезапуска"
    }

    /**
     * Раунд 125 (владелец): телефон хранит ОДНУ азбуку. Присланные копии
     * (с сервера и от хранителей роя) в неё ВЛИВАЮТСЯ: добавляются только
     * те адреса, которых локально ещё нет; существующие не трогаются.
     *
     * @return сколько адресов добавлено.
     */
    private suspend fun mergeIntoLocalBook(parsed: JSONObject): Int = withContext(Dispatchers.IO) {
        if (RustBridge.isRunning()) {
            val added = RustBridge.importAddressBookJson(parsed.toString())
            if (added < 0) {
                Log.w(TAG, "running core rejected address book; refusing to overwrite its live file")
                return@withContext 0
            }
            if (added > 0) prefs().edit().putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis()).apply()
            return@withContext added
        }
        val file = bookFile(context)
        val local = if (file.isFile && file.length() >= 4) {
            runCatching { JSONObject(file.readText()) }.getOrNull()
        } else {
            null
        }
        val incoming = parsed.optJSONArray("entries")
        if (incoming == null || incoming.length() == 0) return@withContext 0
        if (local == null) {
            // Своей азбуки ещё нет - копия и становится той самой единственной.
            writeBook(parsed)
            return@withContext incoming.length()
        }
        val localEntries = local.optJSONArray("entries")
        if (localEntries == null) {
            // Битая локальная азбука - заменяем целиком присланной.
            writeBook(parsed)
            return@withContext incoming.length()
        }
        val known = HashSet<String>()
        for (i in 0 until localEntries.length()) {
            val id = localEntries.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) known.add(id)
        }
        var added = 0
        for (i in 0 until incoming.length()) {
            val entry = incoming.optJSONObject(i) ?: continue
            val id = entry.optString("id").orEmpty()
            if (id.isBlank() || known.contains(id)) continue
            known.add(id)
            localEntries.put(entry)
            added++
        }
        if (added > 0) writeBook(local)
        added
    }

    /** Открыть конверт и проверить структуру. null — не наш/битый. */
    private fun parseEnvelope(envelope: String, privateKey: String): JSONObject? {
        val plainBytes = decrypt(envelope, privateKey) ?: return null
        val parsed = runCatching { JSONObject(String(plainBytes, Charsets.UTF_8)) }.getOrNull()
        if (parsed == null || !parsed.has("entries")) return null
        return parsed
    }

    /** Записать азбуку на диск (tmp+rename, как пишет ядро). */
    private fun writeBook(parsed: JSONObject) {
        val file = bookFile(context)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(parsed.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(parsed.toString())
            tmp.delete()
        }
        prefs().edit().putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis()).apply()
    }

    /**
     * Спросить телефоны роя и слить их копии (сколько дали) в одну азбуку:
     * добавляются только адреса, которых локально нет (решение владельца
     * 2026-09-22).
     */
    private suspend fun askSwarm(privateKey: String): String? {
        val envelopes = swarm.askAndRestoreAll { e -> parseEnvelope(e, privateKey) != null }
        if (envelopes.isEmpty()) return null
        var added = 0
        for (envelope in envelopes) {
            val parsed = parseEnvelope(envelope, privateKey) ?: continue
            added += mergeIntoLocalBook(parsed)
        }
        if (added == 0) {
            return "Рой отдал ${envelopes.size} копий, но нового ничего: все адреса уже в азбуке"
        }
        return "Из ${envelopes.size} копий роя в азбуку добавлено $added адресов (стало ${localEntryCount()}) — " +
            if (RustBridge.isRunning()) "прямой поиск уже запущен" else "вступит в силу после перезапуска"
    }

    /**
     * Empty local book: first request *this snapshot* from swarm holders,
     * then ask only the address-book shelf if direct discovery found no
     * authenticated peer. The restored hints enter the already-running core.
     */
    suspend fun autoRestoreIfEmpty(): Unit = withContext(Dispatchers.IO) {
        if (localEntryCount() > 0) return@withContext
        val privateKey = privateKey() ?: return@withContext
        val nodeId = prefs().getString("node_id", null) ?: return@withContext
        val now = System.currentTimeMillis()
        val live = RustBridge.liveSwarmPeerIds().sorted().joinToString("|")
        // Do not burn the six-hour throttle simply because startup happened
        // before the first signed PEX response. A newly reached neighbour is
        // worth asking even if a different one answered recently.
        if (live.isNotEmpty() &&
            (live != prefs().getString(KEY_LAST_SWARM_RESTORE_PEERS, null) ||
                now - prefs().getLong(KEY_LAST_SWARM_RESTORE_AT, 0L) >= SWARM_RESTORE_RETRY_MS)
        ) {
            prefs().edit().putLong(KEY_LAST_SWARM_RESTORE_AT, now)
                .putString(KEY_LAST_SWARM_RESTORE_PEERS, live).apply()
            val result = askSwarm(privateKey)
            if (result != null) {
                Log.i(TAG, "swarm auto-restore: $result")
                return@withContext
            }
        }
        if (!RustBridge.externalTransportAllowed()) {
            Log.i(TAG, "address book backup: live swarm/search, external shelf asleep")
            return@withContext
        }
        if (now - prefs().getLong(KEY_LAST_SERVER_RESTORE_AT, 0L) < SERVER_RESTORE_RETRY_MS) return@withContext
        prefs().edit().putLong(KEY_LAST_SERVER_RESTORE_AT, now).apply()
        val sealed = botApi.fetchAddressBook(shelfFor(nodeId)) ?: return@withContext
        val parsed = parseEnvelope(sealed, privateKey) ?: return@withContext
        val added = mergeIntoLocalBook(parsed)
        Log.i(TAG, "address book fallback restored $added missing address hints")
    }

    /** The digest and encrypted copy MUST describe the same read of the file. */
    private fun currentSnapshot(): Pair<String, String>? {
        val privateKey = privateKey() ?: return null
        val file = bookFile(context)
        if (!file.isFile || file.length() < 4) return null
        val plain = runCatching { file.readBytes() }.getOrNull() ?: return null
        val envelope = encrypt(plain, privateKey) ?: return null
        val sha = MessageDigest.getInstance("SHA-256").digest(plain)
            .joinToString("") { "%02x".format(it) }
        return envelope to sha
    }

    @Volatile private var lastSwarmShareKey = ""
    @Volatile private var lastSwarmShareAt = 0L

    /** Re-offer new data to new live neighbours within a minute, without a per-minute fan-out. */
    suspend fun swarmHourlyTick() = withContext(Dispatchers.IO) {
        val live = RustBridge.liveSwarmPeerIds().sorted()
        if (live.isEmpty()) return@withContext
        val (envelope, sha) = currentSnapshot() ?: return@withContext
        val key = sha + "|" + live.joinToString("|")
        val now = System.currentTimeMillis()
        if (key == lastSwarmShareKey && now - lastSwarmShareAt < 10L * 60_000L) return@withContext
        swarm.hourlyTick(envelope, sha)
        lastSwarmShareKey = key
        lastSwarmShareAt = now
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
