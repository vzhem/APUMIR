package com.vladimir.messenger.data.swarm

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent opt-out from ephemeral online transit; never changes ServerMode or custody consent. */
object OnlineHelperSettings {
    private const val KEY = "online_helper_enabled"
    private val state = MutableStateFlow(true) // Preserve existing online participation on upgrade.
    val enabled = state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        state.value = context.applicationContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY, true)
    }

    @Synchronized
    fun set(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        state.value = enabled
        RustBridge.refreshOnlineHelperPolicy()
    }

    fun limits(context: Context): OnlineHelperLimits {
        init(context) // Also correct when a background service starts before MainActivity.
        SwarmSettings.init(context)
        return OnlineHelperPolicy.limits(state.value, SwarmSettings.mode.value,
            SwarmSettings.isMetered(context), SwarmSettings.isLowPower(context))
    }
}
