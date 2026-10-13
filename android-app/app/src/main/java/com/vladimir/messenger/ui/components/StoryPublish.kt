package com.vladimir.messenger.ui.components

/**
 * Правила публикации своей истории.
 *
 * Вынесено из экрана отдельной чистой логикой, потому что здесь уже была
 * реальная ошибка: если фото не прикрепилось, а подпись набрана, кнопка
 * «Опубликовать» оставалась активной и история молча уходила друзьям текстом -
 * они видели одну подпись без снимка. Теперь при неудачном прикреплении нужен
 * явный выбор «опубликовать без фото», и это правило проверяется тестом.
 */
internal object StoryPublish {
    fun canPublish(text: String, photoB64: String?, photoMissing: Boolean): Boolean =
        !photoMissing && (text.isNotBlank() || photoB64 != null)

    /**
     * Синяя галочка на клавиатуре — то же подтверждение, что кнопка
     * «Опубликовать». Пустую историю галочка не отправляет: только прячет
     * клавиатуру.
     */
    fun confirmOnIme(text: String, photoB64: String?, photoMissing: Boolean, preparing: Boolean): Boolean =
        !preparing && canPublish(text, photoB64, photoMissing)
}

/**
 * Компоновка редактора истории.
 *
 * Кнопки фото и фона занимают много места под превью: если их оставить при
 * открытой клавиатуре или в альбоме (экран низкий), «Опубликовать» уезжает
 * за край — на скрине владельца в горизонтали всё спряталось внизу.
 *
 * Высоту клавиатуры в диалоге Compose часто не отдаёт WindowInsets.ime
 * (окно WRAP_CONTENT). Считаем перекрытие по видимому кадру экрана.
 */
internal object StoryComposerLayout {
    const val COMPACT_HEIGHT_DP = 520

    fun showAccessoryTools(imeOpen: Boolean, heightDp: Int = 2000): Boolean =
        !imeOpen && heightDp >= COMPACT_HEIGHT_DP

    fun showCompactToolRow(imeOpen: Boolean, heightDp: Int): Boolean =
        !imeOpen && heightDp < COMPACT_HEIGHT_DP

    /**
     * Насколько низ нашего окна перекрыт клавиатурой. Навбар не считаем
     * клавиатурой: порог 15% высоты, как в старом WindowVisibleDisplayFrame.
     */
    fun imeOverlapPx(viewBottomOnScreen: Int, visibleFrameBottom: Int, viewHeight: Int): Int {
        val overlap = (viewBottomOnScreen - visibleFrameBottom).coerceAtLeast(0)
        return if (viewHeight > 0 && overlap > viewHeight * 0.15f) overlap else 0
    }
}
