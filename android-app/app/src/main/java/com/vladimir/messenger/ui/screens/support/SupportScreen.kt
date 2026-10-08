package com.vladimir.messenger.ui.screens.support

import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsHeader

// =============================================================================
// SUPPORTSCREEN.KT — «Поддержать разработчика» (Настройки)
// =============================================================================
// Раунд 219, ЧЕРНОВИК по решению владельца. Раунд 220: экран переведён в
// фирменный стиль подменю настроек — пузырь-подсказка (HintBubble) и золотые
// пузыри действий (ApuActionBubble), как на других экранах раздела; прежние
// голые Material-карточки выглядели чужеродно (владелец: «нужно... сделать
// в пузыри в нашем стиле»). И исправлен вход: пункт настроек не открывал
// экран (NavGraph не передавал onSupportClick).
//
// Три блока, «понятно, просто, безопасно»:
//  1. Зачем нужны самостоятельные переводы (текст).
//  2. Способы перевода - список отдаёт СЕРВИС (/support воркера). В коде и
//     репозитории нет ни одного личного реквизита; экран виден только
//     владельцу телефона. Пока сервис не настроен - честный черновой текст.
//  3. «Поддерживать ежемесячно» - переключатель надо/не надо и выбор,
//     КОГДА напоминать. APU деньги не списывает и платёжных данных не
//     хранит: это только локальное уведомление.
// =============================================================================

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.support.SupportReminder
import com.vladimir.messenger.ui.components.ApuActionBubble
import com.vladimir.messenger.ui.components.ApuScrollbar
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor
import com.vladimir.messenger.ui.components.swipeBack
import com.vladimir.messenger.ui.components.ApuPremiumSlider
import com.vladimir.messenger.ui.components.ApuPremiumSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBackClick: () -> Unit,
    viewModel: SupportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scrollState = rememberLazyListState()
    var toast by remember { mutableStateOf<String?>(null) }

    toast?.let { message ->
        LaunchedEffect(message) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            toast = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Смахивание вправо работает как «Назад».
            .swipeBack(onBack = onBackClick),
    ) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = { ApuSettingsHeader("Поддержать разработчика") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── 1. Зачем ──────────────────────────────────────────
                    item { SectionTitle("Зачем нужны самостоятельные переводы") }
                    item {
                        // Фирменный пузырь-подсказка: как на других экранах
                        // настроек («Зачем это нужно»).
                        HintBubble {
                            Column {
                                Text(
                                    "APU бесплатный и без рекламы",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HintBubbleTextColor,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Внутри нет подписок, рекламы и продажи данных. " +
                                        "Приложение распространяется напрямую, минуя " +
                                        "магазины, - поэтому встроенных покупок нет, " +
                                        "а каждый перевод доходит до разработчика " +
                                        "целиком: магазин удерживал бы 15–30%. " +
                                        "Самостоятельный перевод - самый выгодный " +
                                        "для проекта способ, без посредников.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HintBubbleMutedColor,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "На что идут переводы: релеи для связи в сложных " +
                                        "сетях, хранение резервных копий, поиск гифок, " +
                                        "работа над приложением.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HintBubbleMutedColor,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Поддержка добровольна и ни на что не влияет: " +
                                        "все функции одинаковы у всех.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HintBubbleMutedColor,
                                )
                            }
                        }
                    }

                    // ── 2. Способы перевода ───────────────────────────────
                    item { SectionTitle("Способы перевода") }
                    if (uiState.loading) {
                        item { DraftBubble("Загружаем способы…") }
                    } else if (uiState.ways.isEmpty()) {
                        item {
                            // Черновое состояние: реквизиты ещё не опубликованы.
                            HintBubble {
                                Column {
                                    Text(
                                        "Список ещё настраивается",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = HintBubbleTextColor,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Это черновик раздела. Здесь появятся способы " +
                                            "перевода, когда они будут добавлены в сервис.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = HintBubbleMutedColor,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Планируем: перевод по СБП, перевод на карту, " +
                                            "криптопереводы для тех, кто вне России.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = HintBubbleMutedColor,
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(uiState.ways) { _, way ->
                            ApuSettingsCard {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        way.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        way.details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (way.copyText.isNotBlank()) {
                                        Spacer(Modifier.height(12.dp))
                                        // Золотой пузырь действия - фирменный стиль.
                                        ApuActionBubble(
                                            label = "Скопировать",
                                            icon = Icons.Default.ContentCopy,
                                            onClick = {
                                                clipboard.setText(AnnotatedString(way.copyText))
                                                toast = "Скопировано"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                "Обновить список можно, закрыв и снова открыв этот экран.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }

                    // ── 3. Ежемесячная поддержка ──────────────────────────
                    item { SectionTitle("Поддерживать ежемесячно") }
                    item {
                        val reminder = uiState.reminder
                        ApuSettingsCard {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Напоминать о поддержке",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            reminder.humanLine,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    ApuPremiumSwitch(
                                        checked = reminder.enabled,
                                        onCheckedChange = { viewModel.setReminderEnabled(it) },
                                    )
                                }

                                if (reminder.enabled) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Как часто напоминать",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = reminder.periodMonths == 1,
                                            onClick = { viewModel.setReminderPeriod(1) },
                                            label = { Text("Раз в месяц") },
                                        )
                                        FilterChip(
                                            selected = reminder.periodMonths == 3,
                                            onClick = { viewModel.setReminderPeriod(3) },
                                            label = { Text("Раз в 3 месяца") },
                                        )
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "День месяца: ${reminder.day}-е число",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    ApuPremiumSlider(
                                        value = reminder.day.toFloat(),
                                        onValueChange = { viewModel.setReminderDay(it.toInt()) },
                                        valueRange = 1f..28f,
                                        steps = 26,
                                    )

                                    // Золотой пузырь действия - фирменный стиль.
                                    ApuActionBubble(
                                        label = "Показать пример напоминания",
                                        icon = Icons.Default.Favorite,
                                        onClick = {
                                            viewModel.sendTestNotification()
                                            toast = "Пример отправлен в уведомления"
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        // Фирменный пузырь-подсказка про безопасность.
                        HintBubble {
                            Column {
                                Text(
                                    "Безопасно",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HintBubbleTextColor,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "APU сам ничего не списывает и платёжных данных не " +
                                        "хранит: напоминание - обычное локальное " +
                                        "уведомление, никуда не ходит. Перевод вы делаете " +
                                        "сами в своём банке; там же, если захотите, можно " +
                                        "включить его регулярным (автоплатёж).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HintBubbleMutedColor,
                                )
                            }
                        }
                    }

                    // ── Подвал-пометка черновика ──────────────────────────
                    item {
                        Text(
                            "Раздел в разработке (черновик). Личные данные разработчика " +
                                "в приложении нигде не хранятся.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
                ApuScrollbar(state = scrollState)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DraftBubble(text: String) {
    HintBubble {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = HintBubbleMutedColor,
        )
    }
}
