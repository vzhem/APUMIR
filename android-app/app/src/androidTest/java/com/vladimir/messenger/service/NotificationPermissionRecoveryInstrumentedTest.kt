package com.vladimir.messenger.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.ui.navigation.ChatLinkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separate instrumentation processes on a disposable emulator, without reinstalling
 * the target between phases. The host snapshots the initial permission, ensures it
 * is granted, revokes it, grants it for the retry, and finally restores the original
 * state. Revocation can kill an app, so it cannot happen inside the test process.
 * This exercises Android permission state, NotificationManager and isolated Room,
 * but manually invokes the processor with a fake identity/suppression check rather
 * than running the production worker or network.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationPermissionRecoveryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var helper: NotificationHelper
    private var oldAppMute: Long? = null
    private var oldPersonalMute: Long? = null
    private var mutesCaptured = false
    private var disposableProfileVerified = false

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DisposableNotificationTestProfile.requireEmptyEmulator(context)
        disposableProfileVerified = true
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        helper = EntryPointAccessors.fromApplication(context, ChatLinkEntryPoint::class.java).notificationHelper()
        val prefs = context.getSharedPreferences("notification_mutes", Context.MODE_PRIVATE)
        oldAppMute = if (prefs.contains("app")) prefs.getLong("app", 0) else null
        oldPersonalMute = if (prefs.contains("personal_chats")) prefs.getLong("personal_chats", 0) else null
        mutesCaptured = true
        assertTrue(prefs.edit().putLong("app", 0).putLong("personal_chats", 0).commit())
    }

    @After fun tearDown() {
        if (!disposableProfileVerified) return
        LocalMessageNotifications.cancelChat(context, CHAT)
        if (mutesCaptured) {
            val edit = context.getSharedPreferences("notification_mutes", Context.MODE_PRIVATE).edit()
            if (oldAppMute == null) edit.remove("app") else edit.putLong("app", oldAppMute!!)
            if (oldPersonalMute == null) edit.remove("personal_chats") else edit.putLong("personal_chats", oldPersonalMute!!)
            assertTrue(edit.commit())
        }
    }

    @Test fun disposableProfileSnapshotsTheOriginalPermission() {
        // The shell runner checks this exact passing test report before making
        // *any* permission change; a skipped test or nonempty profile is unsafe.
        val database = context.getDatabasePath(DATABASE)
        assertTrue("A previous test fixture or sidecar must not be overwritten",
            listOf("", "-wal", "-shm", "-journal").none { File(database.path + it).exists() })
        val marker = File(context.filesDir, PERMISSION_MARKER)
        assertFalse("A previous permission snapshot must not be overwritten", marker.exists())
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(permission == PackageManager.PERMISSION_GRANTED || permission == PackageManager.PERMISSION_DENIED)
        val granted = permission == PackageManager.PERMISSION_GRANTED
        assertEquals(granted, manager.areNotificationsEnabled())
        // This private marker is read via run-as only AFTER this exact test passes.
        marker.outputStream().use { stream ->
            stream.write((if (granted) "granted\n" else "denied\n").toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
        }
    }

    @Test fun permissionIsGrantedBeforeRevocation() {
        assertEquals(PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(manager.areNotificationsEnabled())
        assertFalse(context.getDatabasePath(DATABASE).exists())
    }

    @Test fun revokedPermissionLeavesTheCommittedNotificationPending() = runBlocking {
        assertEquals(PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(manager.areNotificationsEnabled())
        assertFalse(context.getDatabasePath(DATABASE).exists())
        val db = open()
        try {
            db.messageDao().insertMessage(MessageEntity(
                id = MESSAGE, chatId = CHAT, senderId = "fixture-sender", content = "fixture message",
                timestamp = 123L, recipientId = OWNER, notificationPending = true,
            ))
            assertEquals(IncomingNotificationProcessor.Result.BLOCKED, process(db))
            assertTrue(db.messageDao().getMessageById(MESSAGE)!!.notificationPending)
            assertFalse(manager.activeNotifications.any {
                it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == MESSAGE
            })
        } finally {
            // Keep only this uniquely named fixture for the next process.
            db.close()
        }
    }

    @Test fun grantingPermissionLetsTheReopenedPendingRowPostOnce() = runBlocking {
        assertEquals(PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(manager.areNotificationsEnabled())
        assertTrue("The denied phase must have committed its fixture", context.getDatabasePath(DATABASE).isFile)
        val db = open()
        try {
            assertTrue(db.messageDao().getMessageById(MESSAGE)!!.notificationPending)
            assertEquals(IncomingNotificationProcessor.Result.DONE, process(db))
            assertFalse(db.messageDao().getMessageById(MESSAGE)!!.notificationPending)
            withTimeout(10_000) {
                while (manager.activeNotifications.none {
                        it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == MESSAGE
                    }) delay(25)
            }
            assertNotNull(manager.activeNotifications.firstOrNull {
                it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == MESSAGE
            })
            assertEquals(IncomingNotificationProcessor.Result.DONE, process(db))
            assertEquals(1, manager.activeNotifications.count {
                it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == MESSAGE
            })
        } finally {
            db.close()
            context.deleteDatabase(DATABASE)
        }
    }

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE).build()

    private suspend fun process(db: AppDatabase): IncomingNotificationProcessor.Result {
        val dao = db.messageDao()
        fun snapshot(message: MessageEntity) = IncomingNotificationSnapshot.of(OWNER, message)
        return IncomingNotificationProcessor(
            recipientId = OWNER,
            load = dao::getMessageById,
            complete = { message ->
                dao.completePendingNotification(message.id, message.chatId, message.senderId, message.recipientId,
                    message.content, message.timestamp, message.topicId, message.channel) == 1
            },
            isSuppressed = { false },
            isActive = { message -> helper.isMessageNotificationActive(message.id, snapshot(message)) },
            alert = { message, kind ->
                assertEquals(IncomingAlert.NORMAL, kind)
                helper.showMessageNotification(message.chatId, message.senderId, message.content, true, null,
                    messageId = message.id, snapshot = snapshot(message))
            },
            cancel = { message -> helper.cancelMessageNotification(message.id, snapshot(message)) },
            current = { true },
        ).process(MESSAGE)
    }

    private companion object {
        const val DATABASE = "ci_notification_permission_recovery_fixture.db"
        const val PERMISSION_MARKER = "ci_notification_permission_original_state.txt"
        const val OWNER = "ci-notification-owner"
        const val CHAT = "ci-notification-permission-chat"
        const val MESSAGE = "ci-notification-permission-message"
    }
}
