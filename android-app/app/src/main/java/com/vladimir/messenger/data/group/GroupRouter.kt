package com.vladimir.messenger.data.group

import android.util.Log
import com.vladimir.messenger.data.avatavideo.AvatarVideoCoordinator
import com.vladimir.messenger.data.avatavideo.AvatarVideoWire
import com.vladimir.messenger.data.contactreset.ContactResetCoordinator
import com.vladimir.messenger.data.contactreset.ContactResetWire
import com.vladimir.messenger.data.security.DeviceIdentityCoordinator
import com.vladimir.messenger.data.security.DeviceProfileAnchor

/**
 * Приём групповых событий из общего потока сообщений.
 *
 * Подключается в CoreServerService.handleEvent сразу после роутера файловых
 * пакетов и ДО авто-создания контакта: иначе каждый групповой конверт
 * превратился бы в личный чат с отправителем.
 *
 * Возвращает true, если текст оказался групповым пакетом и обработан — даже
 * когда разобрать его не удалось. Битый конверт намеренно поглощается, чтобы
 * мусор не попадал в историю чата как обычное сообщение.
 */
class GroupRouter(
    private val repository: GroupRepository,
    private val contactReset: ContactResetCoordinator,
    private val avatarVideos: AvatarVideoCoordinator,
    private val deviceIdentities: DeviceIdentityCoordinator? = null,
) {

    suspend fun routeIncoming(
        senderId: String,
        chatId: String,
        messageId: String,
        text: String,
    ): Boolean {
        if (ContactResetWire.isPacket(text)) {
            return contactReset.receive(senderId, text)
        }
        // Видеоаватары собеседников тем же конвертом APUGRP1|: свой вид
        // разбирается здесь, а старые клиенты глотают его как неразобранный
        // групповой пакет и не показывают служебный текст в чате.
        if (AvatarVideoWire.isPacket(text)) {
            return avatarVideos.receive(senderId, text)
        }
        if (DeviceProfileAnchor.Wire.isPacket(text)) {
            return deviceIdentities?.receive(senderId, text) ?: true
        }
        if (!GroupWire.isGroupPacket(text)) return false

        val packet = GroupWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "group packet from $senderId is malformed, dropped")
            return true
        }

        return try {
            repository.handleIncoming(senderId, packet, messageId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "group packet handling failed from $senderId: ${e.message}")
            true
        }
    }

    private companion object {
        const val TAG = "GroupRouter"
    }
}
