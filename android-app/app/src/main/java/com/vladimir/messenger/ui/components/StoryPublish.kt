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
}

/**
 * Компоновка редактора истории.
 *
 * Кнопки фото и фона занимают много места под превью: если их оставить при
 * открытой клавиатуре или в альбоме (экран низкий), «Опубликовать» уезжает
 * за край — на скрине владельца в горизонтали всё спряталось внизу.
 *
 * Редактор рисуется поверх главного экрана (не отдельным Dialog): иначе
 * клавиатура не отдаёт отступы, экран дрожит, а в альбоме остаётся узкая карточка.
 */
internal object StoryComposerLayout {
    const val COMPACT_HEIGHT_DP = 520

    fun showAccessoryTools(imeOpen: Boolean, heightDp: Int = 2000): Boolean =
        !imeOpen && heightDp >= COMPACT_HEIGHT_DP

    fun showCompactToolRow(imeOpen: Boolean, heightDp: Int): Boolean =
        !imeOpen && heightDp < COMPACT_HEIGHT_DP
}

/**
 * Полоса историй как в Telegram: свой кружок один, плюс на нём.
 * Отдельной «Новой истории» нет — просмотр и добавление живут вместе.
 */
internal object StoryStrip {
    fun showsSeparateAddButton(): Boolean = false
    fun selfOpensViewer(hasOwnStory: Boolean): Boolean = hasOwnStory
}
