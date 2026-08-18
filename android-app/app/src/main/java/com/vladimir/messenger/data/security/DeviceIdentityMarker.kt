package com.vladimir.messenger.data.security

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Device-local proof that the identity was created on this installation.
 * [Context.noBackupFilesDir] is never restored by Auto Backup/device transfer.
 */
object DeviceIdentityMarker {
    private const val TAG = "DeviceIdentityMarker"
    private const val MARKER_FILE = "apu_identity_device_v1"

    fun isPresent(context: Context): Boolean =
        File(context.applicationContext.noBackupFilesDir, MARKER_FILE).isFile

    fun create(context: Context) {
        val marker = File(context.applicationContext.noBackupFilesDir, MARKER_FILE)
        marker.parentFile?.mkdirs()
        marker.writeText("v1", Charsets.US_ASCII)
    }

    fun discardIfRestored(context: Context): Boolean {
        val app = context.applicationContext
        val restoredIdentity = app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getBoolean("identity_created", false)
        if (!restoredIdentity || isPresent(app)) return false
        discardRestoredState(app)
        return true
    }

    /** Remove logically inconsistent state restored without its device marker. */
    private fun discardRestoredState(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("apu_relay_at_rest", Context.MODE_PRIVATE).edit().clear().commit()
        app.deleteDatabase("messenger_database")
        listOf(
            "apu_relay.sqlite",
            "apu_relay.sqlite-wal",
            "apu_relay.sqlite-shm",
            "apu_relay.sqlite.relay.sqlite",
            "apu_relay.sqlite.relay.sqlite-wal",
            "apu_relay.sqlite.relay.sqlite-shm",
        ).forEach { File(app.filesDir, it).delete() }
        File(app.noBackupFilesDir, MARKER_FILE).delete()
        Log.w(TAG, "Discarded restored identity/chat/custody state without device marker")
    }
}
