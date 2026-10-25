package com.vladimir.messenger.data.settings

import android.content.Context
import com.vladimir.messenger.data.folder.ChatFolderStore
import com.vladimir.messenger.data.heartgame.HeartGameStore
import com.vladimir.messenger.data.notification.NotificationMuteStore
import com.vladimir.messenger.data.textcolor.TextColorSettingsStore
import com.vladimir.messenger.data.translate.TranslationSettings
import com.vladimir.messenger.data.voice.VoiceEffect
import com.vladimir.messenger.data.voice.VoiceEffectPreferences
import com.vladimir.messenger.data.voice.VoicePlaybackSpeedStore
import com.vladimir.messenger.data.wallpaper.ConversationWallpaper
import com.vladimir.messenger.data.profile.InvitePrivacy
import com.vladimir.messenger.data.profile.InvitePrivacyStore
import com.vladimir.messenger.data.swarm.OnlineHelperSettings
import com.vladimir.messenger.data.swarm.ServerMode
import com.vladimir.messenger.data.swarm.StoragePolicy
import com.vladimir.messenger.data.swarm.StorageSettings
import com.vladimir.messenger.data.swarm.SwarmMode
import com.vladimir.messenger.data.swarm.SwarmSettings
import com.vladimir.messenger.ui.i18n.AppLanguage
import com.vladimir.messenger.ui.i18n.AppLanguageHolder
import com.vladimir.messenger.ui.theme.AppFontSize
import com.vladimir.messenger.ui.theme.AppFontSizeHolder
import com.vladimir.messenger.ui.theme.ThemeMode
import com.vladimir.messenger.ui.theme.ThemeModeHolder
import com.vladimir.messenger.ui.theme.WallpaperHolder
import com.vladimir.messenger.ui.theme.WallpaperTint
import com.vladimir.messenger.ui.theme.WallpaperTintHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Categories available to the safe, local settings reset. */
enum class SettingsResetCategory {
    CHATS,
    NOTIFICATIONS,
    APPEARANCE,
    SECURITY,
    NETWORK,
    FILES,
    HEARTS,
}

/**
 * One allow-listed defaults/reset mechanism for settings.
 *
 * This object intentionally has no "clear all preferences" operation. Account
 * identity, messages, contacts, device keys, signed receipts, referral
 * idempotency, group membership, backups and APU cards live outside the
 * allow-list and are never touched by these operations.
 */
object SettingsResetStore {
    val allCategories: Set<SettingsResetCategory> =
        SettingsResetCategory.entries.toSet()

    /** Human-readable policy surface used by tests and the confirmation UI. */
    val protectedDataLabels: Set<String> = setOf(
        "messages",
        "contacts",
        "profile",
        "files",
        "device_keys",
        "signed_receipts",
        "referral_data",
        "group_membership",
        "backups",
        "apu_cards",
    )

    /** Reset one or more categories; only explicit category handlers run. */
    suspend fun reset(
        context: Context,
        categories: Set<SettingsResetCategory>,
        notificationMuteStore: NotificationMuteStore,
    ) = withContext(Dispatchers.IO) {
        for (category in categories) {
            when (category) {
                SettingsResetCategory.CHATS -> resetChats(context)
                SettingsResetCategory.NOTIFICATIONS -> notificationMuteStore.resetAll()
                SettingsResetCategory.APPEARANCE -> resetAppearance(context)
                SettingsResetCategory.SECURITY -> resetSecurity(context)
                SettingsResetCategory.NETWORK -> resetNetwork(context)
                SettingsResetCategory.FILES -> resetFiles(context)
                SettingsResetCategory.HEARTS -> resetHearts(context)
            }
        }
    }

    /** Appearance defaults: system theme, standard text and the normal APU look. */
    fun resetAppearance(context: Context) {
        ThemeModeHolder.set(context, ThemeMode.SYSTEM)
        AppFontSizeHolder.set(context, AppFontSize.STANDARD)
        WallpaperHolder.set(context, null)
        WallpaperTintHolder.set(context, WallpaperTint.CLASSIC)
        TextColorSettingsStore.resetAll(context)
        TranslationSettings.set(context, false)
        AppLanguageHolder.set(context, AppLanguage.DEFAULT)
        VoicePlaybackSpeedStore.set(context, 1f)
        VoiceEffectPreferences.save(context, VoiceEffect.ORIGINAL)
    }

    /** Chat-list and personal-per-chat defaults; messages themselves remain. */
    suspend fun resetChats(context: Context) {
        ChatFolderStore.reset(context)
        ConversationWallpaper.resetAllPersonal(context)
    }

    /** All local notification pauses, including topic-specific pauses. */
    fun resetNotifications(notificationMuteStore: NotificationMuteStore) {
        notificationMuteStore.resetAll()
    }

    /** Safe invitation policy default; identity and security keys remain intact. */
    fun resetSecurity(context: Context) {
        InvitePrivacyStore.set(context, InvitePrivacy.ALL)
    }

    /** Network behaviour defaults; no account or transport identity is removed. */
    fun resetNetwork(context: Context) {
        context.applicationContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("proxy_tunnel_enabled", true).apply()
    }

    /** File/seeding defaults; stored files and transfer history remain. */
    fun resetFiles(context: Context) {
        SwarmSettings.set(context, SwarmMode.NORMAL)
        OnlineHelperSettings.set(context, true)
        ServerMode.set(context, true)
        StorageSettings.set(context, StoragePolicy.DEFAULT_QUOTA_BYTES)
    }

    /** Disable the optional heart animation only; score and referral data remain. */
    suspend fun resetHearts(context: Context) {
        HeartGameStore.ownId()?.let { id -> HeartGameStore.setEnabled(context, id, false) }
    }
}
