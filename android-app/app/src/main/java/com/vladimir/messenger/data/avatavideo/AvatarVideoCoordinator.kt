package com.vladimir.messenger.data.avatavideo

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.profile.VideoAvatarStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Обмен видеоаватарами между контактами: анонс своей формы и ролика, ответ на
 * просьбу, приём и повторы (docs/VIDEO_AVATAR.md).
 *
 * Схема повторяет историю видео у историй, но без их веера аудиторий: у
 * видеоаватара одна аудитория — контакты, как и у обычного аватара.
 *
 *  1. Владелец анонсирует sha256, размер и круг/квадрат ([AvatarVideoWire.Announce])
 *     каждому контакту тем же прямым конвертом, чем ходят сбросы адресной книги.
 *  2. Контакт, у которого ролика ещё нет, отвечает готовностью ([AvatarVideoWire.Ready]).
 *  3. Владелец отдаёт файл обычной файловой передачей с зарезервированным именем
 *     `apu-avatar-video-…mp4`; строки передачи не попадают в чат и хранителям не отдаются.
 *  4. Приём проверяет sha256 и размер и ставит ролик в [PeerVideoAvatarStore].
 *
 * Старые клиенты не знают вид `avv1`: конверт проглатывается как неразобранный
 * групповой пакет, а единственный видимый им слой — обычный постер-аватар.
 */
@Singleton
class AvatarVideoCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val transferDao: FileTransferDao,
    private val preparation: OutgoingFilePreparationService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Первый запуск приложения: догоняющие просьбы, анонс, уборка, повторы. */
    fun start() {
        // Application стартует до MainActivity, а своё видео живёт в
        // VideoAvatarStore, который до MainActivity не инициализирован: первый
        // анонс уходил бы «ролика нет» и оставлял у друзей постер («статичная
        // картинка»). init идемпотентен — второй вызов из MainActivity не мешает.
        VideoAvatarStore.init(context)
        AvatarVideoGossip.attach(this)
        PeerVideoAvatarStore.sweep(context)
        scope.launch {
            catchUpRequests()
            announceAll(force = true)
            while (true) {
                delay(RESYNC_EVERY_MS)
                runCatching {
                    catchUpRequests()
                    announceAll(force = false)
                }.onFailure { Log.w(TAG, "video avatar resync failed: ${it.message}") }
            }
        }
        // Кольцо загрузки у собеседников: пока хоть один ролик ждём, следим за
        // прогрессом входящих передач; когда ждать нечего — цикл почти бесплатен.
        scope.launch {
            while (true) {
                delay(DOWNLOAD_POLL_MS)
                runCatching { pumpDownloads() }
                    .onFailure { Log.w(TAG, "video avatar download poll failed: ${it.message}") }
            }
        }
    }

    /** Своё видео сменили — анонс уходит сразу, без ожидания фонового цикла. */
    fun onMyVideoChanged() {
        scope.launch {
            runCatching { announceAll(force = true) }
                .onFailure { Log.w(TAG, "video avatar announce failed: ${it.message}") }
        }
    }

    /**
     * Входящий конверт видеоаватара. true — конверт наш и поглощён (так и
     * битый: мусор не должен стать сообщением в чате).
     */
    fun receive(senderId: String, text: String): Boolean {
        val packet = AvatarVideoWire.parse(text) ?: return true
        scope.launch {
            runCatching {
                when (packet) {
                    is AvatarVideoWire.Packet.Announce -> onAnnounce(senderId, packet)
                    is AvatarVideoWire.Packet.Ready -> onReady(senderId, packet)
                }
            }.onFailure { Log.w(TAG, "video avatar packet failed from $senderId: ${it.message}") }
        }
        return true
    }

    private suspend fun onAnnounce(senderId: String, packet: AvatarVideoWire.Packet.Announce) {
        PeerVideoAvatarStore.bump(context, "announce-in")
        // Объявлять чужое видео нельзя: ролик всегда от его владельца.
        if (packet.ownerId != senderId) {
            Log.w(TAG, "video avatar announce for someone else dropped")
            return
        }
        val need = PeerVideoAvatarStore.noteAnnounce(
            context, senderId, packet.sha256, packet.bytes, packet.circle, packet.updatedAtMs,
        )
        if (need) requestVideo(senderId, packet.sha256)
        // Собеседник жив и говорит о видео — заодно освежить своё, если просрочено.
        announceAll(force = false)
    }

    private suspend fun onReady(senderId: String, packet: AvatarVideoWire.Packet.Ready) {
        // Просьба адресована владельцу ролика. Клиенты до v11.74.491 клали в
        // поле свой id — принимаем и такой конверт, чтобы просьба не терялась
        // ни в какой паре сборок.
        if (packet.ownerId != myNodeId() && packet.ownerId != senderId) return
        PeerVideoAvatarStore.bump(context, "ready-in")
        sendMyVideoTo(senderId, packet.sha256)
    }

    /** Попросить ролик: повторная просьба ограничена, чтобы не долбить сеть. */
    private suspend fun requestVideo(ownerId: String, sha256: String) {
        if (sha256.isEmpty()) return
        val now = System.currentTimeMillis()
        // Ключ — по ролику, а не только по владельцу: новый видеоаватар
        // запрашивается сразу, не дожидаясь конца таймаута по старому.
        val key = "$READY_AT_PREFIX$ownerId:$sha256"
        if (now - prefs.getLong(key, 0L) < READY_THROTTLE_MS) return
        prefs.edit().putLong(key, now).apply()
        // Поле «владелец» в просьбе — ЧЕЙ ролик просим (получатель просьбы).
        // Раньше сюда клали свой id, и владелец в onReady молча отбрасывал
        // просьбу: файл не уходил никогда, у друзей оставался постер.
        val wire = AvatarVideoWire.ready(ownerId, sha256)
        runCatching { RustBridge.sendMessage(messageId("avatar-video-ready"), ownerId, ownerId, wire) }
            .onSuccess { PeerVideoAvatarStore.bump(context, "ready-out") }
            .onFailure { Log.w(TAG, "video avatar ready send failed: ${it.message}") }
    }

    /** Отдать свой ролик собеседнику обычной файловой передачей (как у историй). */
    private suspend fun sendMyVideoTo(peerId: String, sha256: String) {
        val mine = myVideoMeta() ?: return
        if (mine.sha256 != sha256) return // просят не то, что сейчас стоит
        val file = mine.path.let(::File).takeIf { it.isFile } ?: return
        val messageId = "avatar-video:${sha256.take(16)}:$peerId"
        val now = System.currentTimeMillis()
        // Одна строка передачи на (ролик, собеседника): повторная просьба не
        // плодит копии; законченная или истёкшая передача не мешает новой.
        val existing = transferDao.getOutgoingForMessage(messageId)
        if (existing.any { it.state != "FAILED" && it.state != "CANCELLED" && it.expiresAtMs > now }) return
        runCatching {
            preparation.prepareFromFile(
                source = file,
                displayName = AvatarVideoWire.fileName(sha256),
                mediaType = "video/mp4",
                messageId = messageId,
                chatId = peerId,
                recipientNodeId = peerId,
                nowMs = now,
                expiresAtMs = now + TRANSFER_TTL_MS,
            )
        }
            .onSuccess { PeerVideoAvatarStore.bump(context, "file-out") }
            .onFailure { Log.w(TAG, "video avatar send prepare failed: ${it.message}") }
    }

    /** Объявить свой текущий видеоаватар (или его отсутствие) всем контактам. */
    private suspend fun announceAll(force: Boolean) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(ANNOUNCE_AT_KEY, 0L)
        if (!force && now - last < ANNOUNCE_THROTTLE_MS) return
        val myId = myNodeId() ?: return
        val mine = myVideoMeta()
        val wire = AvatarVideoWire.announce(
            ownerId = myId,
            sha256 = mine?.sha256.orEmpty(),
            bytes = mine?.bytes ?: 0L,
            circle = mine?.circle ?: true,
            updatedAtMs = mine?.updatedAtMs ?: now,
        )
        val ids = runCatching { contactDao.allIds() }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        prefs.edit().putLong(ANNOUNCE_AT_KEY, now).apply()
        ids.forEach { peer ->
            runCatching { RustBridge.sendMessage(messageId("avatar-video-announce"), peer, peer, wire) }
                .onSuccess { PeerVideoAvatarStore.bump(context, "announce-out") }
                .onFailure { Log.w(TAG, "video avatar announce to $peer failed: ${it.message}") }
        }
    }

    /** Ролики, до которых руки не дошли: повторить просьбы владельцам. */
    private suspend fun catchUpRequests() {
        PeerVideoAvatarStore.missing(context).forEach { meta ->
            requestVideo(meta.ownerId, meta.sha256)
        }
    }

    /**
     * Прогресс скачивания роликов собеседников — для кольца загрузки вокруг
     * аватара. По объявленному sha ищем активную входящую передачу и меряем её
     * байты; пока строки нет (просьба ушла, файл ещё не пошёл) — прогресса нет.
     */
    private suspend fun pumpDownloads() {
        val missing = PeerVideoAvatarStore.missing(context)
        if (missing.isEmpty()) {
            PeerVideoAvatarStore.setDownloads(emptyMap())
            return
        }
        val incoming = runCatching { transferDao.getActiveIncoming(System.currentTimeMillis()) }
            .getOrDefault(emptyList())
        val map = HashMap<String, Float>(missing.size)
        for (meta in missing) {
            val row = incoming.firstOrNull { it.displayName == AvatarVideoWire.fileName(meta.sha256) }
            if (row != null && row.totalBytes > 0L) {
                map[meta.ownerId] = (row.transferredBytes.toFloat() / row.totalBytes).coerceIn(0f, 1f)
            }
        }
        PeerVideoAvatarStore.setDownloads(map)
    }

    /** Текущее состояние СВОЕГО видеоаватара; null — его нет (остался постер). */
    private fun myVideoMeta(): MyVideo? {
        val state = VideoAvatarStore.snapshot()
        val path = state.path ?: return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        val mtime = file.lastModified()
        val cachedSha = prefs.getString(MY_SHA_KEY, null)
        val cachedFor = prefs.getLong(MY_SHA_FOR_KEY, 0L)
        val sha = if (cachedSha != null && cachedFor == mtime && cachedSha.isNotEmpty()) {
            cachedSha
        } else {
            // Ролик свой, предел тот же, что при установке: читаем потоком.
            val computed = PeerVideoAvatarStore.sha256Of(file) ?: return null
            prefs.edit().putString(MY_SHA_KEY, computed).putLong(MY_SHA_FOR_KEY, mtime).apply()
            computed
        }
        return MyVideo(sha, file.length(), state.circle, mtime, path)
    }

    private fun myNodeId(): String? = RustBridge.nodeId()

    /**
     * Идентификатор конверта — новый на каждую отправку. С постоянным id ядро
     * и приёмник считают следующий конверт повтором уже доставленного пакета и
     * молча проглатывают: до друзей доходил только самый первый анонс, а после
     * него настоящий ролик никогда не запрашивался — так и оставалась
     * «статичная картинка».
     */
    private fun messageId(prefix: String): String = "$prefix:${UUID.randomUUID()}"

    private data class MyVideo(
        val sha256: String,
        val bytes: Long,
        val circle: Boolean,
        val updatedAtMs: Long,
        val path: String,
    )

    private companion object {
        const val TAG = "AvatarVideo"
        const val PREFS = "avatar-video-gossip-v1"
        const val ANNOUNCE_AT_KEY = "lastAnnounceAllAtMs"
        const val READY_AT_PREFIX = "lastReadyAtMs:"
        const val MY_SHA_KEY = "myVideoSha"
        const val MY_SHA_FOR_KEY = "myVideoShaForMtime"
        const val ANNOUNCE_THROTTLE_MS = 30L * 60 * 1000
        const val READY_THROTTLE_MS = 15L * 60 * 1000
        /** Шаг опроса прогресса скачивания для кольца загрузки. */
        const val DOWNLOAD_POLL_MS = 750L
        const val RESYNC_EVERY_MS = 6L * 60 * 60 * 1000
        /** Срок строки передачи — сутки, как у файла видеоистории. */
        const val TRANSFER_TTL_MS = 24L * 60 * 60 * 1000
    }
}
