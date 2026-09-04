package com.vladimir.messenger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Разделы, между которыми ходит нижняя панель. */
enum class ApuTab { Chats, Contacts, Groups, Profile, Settings }

/**
 * Куда ведут кнопки нижней панели.
 *
 * Один набор коллбеков на все экраны: панель обязана выглядеть и вести себя
 * одинаково везде, поэтому список кнопок собирается в одном месте, а не
 * переписывается в каждом Scaffold.
 */
data class ApuTabActions(
    val onChats: () -> Unit = {},
    val onContacts: () -> Unit = {},
    val onGroups: () -> Unit = {},
    val onProfile: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

/**
 * Нижняя панель разделов APU — та самая, что и на главном экране.
 *
 * Показывается на всех разделах, куда она умеет переключать, чтобы человек
 * никогда не оказывался на экране без неё и не искал кнопку «Назад».
 * Кнопка текущего раздела подсвечена и ничего не делает при нажатии: повторный
 * переход на себя же только плодил бы записи в истории.
 */
@Composable
fun ApuMainTabBar(
    current: ApuTab,
    actions: ApuTabActions,
    modifier: Modifier = Modifier,
    /** Непрочитанные чаты: значок на кнопке «Чаты». */
    chatsBadge: Int = 0,
) {
    ApuBottomBar(
        modifier = modifier,
        items = listOf(
            ApuBottomItem(
                title = "Чаты",
                icon = Icons.Filled.Forum,
                badge = chatsBadge,
                selected = current == ApuTab.Chats,
                onClick = { if (current != ApuTab.Chats) actions.onChats() },
            ),
            ApuBottomItem(
                title = "Контакты",
                icon = Icons.Filled.People,
                selected = current == ApuTab.Contacts,
                onClick = { if (current != ApuTab.Contacts) actions.onContacts() },
            ),
            ApuBottomItem(
                title = "Группы",
                icon = Icons.Filled.Groups,
                selected = current == ApuTab.Groups,
                onClick = { if (current != ApuTab.Groups) actions.onGroups() },
            ),
            ApuBottomItem(
                title = "Профиль",
                icon = Icons.Filled.Person,
                selected = current == ApuTab.Profile,
                onClick = { if (current != ApuTab.Profile) actions.onProfile() },
            ),
            ApuBottomItem(
                title = "Настройки",
                icon = Icons.Filled.Settings,
                selected = current == ApuTab.Settings,
                onClick = { if (current != ApuTab.Settings) actions.onSettings() },
            ),
        ),
    )
}
