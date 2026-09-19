package com.vladimir.messenger.data.backup

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Копии азбуки адресов на телефонах роя (решение владельца, 2026-09-19:
 * «зачем ограничиваться только на своих… нужно сохранять копии рандомно,
 * так больше шансов им выжить»).
 *
 * Как это работает. Поверх уже работающей облачной копии каждый телефон
 * раздаёт СВОЮ зашифрованную азбуку ещё и [HOLDER_COUNT] случайным телефонам
 * роя. Случайность — устойчивая «жеребьёвка»: кандидаты сортируются по хешу
 * «мой узел + узел кандидата», первые 5 становятся хранителями. Набор стабилен
 * (копии не пересылаются туда-сюда), но ни от кого не зависит персонально;
 * выпавший хранитель заменяется следующим по жребию — сам, без сервера.
 * Рейтинг сознательно НЕ используется (решение владельца): у «лучших» общие
 * слабые места, случайный выбор независим.
 *
 * Конверт — тот же «APUADDBK1|…», что уходит на сервер: он зашифрован ключом
 * владельца, хранитель не может его прочитать — он просто хранит байты.
 *
 * Протокол (служебные конверты поверх обычной 1:1 доставки, как APUREAD1):
 *   `APUBK1|store|<владелец>|<конверт>`  — «держи мою копию»;
 *   `APUBK1|ack|<хранитель>|<владелец>`  — «копия у меня»;
 *   `APUBK1|ask|<проситель>`             — «дай мою копию» (сервер недоступен);
 *   `APUBK1|give|<хранитель>|<владелец>|<конверт>` — «вот твоя копия».
 *
 * У хранителя: один файл на владельца (filesDir/apu_swarm_backups/), не более
 * [MAX_OWNERS] владельцев и не дольше [OWNER_TTL_MS] — чтобы хранение было
 * посильным. Просроченное стирается само.
 */
@Singleton
class AddressBookSwarmBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val swarmPeerDirectory: SwarmPeerDirectory,
) {
    companion object {
        private const val TAG = "AddrBookSwarm"
        private const val PREFIX = "APUBK1"

        /** Сколько телефонов роя хранят копию (решение владельца: «хотя бы 5»). */
        const val HOLDER_COUNT = 5

        /** Конверт больше этого не раздаём по рою: 1:1-сообщение не должно
         *  упираться в потолок брокера (256 КБ) после запечатывания. */
        const val MAX_ENVELOPE_CHARS = 120_000

        private const val MAX_OWNERS = 20
        private const val OWNER_TTL_MS = 60L * 24 * 60 * 60 * 1000
        private const val RESEND_AFTER_MS = 12L * 60 * 60 * 1000
        private const val ACK_FRESH_MS = 48L * 60 * 60 * 1000
        private const val DIR_NAME = "apu_swarm_backups"
        private const val KEY_INDEX = "swarm_bk_index"
        private const val KEY_ACKS = "swarm_bk_acks"
        private const val KEY_SENDS = "swarm_bk_sends"
        private const val ASK_TIMEOUT_MS = 20_000L
        private const val MAX_ASK_PEERS = 40

        fun isSwarmEnvelope(text: String?): Boolean =
            text != null && text.startsWith("$PREFIX|")
    }

    private fun prefs() = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)

    private fun me(): String? =
        RustBridge.nodeId()?.takeIf { it.isNotBlank() }
            ?: prefs().getString("node_id", null)?.takeIf { it.isNotBlank() }

    // ── служебные JSON-карты в prefs ────────────────────────────────────────

    private fun jsonMap(key: String): MutableMap<String, Long> {
        val raw = prefs().getString(key, null) ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, Long>()
            for (k in json.keys()) out[k] = json.optLong(k, 0L)
            out
        }.getOrDefault(mutableMapOf())
    }

    private fun saveJsonMap(key: String, map: Map<String, Long>) {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        prefs().edit().putString(key, json.toString()).apply()
    }

    // ── жеребьёвка хранителей ───────────────────────────────────────────────

    private fun shaHex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Кандидаты: знакомые узлы (контакты/проверенные) + узлы из азбуки. */
    private suspend fun candidates(me: String): List<String> {
        val out = linkedSetOf<String>()
        runCatching { swarmPeerDirectory.audienceIds() }
            .onSuccess { out.addAll(it.filter { id -> id.isNotBlank() && id != me }) }
        runCatching {
            val file = AddressBookBackup.bookFile(context)
            if (file.isFile && file.length() > 4) {
                val entries = JSONObject(file.readText()).optJSONArray("entries") ?: return@runCatching
                for (i in 0 until entries.length()) {
                    val id = entries.optJSONObject(i)?.optString("id", "").orEmpty()
                        .removeSuffix("_public")
                    if (id.isNotBlank() && id != me) out.add(id)
                }
            }
        }
        return out.toList()
    }

    /**
     * Жеребьёвка: кандидаты сортируются по хешу «мой узел | кандидат» —
     * случайно для постороннего глаза, стабильно для всех телефонов (каждый
     * может посчитать список сам). Себя исключаем.
     */
    private fun elect(me: String, list: List<String>): List<String> =
        list.asSequence()
            .filter { it.isNotBlank() && it != me }
            .distinct()
            .sortedBy { shaHex("$me|$it") }
            .take(HOLDER_COUNT)
            .toList()

    // ── отправка ────────────────────────────────────────────────────────────

    private suspend fun send(to: String, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { RustBridge.sendMessage(UUID.randomUUID().toString(), to, to, text) }
            .getOrDefault(false)
    }

    // ── раздача своей копии ─────────────────────────────────────────────────

    /**
     * Разослать конверт хранителям. [force] — по кнопке (шлём всем избранным
     * без оглядки на паузы); иначе — только тем, кому давно не отправляли.
     *
     * @return человекочитаемая добавка к итогу копирования (пустая строка —
     * добавлять нечего).
     */
    suspend fun distribute(envelope: String, force: Boolean): String = withContext(Dispatchers.IO) {
        if (envelope.length > MAX_ENVELOPE_CHARS) {
            return@withContext "для телефонов роя велика (>120 КБ) — храним на сервере"
        }
        val me = me() ?: return@withContext ""
        val elected = elect(me, candidates(me))
        if (elected.isEmpty()) return@withContext "в рое пока нет других знакомых телефонов"
        val now = System.currentTimeMillis()
        val sends = jsonMap(KEY_SENDS)
        var sent = 0
        for (holder in elected) {
            val due = force || now - sends.getOrDefault(holder, 0L) > RESEND_AFTER_MS
            if (!due) continue
            if (send(holder, "$PREFIX|store|$me|$envelope")) {
                sends[holder] = now
                sent++
            }
        }
        if (sent > 0) saveJsonMap(KEY_SENDS, sends)
        when {
            sent == elected.size -> "роздана на $sent телефонах роя"
            sent > 0 -> "роздана на $sent из ${elected.size} телефонов роя, остальные недоступны"
            else -> "телефоны роя для копии недоступны, повторю позже"
        }
    }

    /** Сколько избранных хранителей подтверждали копию за последние сутки-двое. */
    fun freshAckCount(): Int {
        val me = me() ?: return 0
        val acks = jsonMap(KEY_ACKS)
        val now = System.currentTimeMillis()
        return acks.count { (holder, at) -> holder != me && now - at < ACK_FRESH_MS }
    }

    /** Ежечасный тик из фоновой петли: обновить копии у хранителей, если пора. */
    suspend fun hourlyTick(currentEnvelope: String?) {
        if (currentEnvelope.isNullOrBlank()) return
        runCatching { distribute(currentEnvelope, force = false) }
            .onFailure { Log.w(TAG, "swarm distribute: ${it.message}") }
    }

    // ── восстановление с телефонов роя ──────────────────────────────────────

    @Volatile private var asking: Boolean = false

    @Volatile private var pendingGive: String? = null

    /**
     * Спросить рой: «дай мою копию азбуки». Посылает [MAX_ASK_PEERS] знакомым
     * узлам запрос и ждёт первый конверт, который откроется ключом владельца
     * ([validator] — это расшифровка+проверка структуры у AddressBookBackup).
     *
     * @return конверт, или null — рой молчал/копий нет.
     */
    suspend fun askAndRestore(validator: (String) -> Boolean): String? = withContext(Dispatchers.IO) {
        val me = me() ?: return@withContext null
        val peers = candidates(me).take(MAX_ASK_PEERS)
        if (peers.isEmpty()) return@withContext null
        pendingGive = null
        asking = true
        try {
            for (peer in peers) send(peer, "$PREFIX|ask|$me")
            Log.i(TAG, "ask sent to ${peers.size} peers")
            val deadline = System.currentTimeMillis() + ASK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val give = pendingGive
                if (give != null) {
                    if (validator(give)) return@withContext give
                    pendingGive = null
                }
                delay(1000)
            }
        } finally {
            asking = false
            pendingGive = null
        }
        null
    }

    // ── роль хранителя: приём, хранение, отдача ─────────────────────────────

    private fun dir(): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun blobFile(owner: String): File =
        File(dir(), owner.replace(Regex("[^A-Za-z0-9_]"), "_") + ".bk")

    private fun storeBlob(owner: String, envelope: String) {
        runCatching {
            val file = blobFile(owner)
            val tmp = File(dir(), file.name + ".tmp")
            tmp.writeText(envelope)
            if (!tmp.renameTo(file)) {
                file.writeText(envelope)
                tmp.delete()
            }
            val index = jsonMap(KEY_INDEX)
            index[owner] = System.currentTimeMillis()
            // Просроченных стираем, лишних (сверх MAX_OWNERS) — самых старых.
            val now = System.currentTimeMillis()
            val alive = index.filterValues { now - it <= OWNER_TTL_MS }.toMutableMap()
            while (alive.size > MAX_OWNERS) {
                val oldest = alive.minByOrNull { it.value }?.key ?: break
                if (oldest == owner) break
                alive.remove(oldest)
                runCatching { blobFile(oldest).delete() }
            }
            index.clear(); index.putAll(alive)
            saveJsonMap(KEY_INDEX, index)
        }.onFailure { Log.w(TAG, "store blob: ${it.message}") }
    }

    // ── разбор входящих конвертов (вызывается из петли сервиса) ─────────────

    /**
     * Разобрать служебный конверт рой-копии. true — конверт наш, дальше по
     * цепочке (в чат, в автоконтакты) его не пускать.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!isSwarmEnvelope(text)) return false
        val me = me()
        if (me.isNullOrBlank()) return true
        val parts = text.split("|")
        when (parts.getOrNull(1)) {
            "store" -> {
                // APUBK1|store|<владелец>|<конверт с «|» внутри>
                val owner = parts.getOrNull(2).orEmpty()
                val envelope = parts.drop(3).joinToString("|")
                if (owner.isBlank() || senderId != owner) return true
                if (envelope.startsWith(AddressBookBackup.MAGIC) &&
                    envelope.length <= MAX_ENVELOPE_CHARS
                ) {
                    storeBlob(owner, envelope)
                    send(senderId, "$PREFIX|ack|$me|$owner")
                }
                return true
            }
            "ack" -> {
                if (parts.size != 4) return true
                val holder = parts[2]
                val owner = parts[3]
                if (owner == me && holder != me) {
                    val acks = jsonMap(KEY_ACKS)
                    acks[holder] = System.currentTimeMillis()
                    saveJsonMap(KEY_ACKS, acks)
                    Log.i(TAG, "copy ack from ${holder.takeLast(8)}")
                }
                return true
            }
            "ask" -> {
                // APUBK1|ask|<проситель> — отдаём ТОЛЬКО просителю его копию.
                if (parts.size != 3) return true
                val asker = parts[2]
                if (senderId != asker) return true
                val blob = runCatching { blobFile(asker).readText() }.getOrNull()
                if (blob != null && blob.startsWith(AddressBookBackup.MAGIC)) {
                    send(asker, "$PREFIX|give|$me|$asker|$blob")
                    Log.i(TAG, "gave copy to ${asker.takeLast(8)}")
                }
                return true
            }
            "give" -> {
                // APUBK1|give|<хранитель>|<владелец>|<конверт>
                val owner = parts.getOrNull(3).orEmpty()
                val envelope = parts.drop(4).joinToString("|")
                if (owner == me && asking) pendingGive = envelope
                return true
            }
            else -> return true
        }
    }
}
