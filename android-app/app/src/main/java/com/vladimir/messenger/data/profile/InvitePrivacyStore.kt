package com.vladimir.messenger.data.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Кто вправе прислать мне приглашение в группу или канал. */
enum class InvitePrivacy { ALL, CONTACTS, NOBODY }

/**
 * Настройка «Кто может приглашать в группы и каналы» (Настройки →
 * Безопасность). Приглашение в APU — это карточка со ссылкой в личном чате;
 * фильтр применяется при сохранении входящего сообщения
 * (ChatRepository.saveIncomingMessage): карточка от неразрешённого
 * отправителя выбрасывается до записи в базу - человек не видит ни самой
 * карточки, ни уведомления о ней, ни непрочитанных. Полная тишина.
 */
object InvitePrivacyStore {
    private const val PREFS = "apu_invite_privacy"
    private const val KEY_MODE = "who_can_invite"

    private val _mode = MutableStateFlow(InvitePrivacy.ALL)

    /** Текущий выбор для экрана настроек. */
    val mode: StateFlow<InvitePrivacy> = _mode.asStateFlow()

    fun init(context: Context) {
        _mode.value = value(context)
    }

    fun set(context: Context, mode: InvitePrivacy) {
        _mode.value = mode
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** Честное чтение из файла: работает и до init, и без StateFlow. */
    fun value(context: Context): InvitePrivacy =
        runCatching {
            InvitePrivacy.valueOf(prefs(context).getString(KEY_MODE, null).orEmpty())
        }.getOrDefault(InvitePrivacy.ALL)

    /** Разрешён ли этот отправитель при текущем режиме. */
    fun allows(mode: InvitePrivacy, senderIsContact: Boolean): Boolean = when (mode) {
        InvitePrivacy.ALL -> true
        InvitePrivacy.CONTACTS -> senderIsContact
        InvitePrivacy.NOBODY -> false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
