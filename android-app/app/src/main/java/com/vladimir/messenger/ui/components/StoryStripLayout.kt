package com.vladimir.messenger.ui.components

/**
 * Геометрия полосы историй: «как в Telegram».
 *
 * Пока историй немного, кружки стоят свободно и подписи видны. Когда историй
 * много, они складываются плотной стопкой — сосед наезжает на соседа, и в
 * ширину полоса занимает столько же, сколько один кружок с небольшим. Нажатие
 * на такой кружок раздвигает стопку (состояние `expanded`), и уже раздвинутую
 * полосу можно листать и выбирать, чью историю смотреть.
 *
 * Чистая арифметика без Compose: правило проверяется тестом без телефона.
 */
internal object StoryStripLayout {
    /** Сколько авторов ещё помещаются свободно, без наезда кружков. */
    const val COMFORTABLE_AUTHORS = 3

    /** Ширина слота под чужой кружок; у своего шире — на нём плюс и цифра. */
    const val PEER_SLOT_DP = 72
    const val SELF_SLOT_DP = 80

    /** Просвет между свободно стоящими кружками. */
    const val ITEM_GAP_DP = 14

    /** Диаметр чужого кружка: по нему считается наезд соседей. */
    const val PEER_DIAMETER_DP = 64

    /** Полоса стоит плотной стопкой: её можно раздвинуть нажатием. */
    fun compact(authorCount: Int, expanded: Boolean): Boolean =
        !expanded && authorCount > COMFORTABLE_AUTHORS

    /**
     * Ширина слота чужого кружка. Чем больше историй, тем плотнее стопка;
     * своему кружку слот не сужаем — на нём плюс, по нему надо попадать.
     */
    fun peerSlotDp(authorCount: Int, expanded: Boolean): Int = when {
        !compact(authorCount, expanded) -> PEER_SLOT_DP
        authorCount <= 6 -> 52
        authorCount <= 10 -> 44
        else -> 38
    }

    /** Просвет между слотами: в стопке ноль — наезд даёт сама ширина слота. */
    fun gapDp(authorCount: Int, expanded: Boolean): Int =
        if (compact(authorCount, expanded)) 0 else ITEM_GAP_DP

    /** Насколько кружки наезжают друг на друга. 0 — стоят свободно. */
    fun overlapDp(authorCount: Int, expanded: Boolean): Int =
        (PEER_DIAMETER_DP - peerSlotDp(authorCount, expanded) - gapDp(authorCount, expanded))
            .coerceAtLeast(0)

    /**
     * Подписи видны, только когда кружки стоят свободно: в стопке сосед
     * закрывает подпись предыдущего, и читаемого текста всё равно нет.
     */
    fun labelsVisible(authorCount: Int, expanded: Boolean): Boolean =
        !compact(authorCount, expanded)

    /** Цифра на золотом значке: больше двух разрядов в кружок не влезает. */
    fun totalLabel(total: Int): String =
        if (total > 99) "99+" else total.coerceAtLeast(0).toString()
}
