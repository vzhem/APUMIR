package com.vladimir.messenger.ui.components

/** Layout only: never changes preferences, entitlement or profile data. */
internal object ApuSettingsLayout {
    /**
     * Колонки быстрых действий профиля («Мой QR», «Ссылка», «Никнейм»,
     * «Аватар»). Ячейка обязана вмещать самую длинную подпись («Никнейм»,
     * labelLarge SemiBold ~70dp) плюс 16dp боковых отступов кнопки; прежний
     * порог 64dp на колонку ломал подпись посреди слова на карточке ~300dp
     * (скрин владельца 2026-10-05). Поэтому 4 колонки - только широкий
     * экран/планшет, на телефоне подпись идёт в 2x2 без переносов.
     */
    fun profileActionColumns(widthDp: Float, fontScale: Float): Int {
        val scale = fontScale.coerceAtLeast(1f)
        return when {
            widthDp >= 4 * 88f * scale + 24f -> 4
            widthDp >= 2 * 88f * scale + 8f -> 2
            else -> 1
        }
    }

    fun horizontalThemeChoices(widthDp: Float, fontScale: Float): Boolean =
        widthDp >= 3 * 80f * fontScale.coerceAtLeast(1f) + 16f

    fun horizontalCommunityTypeChoices(widthDp: Float, fontScale: Float): Boolean =
        widthDp >= 2 * 108f * fontScale.coerceAtLeast(1f) + 8f
}
