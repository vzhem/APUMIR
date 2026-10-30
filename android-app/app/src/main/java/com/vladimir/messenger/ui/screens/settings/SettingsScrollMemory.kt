package com.vladimir.messenger.ui.screens.settings

/**
 * Где стоял список настроек, когда человек из него ушёл.
 *
 * Владелец 2026-10-29: «когда возвращаешься в предыдущее меню, оно не всегда
 * должно быть в верхней части, а в каком положении переходил — в такое же и
 * возвращалось». Причина была прямой: в `SettingsTabContent` стояло
 * `LaunchedEffect(section) { scrollToItem(0) }` — любой раздел и любой возврат
 * к списку начинались с нулевой строки.
 *
 * Теперь у каждого меню своя позиция: корневой список, «Чаты», «Уведомления» и
 * остальные помнят, где их оставили. Android-зависимостей нет — правило
 * проверяется обычным JVM-тестом (`SettingsScrollMemoryTest`).
 */
class SettingsScrollMemory {

    /** Первая видимая строка и её смещение в пикселях. */
    data class Anchor(val index: Int, val offset: Int) {
        val isTop: Boolean get() = index == 0 && offset == 0

        companion object {
            val TOP = Anchor(0, 0)
        }
    }

    private val anchors = LinkedHashMap<String, Anchor>()

    /** Позиция меню; неизвестное меню начинается сверху. */
    fun anchorFor(menu: String): Anchor = anchors[menu] ?: Anchor.TOP

    /**
     * Запомнить позицию. Верх списка не запоминаем: так карта не растёт от
     * каждого пролистывания, а «сверху» и есть значение по умолчанию.
     */
    fun remember(menu: String, anchor: Anchor) {
        if (anchor.isTop) anchors.remove(menu) else anchors[menu] = anchor
    }

    fun forget(menu: String) {
        anchors.remove(menu)
    }

    /** Сколько меню помнят свою позицию — нужно диагностике и тестам. */
    val rememberedMenus: Int get() = anchors.size

    /** Плоский вид для `rememberSaveable`: меню, строка, смещение, меню, … */
    fun flatten(): List<Int> {
        val flat = ArrayList<Int>(anchors.size * 3)
        for ((menu, anchor) in anchors) {
            flat += menuKey(menu)
            flat += anchor.index
            flat += anchor.offset
        }
        return flat
    }

    companion object {
        /** Восстановление после поворота и после смерти процесса. */
        fun fromFlatten(flat: List<Int>): SettingsScrollMemory {
            val memory = SettingsScrollMemory()
            var position = 0
            while (position + 2 < flat.size) {
                val menu = menuName(flat[position])
                memory.remember(menu, Anchor(flat[position + 1], flat[position + 2]))
                position += 3
            }
            return memory
        }

        /**
         * `rememberSaveable` хранит только примитивы: имя меню кодируем в Int
         * по алфавиту разделов. Незнакомый код — корневой список, потеря
         * позиции не ломает экран.
         */
        fun menuKey(menu: String): Int = MENU_KEYS.indexOf(menu).takeIf { it >= 0 } ?: 0

        fun menuName(key: Int): String = MENU_KEYS.getOrElse(key) { ROOT }

        const val ROOT = "root"

        /** Порядок совпадает с `SettingsSection`; менять только вместе с ним. */
        private val MENU_KEYS = listOf(
            ROOT,
            "Chats",
            "Notifications",
            "Appearance",
            "Security",
            "Network",
            "Files",
            "Hearts",
            "Updates",
            "Help",
        )
    }
}
