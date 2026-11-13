package com.vladimir.messenger.service

import android.content.Context
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import com.vladimir.messenger.data.security.ExistingRoutingIdentity

/** Local profile check only; it is not a restore/identity lock or peer authentication. */
internal object IncomingNotificationIdentity {
    fun current(context: Context): String? {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("identity_created", false) || !DeviceIdentityMarker.isPresent(app)) return null
        val node = prefs.getString("node_id", null)
        val resolved = ExistingRoutingIdentity.resolve(
            node, prefs.getString("public_key", null), prefs.getString("existing_public_key", null),
            prefs.getString("existing_private_key", null),
        ) ?: return null
        // Suppression stores also scope by node_id; do not guess when it is
        // missing or differs, even if legacy routing could start the engine.
        return resolved.nodeId.takeIf { it == node }
    }

    fun isCurrent(context: Context, expected: String): Boolean = current(context) == expected
}
