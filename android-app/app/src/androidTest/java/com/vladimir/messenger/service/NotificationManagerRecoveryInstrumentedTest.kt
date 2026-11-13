package com.vladimir.messenger.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.ui.navigation.ChatLinkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real Android notification manager on a disposable emulator; no provider, FCM or network. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationManagerRecoveryInstrumentedTest {
    // GrantPermissionRule cannot undo its grant. The guard must execute first,
    // even before @Before or any Android permission/settings mutation.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            DisposableNotificationTestProfile.requireEmptyEmulator(ApplicationProvider.getApplicationContext())
        }
    }).around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var helper: NotificationHelper
    private val chats = mutableListOf<String>()
    private val channels = mutableListOf<String>()
    private var oldAppMute: Long? = null
    private var oldPersonalMute: Long? = null
    private var mutesCaptured = false

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DisposableNotificationTestProfile.requireEmptyEmulator(context)
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        helper = EntryPointAccessors.fromApplication(context, ChatLinkEntryPoint::class.java).notificationHelper()
        val prefs = context.getSharedPreferences("notification_mutes", Context.MODE_PRIVATE)
        oldAppMute = if (prefs.contains("app")) prefs.getLong("app", 0) else null
        oldPersonalMute = if (prefs.contains("personal_chats")) prefs.getLong("personal_chats", 0) else null
        mutesCaptured = true
        assertTrue(prefs.edit().putLong("app", 0).putLong("personal_chats", 0).commit())
    }

    @After fun tearDown() {
        if (!::context.isInitialized) return
        chats.forEach { LocalMessageNotifications.cancelChat(context, it) }
        if (::manager.isInitialized) channels.forEach { manager.deleteNotificationChannel(it) }
        if (mutesCaptured) {
            val edit = context.getSharedPreferences("notification_mutes", Context.MODE_PRIVATE).edit()
            if (oldAppMute == null) edit.remove("app") else edit.putLong("app", oldAppMute!!)
            if (oldPersonalMute == null) edit.remove("personal_chats") else edit.putLong("personal_chats", oldPersonalMute!!)
            assertTrue(edit.commit())
        }
    }

    private suspend fun waitFor(messageId: String, present: Boolean) = withTimeout(10_000) {
        while (manager.activeNotifications.any {
                it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == messageId
            } != present
        ) delay(25)
    }

    @Test fun collidingChatIdsKeepSeparatePostsAndTapIntents() = runBlocking {
        val prefix = "fixture-${UUID.randomUUID()}-"
        val firstChat = prefix + "Aa"
        val secondChat = prefix + "BB"
        assertEquals(firstChat.hashCode(), secondChat.hashCode()) // would collide in the legacy ID and PendingIntent
        chats += listOf(firstChat, secondChat)
        val firstMessage = UUID.randomUUID().toString()
        val secondMessage = UUID.randomUUID().toString()
        assertEquals(IncomingNotificationPost.POSTED,
            helper.showMessageNotification(firstChat, "friend", "first", true, null, messageId = firstMessage, snapshot = "first-snapshot"))
        assertEquals(IncomingNotificationPost.POSTED,
            helper.showMessageNotification(secondChat, "friend", "second", true, null, messageId = secondMessage, snapshot = "second-snapshot"))
        waitFor(firstMessage, true)
        waitFor(secondMessage, true)
        val posts = manager.activeNotifications.associateBy { it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) }
        val first = requireNotNull(posts[firstMessage])
        val second = requireNotNull(posts[secondMessage])
        assertNotEquals(first.tag, second.tag)
        assertNotEquals(first.notification.contentIntent, second.notification.contentIntent)
        assertTrue(helper.isMessageNotificationActive(firstMessage, "first-snapshot"))
        assertFalse(helper.isMessageNotificationActive(firstMessage, "different-snapshot"))
        helper.cancelMessageNotification(firstMessage, "different-snapshot")
        assertTrue(helper.isMessageNotificationActive(firstMessage, "first-snapshot"))
        helper.cancelChatNotifications(firstChat)
        waitFor(firstMessage, false)
        assertTrue(helper.isMessageNotificationActive(secondMessage, "second-snapshot"))
    }

    @Test fun readingOneTopicCancelsOnlyItsPostsIncludingSos() = runBlocking {
        val chat = "fixture-group-${UUID.randomUUID()}"
        val otherChat = "fixture-other-${UUID.randomUUID()}"
        chats += listOf(chat, otherChat)
        val topicA = UUID.randomUUID().toString()
        val topicB = UUID.randomUUID().toString()
        val sos = UUID.randomUUID().toString()
        val otherTopic = UUID.randomUUID().toString()
        val otherGroup = UUID.randomUUID().toString()
        assertEquals(IncomingNotificationPost.POSTED,
            helper.showMessageNotification(chat, "friend", "other topic", true, topicB, messageId = otherTopic, snapshot = "b"))
        assertEquals(IncomingNotificationPost.POSTED,
            helper.showSosNotification(chat, "friend", topicA, messageId = sos, snapshot = "a"))
        assertEquals(IncomingNotificationPost.POSTED,
            helper.showMessageNotification(otherChat, "friend", "other group", true, topicA, messageId = otherGroup, snapshot = "other"))
        waitFor(otherTopic, true); waitFor(sos, true); waitFor(otherGroup, true)
        val posts = manager.activeNotifications.associateBy {
            it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE)
        }
        // Navigation no longer clears the whole chat. Both notification kinds
        // must therefore ask Android to dismiss only the post actually tapped.
        for ((id, topic) in listOf(otherTopic to topicB, sos to topicA)) {
            val notification = requireNotNull(posts[id]).notification
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(chat, notification.extras.getString(LocalMessageNotifications.EXTRA_CHAT))
            assertEquals(topic, notification.extras.getString(LocalMessageNotifications.EXTRA_TOPIC))
            assertTrue(notification.contentIntent != null)
        }
        LocalMessageNotifications.cancelTopic(context, chat, topicA)
        waitFor(sos, false)
        assertTrue(helper.isMessageNotificationActive(otherTopic, "b"))
        assertTrue(helper.isMessageNotificationActive(otherGroup, "other"))
    }

    @Test fun disabledOrMissingAndroidChannelIsNotReportedAsPostable() {
        assertTrue(helper.canPost(manager, MessengerApplication.CHANNEL_ID))
        val blocked = "test-blocked-${UUID.randomUUID()}"
        channels += blocked
        manager.createNotificationChannel(NotificationChannel(blocked, "Test blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(helper.canPost(manager, blocked))
        assertFalse(helper.canPost(manager, "missing-${UUID.randomUUID()}"))
    }

    @Test fun appMuteIsAnIntentionalSuppressionRatherThanASystemPost() = runBlocking {
        val chat = "test-muted-${UUID.randomUUID()}"
        val message = UUID.randomUUID().toString()
        chats += chat
        assertTrue(context.getSharedPreferences("notification_mutes", Context.MODE_PRIVATE)
            .edit().putLong("app", Long.MAX_VALUE).commit())
        assertEquals(IncomingNotificationPost.SUPPRESSED,
            helper.showMessageNotification(chat, "friend", "text", true, null, messageId = message, snapshot = "muted"))
        assertFalse(manager.activeNotifications.any {
            it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == message
        })
    }
}
