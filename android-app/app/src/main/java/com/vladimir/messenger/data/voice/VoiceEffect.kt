package com.vladimir.messenger.data.voice

import android.content.Context
import androidx.annotation.StringRes
import com.vladimir.messenger.R

/** Популярные пресеты изменения голоса: персонажи и классические студийные эффекты. */
enum class VoiceEffect(
    @StringRes val labelRes: Int,
    val symbol: String,
) {
    ORIGINAL(R.string.voice_effect_original, "🎙️"),
    DARTH_VADER(R.string.voice_effect_darth_vader, "🖤"),
    MINION(R.string.voice_effect_minion, "🍌"),
    ROBOT(R.string.voice_effect_robot, "🤖"),
    DEMON(R.string.voice_effect_demon, "😈"),
    ALIEN(R.string.voice_effect_alien, "👽"),
    MONSTER(R.string.voice_effect_monster, "👹"),
    CHILD(R.string.voice_effect_child, "🧒"),
    FEMALE(R.string.voice_effect_female, "👩"),
    MALE(R.string.voice_effect_male, "👨"),
    CATHEDRAL(R.string.voice_effect_cathedral, "⛪"),
    RADIO(R.string.voice_effect_radio, "📻"),
    UNDERWATER(R.string.voice_effect_underwater, "🌊"),
    FAST(R.string.voice_effect_fast, "🐇"),
    SLOW(R.string.voice_effect_slow, "🐢"),
    ;

    companion object {
        fun fromId(id: String?): VoiceEffect = entries.firstOrNull { it.name == id } ?: ORIGINAL
    }
}

/** Последний выбранный эффект общий для голосовых сообщений и звонков. */
object VoiceEffectPreferences {
    private const val PREFS = "voice_effects"
    private const val KEY = "selected_effect"

    @Volatile
    var current: VoiceEffect = VoiceEffect.ORIGINAL
        private set

    fun load(context: Context): VoiceEffect {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VoiceEffect.fromId(prefs.getString(KEY, null)).also { current = it }
    }

    fun save(context: Context, effect: VoiceEffect) {
        current = effect
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, effect.name)
            .apply()
    }
}
