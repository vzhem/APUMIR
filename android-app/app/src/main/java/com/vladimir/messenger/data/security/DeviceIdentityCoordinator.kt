package com.vladimir.messenger.data.security

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.entity.NicknameEntity
import com.vladimir.messenger.data.local.entity.NicknameSuppressionEntity
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import com.vladimir.messenger.service.BotApi
import com.vladimir.messenger.service.CoreStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Мост вызова координатора привязки устройства из мест без прямого DI-инжекта.
 */
object DeviceIdentityGossip {
    @Volatile
    private var coordinator: DeviceIdentityCoordinator? = null

    internal fun attach(target: DeviceIdentityCoordinator) {
        coordinator = target
    }

    fun onProfileOrContactsChanged() {
        coordinator?.onProfileOrContactsChanged()
    }

    fun announceToPeer(peerId: String) {
        coordinator?.announceToPeer(peerId)
    }
}

/**
 * Координатор сетевой привязки профиля к устройству (`APUGRP1|dva1|…`):
 *  1. Выгружает зашифрованную подсказку профиля на `deviceShelf` в `APU_VAULT`.
 *  2. Рассылает контактам криптографическую привязку якоря устройства (`dva1|bn`)
 *     и, если после переустановки на этом же телефоне был создан новый профиль,
 *     подписанное доказательство замены (`dva1|rp`).
 *  3. Принимает и проверяет подписи `Ed25519` от контактов: схлопывает подтверждённые
 *     дубли одного и того же физического телефона в справочнике имён и в списке чатов.
 */
@Singleton
class DeviceIdentityCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botApi: BotApi,
    private val contactDao: ContactDao,
    private val nicknameDao: NicknameDao,
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val swarmPeerDirectory: SwarmPeerDirectory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val syncMutex = Mutex()
    private val boundPeersThisRun = Collections.synchronizedSet(HashSet<String>())
    private val seenReplacements = Collections.synchronizedSet(HashSet<String>())

    fun start() {
        if (!started.compareAndSet(false, true)) return
        DeviceIdentityGossip.attach(this)
        scope.launch {
            try {
                DeviceProfileBinding.syncCurrentProfileToVault(context, botApi)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "initial device vault sync skipped: ${e.message}")
            }
            CoreStatus.ready.collect { ready ->
                if (ready && RustBridge.isRunning()) {
                    try {
                        syncAndAnnounce(force = false)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.w(TAG, "device anchor startup announce failed: ${e.message}")
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(MAINTENANCE_INTERVAL_MS)
                if (!RustBridge.isRunning()) continue
                try {
                    syncAndAnnounce(force = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "device anchor periodic sync failed: ${e.message}")
                }
            }
        }
    }

    fun onProfileOrContactsChanged() {
        scope.launch {
            try {
                syncAndAnnounce(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "device anchor refresh failed: ${e.message}")
            }
        }
    }

    fun announceToPeer(peerId: String) {
        val canonicalPeer = DeviceProfileAnchor.canonicalNodeId(peerId) ?: return
        scope.launch {
            try {
                if (!RustBridge.isRunning()) return@launch
                val myId = currentMyNodeId() ?: return@launch
                if (canonicalPeer == myId) return@launch
                sendBindAndReplaceTo(myId, listOf(canonicalPeer))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "announceToPeer failed for $canonicalPeer: ${e.message}")
            }
        }
    }

    suspend fun receive(senderId: String, text: String): Boolean {
        val packet = DeviceProfileAnchor.Wire.parseVerified(text)
        if (packet == null) {
            Log.w(TAG, "invalid or forged dva1 packet dropped from $senderId")
            return true
        }
        when (packet) {
            is DeviceProfileAnchor.Wire.Packet.Bind -> handleBind(senderId, packet)
            is DeviceProfileAnchor.Wire.Packet.Replace -> handleReplace(senderId, packet)
        }
        return true
    }

    private suspend fun syncAndAnnounce(force: Boolean) {
        syncMutex.withLock {
            DeviceProfileBinding.syncCurrentProfileToVault(context, botApi)
            if (!RustBridge.isRunning()) return
            val myId = currentMyNodeId() ?: return
            val contacts = contactDao.allIds()
                .mapNotNull { DeviceProfileAnchor.canonicalNodeId(it) }
                .filter { it != myId }
            val targets = if (force) {
                contacts
            } else {
                contacts.filter { it !in boundPeersThisRun }
            }
            if (targets.isNotEmpty()) {
                sendBindAndReplaceTo(myId, targets)
                boundPeersThisRun.addAll(targets)
            }
            // Если на этом устройстве была замена профиля, сообщаем и узлам роевого реестра.
            val replacementWire = DeviceProfileBinding.createMyReplacePacket(context)
            if (replacementWire != null && force) {
                val swarmOwners = nicknameDao.allOwnerIds()
                    .mapNotNull { DeviceProfileAnchor.canonicalNodeId(it) }
                    .filter { it != myId && it !in contacts }
                    .take(MAX_SWARM_REPLACE_FANOUT)
                for (peer in swarmOwners) {
                    sendControl(peer, "dva1-rp", replacementWire)
                }
            }
        }
    }

    private fun sendBindAndReplaceTo(myId: String, recipients: List<String>) {
        val now = System.currentTimeMillis()
        val bindWire = DeviceProfileBinding.createMyBindPacket(context, myId, now)
        val replaceWire = DeviceProfileBinding.createMyReplacePacket(context, now)
        for (peer in recipients) {
            if (bindWire != null) {
                sendControl(peer, "dva1-bn-$myId", bindWire)
            }
            if (replaceWire != null) {
                sendControl(peer, "dva1-rp-$myId", replaceWire)
            }
        }
    }

    private fun sendControl(peerId: String, key: String, wire: String) {
        val msgId = UUID.nameUUIDFromBytes("apu-dva1:$peerId:$key".toByteArray(Charsets.UTF_8)).toString()
        RustBridge.sendMessage(msgId, peerId, peerId, wire)
    }

    private suspend fun handleBind(
        senderId: String,
        packet: DeviceProfileAnchor.Wire.Packet.Bind,
    ) {
        val canonicalSender = DeviceProfileAnchor.canonicalNodeId(senderId) ?: return
        if (canonicalSender != packet.ownerId) return
        val pinned = DeviceProfileBinding.pinOrVerifyPeer(context, packet.ownerId, packet.anchorPubHex)
        if (!pinned) {
            Log.w(TAG, "device anchor mismatch ignored for ${packet.ownerId}")
            return
        }
        // Если оба контакта уже доказали один и тот же физический телефон и одно @имя — склеиваем дубль.
        val liveContact = contactDao.getContactByFingerprint(packet.ownerId) ?: return
        val nick = liveContact.username.trim().trimStart('@').lowercase()
        if (nick.isBlank()) return
        val sameNickContacts = contactDao.getContactsByUsername(nick)
        for (other in sameNickContacts) {
            if (other.id == packet.ownerId || other.id == liveContact.id) continue
            if (DeviceProfileBinding.areSameVerifiedDevice(context, other.id, packet.ownerId)) {
                mergeReplacedContact(
                    oldOwnerId = other.id,
                    newOwnerId = packet.ownerId,
                    nickname = nick,
                    fallbackDisplayName = liveContact.displayName,
                    timestampMs = packet.timestampMs,
                )
            }
        }
    }

    private suspend fun handleReplace(
        senderId: String,
        packet: DeviceProfileAnchor.Wire.Packet.Replace,
    ) {
        val canonicalSender = DeviceProfileAnchor.canonicalNodeId(senderId) ?: return
        if (packet.hops == 0 && canonicalSender != packet.newOwnerId) return
        val dedupKey = "${packet.oldOwnerId}->${packet.newOwnerId}"
        val accepted = DeviceProfileBinding.acceptVerifiedReplacement(
            context = context,
            oldOwnerId = packet.oldOwnerId,
            newOwnerId = packet.newOwnerId,
            anchorPubHex = packet.anchorPubHex,
        )
        if (!accepted) {
            // У нас этот старый адрес не был закреплён за данным якорем устройства — не трогаем.
            return
        }
        if (!seenReplacements.add(dedupKey)) return

        try {
            mergeReplacedContact(
                oldOwnerId = packet.oldOwnerId,
                newOwnerId = packet.newOwnerId,
                nickname = packet.nickname,
                fallbackDisplayName = packet.displayName,
                timestampMs = packet.timestampMs,
            )
        } catch (error: Exception) {
            // Do not permanently suppress a verified replacement after an IO failure.
            seenReplacements.remove(dedupKey)
            Log.w(TAG, "verified replacement deferred", error)
            throw error
        }

        // Пересылаем подтверждённую замену общим знакомым на 1–2 шага.
        val forwarded = packet.nextHop()
        if (forwarded != null) {
            val myId = currentMyNodeId()
            val targets = contactDao.allIds()
                .mapNotNull { DeviceProfileAnchor.canonicalNodeId(it) }
                .filter { it != myId && it != canonicalSender && it != packet.newOwnerId && it != packet.oldOwnerId }
            for (peer in targets) {
                sendControl(peer, "dva1-rp-fwd-$dedupKey", forwarded)
            }
        }
    }

    private suspend fun mergeReplacedContact(
        oldOwnerId: String,
        newOwnerId: String,
        nickname: String,
        fallbackDisplayName: String,
        timestampMs: Long,
    ) {
        val now = System.currentTimeMillis()
        // 1. Подавляем старый адрес в справочнике @имён и записываем живой.
        val oldNickRows = nicknameDao.byOwner(oldOwnerId)?.let { listOf(it) }.orEmpty()
        NicknameRevocationHistory.revokeOwner(context, oldOwnerId)
        for (row in oldNickRows) {
            val norm = row.name.trim().trimStart('@').lowercase()
            if (norm.isNotBlank()) {
                NicknameRevocationHistory.revoke(context, oldOwnerId, norm)
                nicknameDao.suppress(NicknameSuppressionEntity(oldOwnerId, norm, now))
            }
        }
        if (nickname.isNotBlank()) {
            NicknameRevocationHistory.revoke(context, oldOwnerId, nickname)
            nicknameDao.suppress(NicknameSuppressionEntity(oldOwnerId, nickname, now))
            nicknameDao.unsuppress(newOwnerId, nickname)
            NicknameRevocationHistory.clear(context, newOwnerId, nickname)
            nicknameDao.upsert(
                NicknameEntity(
                    ownerId = newOwnerId,
                    name = nickname,
                    registeredAtMs = timestampMs.takeIf { it > 0L } ?: now,
                ),
            )
        }
        nicknameDao.deleteByOwner(oldOwnerId)

        // 2. Если старый узел был в контактах или чатах — переносим контакт и историю на новый адрес.
        val oldContact = contactDao.getContactByFingerprint(oldOwnerId)
        val oldChat = chatRepository.getChatByContactId(oldOwnerId)
        if (oldContact != null || oldChat != null) {
            val preservedName = when {
                oldContact != null && !contactRepository.isPlaceholderName(oldContact.displayName) ->
                    oldContact.displayName
                fallbackDisplayName.isNotBlank() -> fallbackDisplayName
                nickname.isNotBlank() -> nickname
                else -> "Contact ${newOwnerId.takeLast(8)}"
            }
            val effectiveNick = nickname.ifBlank { oldContact?.username.orEmpty() }
            val existingNew = contactRepository.getContactByFingerprint(newOwnerId)
            if (existingNew == null) {
                val error = contactRepository.addContact(
                    displayName = preservedName,
                    fingerprint = newOwnerId,
                    username = effectiveNick,
                ).exceptionOrNull()
                // A simultaneous insert is safe; a failed insert without a
                // destination must never lead to deleting the old history.
                if (error != null && contactRepository.getContactByFingerprint(newOwnerId) == null) throw error
            } else if (effectiveNick.isNotBlank() && existingNew.username.isBlank()) {
                contactRepository.updateUsername(newOwnerId, effectiveNick)
            }
            chatRepository.getOrCreateChat(newOwnerId, preservedName)
            if (oldChat != null) {
                chatRepository.absorbChatOf(oldOwnerId, newOwnerId)
            }
            contactRepository.deleteContact(oldOwnerId)
            chatRepository.mergeDuplicateChats(newOwnerId)

            swarmPeerDirectory.invalidate()
            val audience = swarmPeerDirectory.audienceIds()
            RustBridge.setPresenceAudience(audience)
            RustBridge.triggerGossipDiscovery()
            announceToPeer(newOwnerId)
            Log.i(TAG, "same-device replacement merged: $oldOwnerId -> $newOwnerId")
        } else {
            swarmPeerDirectory.invalidate()
        }
    }

    private fun currentMyNodeId(): String? =
        DeviceProfileAnchor.canonicalNodeId(RustBridge.nodeId())
            ?: DeviceProfileAnchor.canonicalNodeId(
                context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                    .getString("node_id", null),
            )

    companion object {
        private const val TAG = "DeviceIdentityCoord"
        private const val MAINTENANCE_INTERVAL_MS = 15L * 60L * 1000L
        private const val MAX_SWARM_REPLACE_FANOUT = 24
    }
}
