package com.vladimir.messenger.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.InputPanelDialog
import com.vladimir.messenger.ui.screens.chat.ChatListInviteChoiceDialog
import com.vladimir.messenger.ui.screens.groups.CommunityInviteChoiceDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MenuDialogsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun invitationFromChatListCanBeCancelledWithoutSending() {
        val open = mutableStateOf(true)
        val channel = mutableStateOf(false)
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                if (open.value) ChatListInviteChoiceDialog(
                    isChannel = channel.value,
                    onDismiss = { events += "cancel"; open.value = false },
                    onShowQr = { events += "qr"; open.value = false },
                    onSendLink = { events += "link"; open.value = false },
                    onSendInApu = { events += "apu"; open.value = false },
                )
            }
        }
        compose.onNodeWithText(text(R.string.action_cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel"), events); channel.value = true; open.value = true }
        compose.onNodeWithText(text(R.string.groups_show_qr)).performScrollTo().performClick()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText(text(R.string.groups_send_link)).performScrollTo().performClick()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText(text(R.string.contacts_send_in_apu)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel", "qr", "link", "apu"), events) }
    }

    @Test fun invitationFromCommunitiesHasCancelAndPreservesAllThreeActions() {
        val open = mutableStateOf(true)
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                if (open.value) CommunityInviteChoiceDialog(
                    isChannel = false,
                    onDismiss = { events += "cancel"; open.value = false },
                    onSendInApu = { events += "apu"; open.value = false },
                    onShowQr = { events += "qr"; open.value = false },
                    onShareLink = { attach -> events += "link:$attach"; open.value = false },
                )
            }
        }
        compose.onNodeWithText(text(R.string.action_cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel"), events); open.value = true }
        compose.onNodeWithText(text(R.string.groups_show_qr)).performScrollTo().performClick()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText(text(R.string.groups_send_link)).performScrollTo().performClick()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText(text(R.string.contacts_attach_apk)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.groups_send_link)).performScrollTo().performClick()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText(text(R.string.contacts_send_in_apu)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel", "qr", "link:true", "link:false", "apu"), events) }
    }

    @Test fun inputPanelCanBeClosedFromGifEmojiAndStickerSections() {
        val open = mutableStateOf(true)
        var closes = 0
        compose.setContent {
            MaterialTheme {
                if (open.value) InputPanelDialog(
                    myGifs = emptyList(), swarmGifs = emptyList(), swarmStatus = null,
                    items = emptyList(), next = "", loading = true, error = null,
                    onSearch = {}, onMore = {}, onAttach = {}, onAttachLocal = {}, onRequestSwarm = {},
                    stickers = emptyList(), stickerRecents = emptyList(), onSticker = {}, onAddSticker = {},
                    onEmoji = {}, onDismiss = { closes++; open.value = false },
                )
            }
        }
        for (section in listOf<Int?>(null, R.string.input_emoji, R.string.sv_stickers)) {
            compose.runOnIdle { open.value = true }
            if (section != null) compose.onNodeWithText(text(section)).performClick()
            compose.onNodeWithContentDescription(text(R.string.action_close))
                .assertIsDisplayed().assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithContentDescription(text(R.string.action_close)).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(3, closes) }
    }
}
