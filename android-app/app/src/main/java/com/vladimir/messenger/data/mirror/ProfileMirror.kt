package com.vladimir.messenger.data.mirror

// =============================================================================
// PROFILEMIRROR.KT — живой профиль: устройства одной личности всегда одинаковые
// =============================================================================
// Раунд 246 (владелец: «синхронизация профиля между устройствами онлайн;
// проверить, весь ли функционал синхронизируется и находит ли расхождения
// онлайн; всё безшовно»).
//
// Что входит в профиль: имя, @никнейм, тема (день/ночь/авто), аватар и обои.
// Раньше это разъезжалось по устройствам только полной копией (.apubak) с
// перезапуском; живое зеркало (MirrorSync) гоняло сообщения и действия, но не
// профиль. Теперь профиль едет тем же зеркальным каналом:
//  - любое изменение поля публикуется партнёру («profstate»);
//  - в hello/heartbeat идёт отпечаток профиля («pd»): увидев чужой отпечаток,
//    устройство само запрашивает полный снимок и находит расхождения;
//  - слияние по полям, last-write-wins по меткам времени (при равенстве -
//    детерминированный тай-брейк по значению, чтобы оба устройства сошлись);
//  - аватар и обои переносятся байтами («reqpbytes»/«pbytes»), как гифки.
// Применение тихо пишет в те же prefs/холдеры, поэтому UI оживает сам.
// В диагностике («Настройки → Диагностика синхронизации») видна строка
// «профиль: …» - совпадает или какие поля расходятся.
// =============================================================================

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.vladimir.messenger.ui.theme.AvatarHolder
import com.vladimir.messenger.ui.theme.ThemeMode
import com.vladimir.messenger.ui.theme.ThemeModeHolder
import com.vladimir.messenger.ui.theme.UsernameHolder
import com.vladimir.messenger.ui.theme.WallpaperHolder
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

object ProfileMirror {

    private const val TAG = "ProfileMirror"

    const val FIELD_NAME = "name"
    const val FIELD_NICK = "nick"
    const val FIELD_THEME = "theme"
    const val FIELD_AV = "av"
    const val FIELD_WALL = "wall"

    private const val PREFS = "apu_profile_mirror"
    private const val MAIN_PREFS = "p2p_prefs"

    /** Потолок картинки профиля, гонимой байтами (как у гифок в зеркале). */
    const val IMAGE_MAX_BYTES = 8L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Сейчас применяем кадр партнёра: холдеры зовут [noteLocalChange] из set(),
     * и без флага применённое значение полетело бы обратно петлёй.
     */
    @Volatile
    var applyingRemote = false
        private set

    /** Снимок профиля партнёра из последнего кадра «profstate» (диагностика). */
    @Volatile
    var partnerState: State? = null
        private set

    /** Отпечаток профиля партнёра из hello/hb (без полного снимка). */
    @Volatile
    var partnerDigest: String = ""
        private set

    fun notePartnerDigest(digest: String) {
        if (digest.isNotBlank()) partnerDigest = digest
    }

    // ── Снимок профиля ──────────────────────────────────────────────────────

    data class State(
        val name: String,
        val nick: String,
        val theme: String,
        val avName: String,
        val avSha: String,
        val wallName: String,
        val wallSha: String,
        val tName: Long,
        val tNick: Long,
        val tTheme: Long,
        val tAv: Long,
        val tWall: Long,
    ) {
        /** Отпечаток СОДЕРЖИМОГО (метки времени не входят): им сравниваются устройства. */
        fun digest(): String = sha256(
            listOf(name, nick, theme, avSha, wallSha).joinToString("|")
        ).take(32)

        /** Человеко-читаемые поля, где содержимое расходится. */
        fun contentDiff(other: State): List<String> {
            val diff = ArrayList<String>(3)
            if (name != other.name) diff += "имя"
            if (nick != other.nick) diff += "никнейм"
            if (theme != other.theme) diff += "тема"
            if (avSha != other.avSha) diff += "аватар"
            if (wallSha != other.wallSha) diff += "обои"
            return diff
        }

        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("nick", nick)
            .put("theme", theme)
            .put("avn", avName)
            .put("avs", avSha)
            .put("waln", wallName)
            .put("wals", wallSha)
            .put("tname", tName)
            .put("tnick", tNick)
            .put("ttheme", tTheme)
            .put("tav", tAv)
            .put("twall", tWall)

        companion object {
            fun fromJson(o: JSONObject): State = State(
                name = o.optString("name", ""),
                nick = o.optString("nick", ""),
                theme = o.optString("theme", ""),
                avName = o.optString("avn", ""),
                avSha = o.optString("avs", ""),
                wallName = o.optString("waln", ""),
                wallSha = o.optString("wals", ""),
                tName = o.optLong("tname", 0L),
                tNick = o.optLong("tnick", 0L),
                tTheme = o.optLong("ttheme", 0L),
                tAv = o.optLong("tav", 0L),
                tWall = o.optLong("twall", 0L),
            )
        }
    }

    /**
     * Текущий профиль этого устройства. [localize] - дополнительно привести
     * аватар/обои к локальным файлам (иначе пресет или картинка из галереи
     * остались бы URI, который второму телефону недоступен).
     */
    fun snapshot(context: Context, localize: Boolean): State {
        val app = context.applicationContext
        val main = app.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)
        val upd = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (localize) {
            localizeImage(app, FIELD_AV)
            localizeImage(app, FIELD_WALL)
        }
        val (avName, avSha) = imageState(app, FIELD_AV)
        val (wallName, wallSha) = imageState(app, FIELD_WALL)
        return State(
            name = main.getString("display_name", "") ?: "",
            nick = main.getString("my_username", "") ?: "",
            theme = main.getString("theme_mode", "") ?: "",
            avName = avName,
            avSha = avSha,
            wallName = wallName,
            wallSha = wallSha,
            tName = upd.getLong("upd_" + FIELD_NAME, 0L),
            tNick = upd.getLong("upd_" + FIELD_NICK, 0L),
            tTheme = upd.getLong("upd_" + FIELD_THEME, 0L),
            tAv = upd.getLong("upd_" + FIELD_AV, 0L),
            tWall = upd.getLong("upd_" + FIELD_WALL, 0L),
        )
    }

    @Volatile private var cachedDigest: String = ""
    @Volatile private var cachedAt: Long = 0L

    /** Отпечаток для hello/hb: лёгкий кэш, чтобы не хэшировать на каждом кадре. */
    fun cachedDigest(context: Context): String {
        val now = System.currentTimeMillis()
        if (cachedDigest.isEmpty() || now - cachedAt > 15_000L) {
            cachedDigest = snapshot(context, localize = false).digest()
            cachedAt = now
        }
        return cachedDigest
    }

    private fun invalidateCache() {
        cachedDigest = ""
        cachedAt = 0L
    }

    // ── Локальные изменения ─────────────────────────────────────────────────

    /**
     * Зовётся холдерами и настройками сразу после записи поля. Ставит метку
     * времени поля и публикует снимок партнёру (с дебаунсом).
     */
    fun noteLocalChange(context: Context, field: String) {
        if (applyingRemote) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("upd_" + field, System.currentTimeMillis())
            .apply()
        invalidateCache()
        publishSoon(context)
    }

    @Volatile private var publishJob: Job? = null

    /** Дебаунс 0.8 с: серия правок имени уезжает одним кадром. */
    fun publishSoon(context: Context) {
        publishJob?.cancel()
        val app = context.applicationContext
        publishJob = scope.launch {
            delay(800)
            publishNow(app)
        }
    }

    /** Собрать снимок (с локализацией картинок) и отдать зеркальному каналу. */
    fun publishNow(context: Context) {
        val state = snapshot(context, localize = true)
        invalidateCache()
        MirrorHub.publishProfileState(state.toJson().toString())
    }

    // ── Применение кадра партнёра ───────────────────────────────────────────

    /**
     * Пришёл «profstate». Сливаем поля (last-write-wins), возвращаем виды
     * картинок, байты которых нужно запросить («av»/«wall»).
     */
    fun onPartnerState(context: Context, incoming: State): List<String> {
        val app = context.applicationContext
        partnerState = incoming
        partnerDigest = incoming.digest()
        val mine = snapshot(app, localize = false)
        val needBytes = ArrayList<String>(2)
        applyingRemote = true
        try {
            // Текст: имя, никнейм, тема.
            if (wins(incoming.tName, incoming.name, mine.tName, mine.name)) {
                app.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)
                    .edit().putString("display_name", incoming.name).apply()
                stamp(app, FIELD_NAME, incoming.tName)
            }
            if (wins(incoming.tNick, incoming.nick, mine.tNick, mine.nick)) {
                UsernameHolder.set(app, incoming.nick.ifBlank { null })
                stamp(app, FIELD_NICK, incoming.tNick)
            }
            if (wins(incoming.tTheme, incoming.theme, mine.tTheme, mine.theme)) {
                ThemeModeHolder.set(app, ThemeMode.fromStored(incoming.theme.ifBlank { null }))
                stamp(app, FIELD_THEME, incoming.tTheme)
            }
            // Картинки: решаем, чья версия побеждает; байты придут отдельно.
            if (incoming.avSha != mine.avSha &&
                wins(incoming.tAv, incoming.avSha, mine.tAv, mine.avSha)
            ) {
                stamp(app, FIELD_AV, incoming.tAv)
                if (incoming.avName.isBlank() || incoming.avSha.isBlank()) {
                    AvatarHolder.set(app, null) // у партнёра аватара нет - снимаем и здесь
                } else {
                    needBytes += FIELD_AV
                }
            }
            if (incoming.wallSha != mine.wallSha &&
                wins(incoming.tWall, incoming.wallSha, mine.tWall, mine.wallSha)
            ) {
                stamp(app, FIELD_WALL, incoming.tWall)
                if (incoming.wallName.isBlank() || incoming.wallSha.isBlank()) {
                    WallpaperHolder.set(app, null)
                } else {
                    needBytes += FIELD_WALL
                }
            }
        } finally {
            applyingRemote = false
        }
        invalidateCache()
        return needBytes
    }

    /**
     * Байты картинки профиля от партнёра: кладём файл в каноническую папку и
     * вешаем холдер на него. Имя файла берём то же, что у отправителя.
     */
    fun storeRemoteImage(context: Context, kind: String, name: String, bytes: ByteArray): Boolean {
        val app = context.applicationContext
        if (bytes.size.toLong() > IMAGE_MAX_BYTES) return false
        if (!isSafeName(name)) return false
        val dir = dirFor(app, kind)
        val file = File(dir, name)
        return runCatching {
            dir.mkdirs()
            file.writeBytes(bytes)
            val uri = fileProviderUri(app, file)
            applyingRemote = true
            try {
                if (kind == FIELD_AV) AvatarHolder.set(app, uri) else WallpaperHolder.set(app, uri)
            } finally {
                applyingRemote = false
            }
            invalidateCache()
            Log.i(TAG, "profile $kind applied: ${bytes.size} B")
            true
        }.onFailure { Log.w(TAG, "storeRemoteImage($kind) failed: ${it.message}") }
            .getOrDefault(false)
    }

    /** Отдать файл картинки профиля (для «reqpbytes»). */
    fun imageFile(context: Context, kind: String, name: String): File? {
        if (!isSafeName(name)) return null
        val file = File(dirFor(context.applicationContext, kind), name)
        return if (file.isFile && file.length() in 1..IMAGE_MAX_BYTES) file else null
    }

    // ── Диагностика ─────────────────────────────────────────────────────────

    /**
     * Строка в «Диагностику синхронизации»: совпадает ли профиль с партнёрским
     * устройством, а если нет - какие поля расходятся.
     */
    fun diagLine(context: Context): String {
        val partner = partnerState
        if (partner == null) {
            return "профиль: партнёрское устройство не видно"
        }
        val diff = snapshot(context, localize = false).contentDiff(partner)
        return if (diff.isEmpty()) {
            "профиль: совпадает с партнёрским устройством"
        } else {
            "профиль: расхождения - " + diff.joinToString(", ") + " (досылаются сами)"
        }
    }

    // ── Внутреннее ──────────────────────────────────────────────────────────

    /**
     * Чья версия поля побеждает: новее по метке; при равных метках - большее
     * значение (детерминированно на обоих устройствах, иначе каждое считало
     * бы правым себя и профиль бы не сошёлся).
     */
    private fun wins(inTs: Long, inVal: String, myTs: Long, myVal: String): Boolean =
        if (inVal == myVal) false
        else if (inTs != myTs) inTs > myTs
        else inVal > myVal

    private fun stamp(context: Context, field: String, ts: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("upd_" + field, ts).apply()
    }

    private fun dirFor(context: Context, kind: String): File = when (kind) {
        FIELD_AV -> File(context.filesDir, "avatar")
        else -> File(context.filesDir, "wallpaper")
    }

    /** Имя и отпечаток канонического файла картинки поля (пусто - картинки нет). */
    private fun imageState(context: Context, kind: String): Pair<String, String> {
        val uri = if (kind == FIELD_AV) AvatarHolder.uri.value else WallpaperHolder.uri.value
        if (uri.isNullOrBlank()) return "" to ""
        val name = Uri.parse(uri).lastPathSegment ?: return "" to ""
        val file = File(dirFor(context, kind), name)
        if (!file.isFile) return name to ""
        return name to sha256Of(file)
    }

    /**
     * Аватар-пресет (android.resource://) или обои из галереи живут URI,
     * который второму телефону недоступен. Копируем байты в каноническую папку
     * и перевешиваем холдер на FileProvider-URI локального файла.
     */
    private fun localizeImage(context: Context, kind: String) {
        val uri = (if (kind == FIELD_AV) AvatarHolder.uri.value else WallpaperHolder.uri.value)
            ?: return
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
        val scheme = parsed.scheme ?: return
        val dir = dirFor(context, kind)
        val localName = parsed.lastPathSegment.orEmpty()
        val alreadyLocal = (scheme == "content" || scheme == "file") &&
            localName.isNotEmpty() && File(dir, localName).isFile &&
            runCatching {
                val path = if (scheme == "file") parsed.path.orEmpty() else ""
                path.isEmpty() || path.startsWith(context.filesDir.path)
            }.getOrDefault(false)
        if (alreadyLocal) return
        if (scheme == "content" && localName.isNotEmpty() && File(dir, localName).isFile) {
            // FileProvider-URI нашего же файла (аватар после кропа) - уже локален.
            return
        }
        runCatching {
            val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: return
            if (bytes.isEmpty() || bytes.size.toLong() > IMAGE_MAX_BYTES) return
            dir.mkdirs()
            val stampMs = System.currentTimeMillis()
            val name = (if (kind == FIELD_AV) "sync_" else "wall_") + stampMs + ".jpg"
            val file = File(dir, name)
            file.writeBytes(bytes)
            val localUri = fileProviderUri(context, file)
            applyingRemote = true
            try {
                if (kind == FIELD_AV) AvatarHolder.set(context, localUri)
                else WallpaperHolder.set(context, localUri)
            } finally {
                applyingRemote = false
            }
            stamp(context, kind, stampMs)
            Log.i(TAG, "profile $kind localized: ${bytes.size} B -> $name")
        }.onFailure { Log.w(TAG, "localize $kind failed: ${it.message}") }
    }

    private fun fileProviderUri(context: Context, file: File): String =
        FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        ).toString()

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 120 &&
            !name.contains('/') && !name.contains('\\') && !name.contains("..") &&
            name.all { it.isLetterOrDigit() || it in "._-" }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }

    private fun sha256Of(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { String.format("%02x", it) }
    }.getOrDefault("")
}
