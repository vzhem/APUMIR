package com.vladimir.messenger.service

import android.app.NotificationManager
import android.content.Context
import java.security.MessageDigest

/** Stable, collision-resistant Android tags; no message text is placed in a tag. */
internal object LocalMessageNotifications {
    const val MESSAGE_ID = 2000
    const val SOS_ID = 700_000
    const val EXTRA_MESSAGE = "apu_message_id"
    const val EXTRA_CHAT = "apu_notification_chat_id"
    const val EXTRA_TOPIC = "apu_notification_topic_id"
    const val EXTRA_SNAPSHOT = "apu_notification_snapshot_v1"

    fun chatTag(chatId: String): String = "apu_chat_" + digest(chatId)
    fun sosTag(chatId: String, messageId: String?): String =
        "apu_sos_" + digest(if (messageId == null) "c:$chatId" else "m:$messageId")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    /**
     * Only notifications explicitly carrying this chat ID are cancelled. Old
     * untagged notifications without a chat extra cannot safely be attributed
     * (legacy integer IDs collided) and are left for Android/the user to dismiss.
     */
    fun cancelChat(context: Context, chatId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(chatTag(chatId), MESSAGE_ID)
        manager.activeNotifications.filter { it.notification.extras.getString(EXTRA_CHAT) == chatId }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /** Do not cancel the latest post from a different topic of the same group. */
    fun cancelTopic(context: Context, chatId: String, topicId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter {
            it.notification.extras.getString(EXTRA_CHAT) == chatId &&
                it.notification.extras.getString(EXTRA_TOPIC) == topicId
        }.forEach { manager.cancel(it.tag, it.id) }
    }
}
