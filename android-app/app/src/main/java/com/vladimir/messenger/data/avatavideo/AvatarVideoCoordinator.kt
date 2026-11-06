package com.vladimir.messenger.data.avatavideo

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.file.ReceivedFileStore
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.profile.VideoAvatarStore
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.service.CoreStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val fileRouter: Provider<FileTransferRouter>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val started = AtomicBoolean(false)
    private val requestMutex = Mutex()
    private val announceMutex = Mutex()
    private val sendMutex = Mutex()

    /** Первый запуск приложения: восстановление, анонсы и ограниченные повторы. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Application стартует до MainActivity и до ядра: сначала загрузить
        // своё видео, а сетевые анонсы и просьбы посылать только после запуска.
        VideoAvatarStore.init(context)
        AvatarVideoGossip.attach(this)
        PeerVideoAvatarStore.sweep(context)
        scope.launch {
            try {
                restoreCompletedVideos()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "video avatar recovery failed", failure)
            }
            CoreStatus.ready.collect { ready ->
                if (ready && RustBridge.isRunning()) {
                    try {
                        catchUpRequests()
                        announceAll(force = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(TAG, "video avatar startup sync failed", failure)
                    }
                }
            }
        }
        // Повтор на случай недоступной сети/ключа или пропавшего пакета.
        // Ограничение на каждую пару контакт+ролик — внутри requestVideo.
        scope.launch {
            while (true) {
                delay(RETRY_PUMP_MS)
                if (!RustBridge.isRunning()) continue
                try {
                    catchUpRequests()
                    announceAll(force = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(TAG, "video avatar resync failed", failure)
                }
            }
        }
        // Room сам сообщает об изменении байтов; в простое нет опроса каждые
        // 750 мс. Метаданные тоже сигналят, чтобы кольцо появилось сразу.
        scope.launch {
            try {
                combine(
                    PeerVideoAvatarStore.changed,
                    transferDao.observeAvatarVideoIncoming().distinctUntilChanged(),
                ) { _, rows -> rows }.conflate().collect { rows -> pumpDownloads(rows) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "video avatar progress observer failed", failure)
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
        if (!AvatarVideoTransferPolicy.acceptsReady(packet.ownerId, senderId, myNodeId())) return
        // Ролик объявляется и выдаётся только контактам, даже если посторонний
        // узнал его sha или контакт уже был удалён из адресной книги.
        if (contactDao.getContactById(senderId) == null) return
        PeerVideoAvatarStore.bump(context, "ready-in")
        sendMyVideoTo(senderId, packet.sha256)
    }

    /** Попросить ролик: ограничение действует на каждую пару владелец+sha. */
    private suspend fun requestVideo(ownerId: String, sha256: String) = requestMutex.withLock {
        if (sha256.isEmpty() || !RustBridge.isRunning()) return@withLock
        val now = System.currentTimeMillis()
        val key = "$READY_AT_PREFIX$ownerId:$sha256"
        val last = prefs.getLong(key, 0L)
        if (last > 0L && now >= last && now - last < READY_THROTTLE_MS) return@withLock
        // Поле «владелец» — ЧЕЙ ролик просим. До этого исправления здесь
        // отправлялся id просителя, и владелец молча отбрасывал просьбу.
        val wire = AvatarVideoWire.ready(ownerId, sha256)
        val accepted = try {
            RustBridge.sendMessage(messageId("avatar-video-ready"), ownerId, ownerId, wire)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "video avatar ready send failed", failure)
            false
        }
        // false может означать отсутствие ключа или прямого маршрута: это не
        // успех. Следующая попытка ограничена по времени, но не потеряна.
        prefs.edit().putLong(key, now).apply()
        PeerVideoAvatarStore.bump(context, if (accepted) "ready-out" else "ready-deferred")
        if (!accepted) requestMissingMessageBinding(ownerId)
    }

    /** Отдать свой ролик собеседнику обычной файловой передачей (как у историй). */
    private suspend fun sendMyVideoTo(peerId: String, sha256: String) {
        val action = sendMutex.withLock {
            val mine = myVideoMeta() ?: return@withLock SendAction.NONE
            if (mine.sha256 != sha256) return@withLock SendAction.NONE
            val file = mine.path.let(::File).takeIf { it.isFile } ?: return@withLock SendAction.NONE
            val messageId = "avatar-video:${sha256.take(16)}:$peerId"
            val now = System.currentTimeMillis()
            // Под замком две просьбы не подготовят две копии. Завершённая,
            // истёкшая или брошенная при подготовке строка не блокирует повтор.
            val existing = transferDao.getOutgoingForMessage(messageId).firstOrNull {
                AvatarVideoTransferPolicy.inFlight(it.state, it.expiresAtMs, it.updatedAtMs, now)
            }
            if (existing != null) {
                return@withLock if (existing.state == "WAITING_RECIPIENT") SendAction.RESUME else SendAction.NONE
            }
            try {
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
                PeerVideoAvatarStore.bump(context, "file-out")
                SendAction.PUMP
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "video avatar send prepare failed", failure)
                if (failure.message.orEmpty().contains("binding is not pinned")) {
                    // Первый обмен: запросить файловый HELLO и дождаться
                    // следующей просьбы, вместо вечного молчаливого отказа.
                    try {
                        fileRouter.get().requestExchangeBinding(peerId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (helloFailure: Exception) {
                        Log.w(TAG, "video avatar key request failed", helloFailure)
                    }
                }
                SendAction.NONE
            }
        }
        try {
            when (action) {
                SendAction.PUMP -> fileRouter.get().pumpOutgoing()
                SendAction.RESUME -> fileRouter.get().resumeWaitingForRecipient()
                SendAction.NONE -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "video avatar file pump failed", failure)
        }
    }

    /** Объявить свой текущий видеоаватар (или его отсутствие) контактам. */
    private suspend fun announceAll(force: Boolean) = announceMutex.withLock {
        if (!RustBridge.isRunning()) return@withLock
        val myId = myNodeId() ?: return@withLock
        val now = System.currentTimeMillis()
        val mine = myVideoMeta()
        val wire = AvatarVideoWire.announce(
            ownerId = myId,
            sha256 = mine?.sha256.orEmpty(),
            bytes = mine?.bytes ?: 0L,
            circle = mine?.circle ?: true,
            updatedAtMs = mine?.updatedAtMs ?: now,
        )
        val ids = contactDao.allIds()
        for (peer in ids) {
            if (peer.isBlank() || peer == myId) continue
            val key = "$ANNOUNCE_AT_PREFIX$peer"
            if (!force && now < prefs.getLong(key, 0L)) continue
            val accepted = try {
                RustBridge.sendMessage(messageId("avatar-video-announce"), peer, peer, wire)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "video avatar announce failed for $peer", failure)
                false
            }
            // Неуспех одного контакта не задерживает остальных на полчаса.
            val retry = if (accepted) ANNOUNCE_THROTTLE_MS else READY_THROTTLE_MS
            prefs.edit().putLong(key, now + retry).apply()
            PeerVideoAvatarStore.bump(context, if (accepted) "announce-out" else "announce-deferred")
            if (!accepted) requestMissingMessageBinding(peer)
        }
    }

    /** Нельзя даже отправить анонс/просьбу без ключа шифрования сообщения. */
    private suspend fun requestMissingMessageBinding(peerId: String) {
        if (MessageSealer.canSeal(context, peerId)) return
        try {
            fileRouter.get().requestExchangeBinding(peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "video avatar message key request failed", failure)
        }
    }

    /** Ролики, до которых руки не дошли: повторить просьбы владельцам. */
    private suspend fun catchUpRequests() {
        PeerVideoAvatarStore.missing(context).forEach { meta ->
            requestVideo(meta.ownerId, meta.sha256)
        }
    }

    /** Реальные байты только той передачи, которая совпадает с объявлением владельца. */
    private fun pumpDownloads(incoming: List<FileTransferEntity>) {
        val now = System.currentTimeMillis()
        val map = HashMap<String, PeerVideoAvatarStore.Download>()
        for (meta in PeerVideoAvatarStore.missing(context)) {
            val progress = incoming.asSequence().mapNotNull { row ->
                AvatarVideoTransferPolicy.progress(meta.ownerId, meta.sha256, meta.bytes, row, now)
            }.maxOrNull()
            if (progress != null) map[meta.ownerId] = PeerVideoAvatarStore.Download(meta.sha256, progress)
        }
        PeerVideoAvatarStore.setDownloads(map)
    }

    /**
     * Передача могла завершиться, а процесс — остановиться до установки в
     * витрину. Поднимаем только совпавший с объявлением проверенный файл;
     * SpecialMediaInbox повторно сверяет размер и sha перед установкой.
     */
    private suspend fun restoreCompletedVideos() {
        val missing = PeerVideoAvatarStore.missing(context).associateBy { it.ownerId }
        if (missing.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = transferDao.observeAvatarVideoIncoming().first()
        val received = ReceivedFileStore(File(context.noBackupFilesDir, "file_received/v1"))
        for (row in rows) {
            val meta = missing[row.peerNodeId] ?: continue
            if (row.state != "COMPLETE" ||
                AvatarVideoTransferPolicy.progress(meta.ownerId, meta.sha256, meta.bytes, row, now) == null
            ) continue
            try {
                SpecialMediaInbox.accept(context, row) { id, name -> received.receivedFile(id, name) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "completed video avatar recovery failed", failure)
            }
        }
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

    private enum class SendAction { NONE, PUMP, RESUME }

    private companion object {
        const val TAG = "AvatarVideo"
        const val PREFS = "avatar-video-gossip-v1"
        const val ANNOUNCE_AT_PREFIX = "announceDueAt:"
        // Новый ключ: старые отметки относятся к неправильным просьбам и не
        // должны задержать первую исправленную просьбу после обновления.
        const val READY_AT_PREFIX = "lastReadyOwnerAtMs:"
        const val MY_SHA_KEY = "myVideoSha"
        const val MY_SHA_FOR_KEY = "myVideoShaForMtime"
        const val ANNOUNCE_THROTTLE_MS = 30L * 60 * 1000
        const val READY_THROTTLE_MS = 2L * 60 * 1000
        const val RETRY_PUMP_MS = 60_000L
        /** Срок строки передачи — сутки, как у файла видеоистории. */
        const val TRANSFER_TTL_MS = 24L * 60 * 60 * 1000
    }
}
