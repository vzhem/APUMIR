package com.vladimir.messenger.data.notification

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/** Уровни отключения уведомлений о новых сообщениях. */
enum class NotificationMuteScope(
    val preferenceKey: String,
    val title: String,
    val description: String,
) {
    /** Все личные чаты, группы, каналы и темы. */
    APP("app", "Во всём приложении", "Все уведомления о новых сообщениях"),
    PERSONAL_CHATS("personal_chats", "Личные чаты", "Все уведомления из личных чатов"),
    GROUPS("groups", "Группы", "Все уведомления из групп"),
    CHANNELS("channels", "Каналы", "Все уведомления из каналов"),
    TOPICS("topics", "Темы и комментарии", "Темы групп и обсуждения публикаций каналов"),
}

/** Быстрые варианты из диалога «Отключить уведомления». */
enum class NotificationMuteDuration(
    val label: String,
    val durationMs: Long?,
) {
    ONE_HOUR("На 1 час", 60L * 60L * 1000L),
    FOUR_HOURS("На 4 часа", 4L * 60L * 60L * 1000L),
    ONE_DAY("На 24 часа", 24L * 60L * 60L * 1000L),
    FOREVER("Навсегда", null);


    fun deadlineFrom(nowMs: Long = System.currentTimeMillis()): Long =
        durationMs?.let { nowMs + it } ?: Long.MAX_VALUE
}

/**
 * Хранилище пользовательских пауз уведомлений.
 *
 * Паузу всего приложения и разделов храним локально на устройстве. Паузу
 * конкретного чата/группы оставляем в Room (она уже синхронизируется между
 * своими устройствами), а паузы тем храним здесь по паре groupId/topicId:
 * тема - личная настройка, и не должна уезжать остальным участникам.
 */
@Singleton
class NotificationMuteStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        private const val PREFERENCES_NAME = "notification_mutes"
        private const val TOPIC_KEY_PREFIX = "topic_"
        private const val UI_REFRESH_INTERVAL_MS = 60_000L
    }

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // Срок может закончиться, пока экран настроек или меню переписки открыто.
    // Периодический tick нужен только интерфейсу; проверка доставки всегда
    // сверяет срок с текущим временем напрямую.
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        refreshScope.launch {
            while (isActive) {
                delay(UI_REFRESH_INTERVAL_MS)
                _revision.update { it + 1L }
            }
        }
    }

    fun mutedUntilMs(scope: NotificationMuteScope): Long =
        preferences.getLong(scope.preferenceKey, 0L)

    fun isMuted(scope: NotificationMuteScope, nowMs: Long = System.currentTimeMillis()): Boolean =
        mutedUntilMs(scope) > nowMs

    fun setMutedUntil(scope: NotificationMuteScope, untilMs: Long) {
        write(scope.preferenceKey, untilMs)
    }

    fun topicMutedUntilMs(groupId: String, topicId: String): Long {
        if (groupId.isBlank() || topicId.isBlank()) return 0L
        return preferences.getLong(topicPreferenceKey(groupId, topicId), 0L)
    }

    fun isTopicMuted(
        groupId: String,
        topicId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val topic = topicId?.takeIf { it.isNotBlank() } ?: return false
        return topicMutedUntilMs(groupId, topic) > nowMs
    }

    fun setTopicMutedUntil(groupId: String, topicId: String, untilMs: Long) {
        if (groupId.isBlank() || topicId.isBlank()) return
        write(topicPreferenceKey(groupId, topicId), untilMs)
    }

    private fun write(key: String, requestedUntilMs: Long) {
        val untilMs = when {
            requestedUntilMs == Long.MAX_VALUE -> Long.MAX_VALUE
            requestedUntilMs > System.currentTimeMillis() -> requestedUntilMs
            else -> 0L
        }
        val editor = preferences.edit()
        if (untilMs == 0L) editor.remove(key) else editor.putLong(key, untilMs)
        editor.apply()
        _revision.update { it + 1L }
    }

    private fun topicPreferenceKey(groupId: String, topicId: String): String {
        val bytes = "$groupId\u0000$topicId".toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return TOPIC_KEY_PREFIX + encoded
    }
}
