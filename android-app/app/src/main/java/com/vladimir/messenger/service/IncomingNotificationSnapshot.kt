package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Private local comparison token, not an authentication tag. Its length-framed
 * fields distinguish an old active post from a changed row or another profile;
 * neither the full message nor a profile ID is put into the token itself.
 */
internal object IncomingNotificationSnapshot {
    fun of(owner: String, message: MessageEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            if (value == null) {
                digest.update(ByteBuffer.allocate(4).putInt(-1).array())
            } else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        field("apu-local-notification-v1")
        field(owner)
        field(message.id)
        field(message.chatId)
        field(message.senderId)
        field(message.recipientId)
        field(message.content)
        field(message.timestamp.toString())
        field(message.topicId)
        field(message.channel)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
