package com.vladimir.messenger.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsResetStoreTest {
    @Test
    fun allCategoriesAreExplicitAndProtectedDataIsNotAResetCategory() {
        assertEquals(
            setOf(
                SettingsResetCategory.CHATS,
                SettingsResetCategory.NOTIFICATIONS,
                SettingsResetCategory.APPEARANCE,
                SettingsResetCategory.SECURITY,
                SettingsResetCategory.NETWORK,
                SettingsResetCategory.FILES,
                SettingsResetCategory.HEARTS,
            ),
            SettingsResetStore.allCategories,
        )
        assertTrue(SettingsResetStore.protectedDataLabels.containsAll(
            setOf(
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
            ),
        ))
    }
}
