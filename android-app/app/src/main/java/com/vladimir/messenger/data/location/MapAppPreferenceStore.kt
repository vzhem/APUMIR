package com.vladimir.messenger.data.location

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Chats → Opening locations. The choice belongs only to this device. */
object MapAppPreferenceStore {
    private const val PREFS = "apu_map_opening"
    private const val KEY_APP = "default_app"
    private val _app = MutableStateFlow(MapApp.ASK)
    val app: StateFlow<MapApp> = _app.asStateFlow()

    fun initialize(context: Context) {
        _app.value = MapOpeningPolicy.appFromId(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APP, MapOpeningPolicy.DEFAULT_APP_ID),
        )
    }

    /** Read the persisted choice even when a caller enters before MainActivity initialization. */
    fun current(context: Context): MapApp {
        initialize(context)
        return _app.value
    }

    fun set(context: Context, app: MapApp) {
        // One tiny preference: commit makes an explicit default survive process death/restart.
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_APP, app.id).commit()
        if (!saved) Log.w("MapAppPreference", "Could not persist the map preference")
        _app.value = app
    }
}
