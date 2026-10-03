package com.vladimir.messenger.ui.components

/** Layout only: never changes preferences, entitlement or profile data. */
internal object ApuSettingsLayout {
    fun profileActionColumns(widthDp: Float, fontScale: Float): Int {
        val scale = fontScale.coerceAtLeast(1f)
        return when {
            widthDp >= 4 * 64f * scale + 24f -> 4
            widthDp >= 2 * 64f * scale + 8f -> 2
            else -> 1
        }
    }

    fun horizontalThemeChoices(widthDp: Float, fontScale: Float): Boolean =
        widthDp >= 3 * 80f * fontScale.coerceAtLeast(1f) + 16f
}
