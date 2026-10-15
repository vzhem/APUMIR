package com.vladimir.messenger.ui.components

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.profile.Birthday
import com.vladimir.messenger.data.profile.BirthdayBanner
import java.time.LocalDate

/**
 * «День рождения» в профиле: выбор даты.
 *
 * Галочка «дата видна друзьям» стоит рядом с датой в самом профиле (в строке
 * настройки), а здесь только сама дата. Год остаётся на этом телефоне: по сети
 * едет лишь число и месяц.
 */
@Composable
fun BirthdayEditDialog(
    initial: Birthday?,
    onDismiss: () -> Unit,
    onSave: (day: Int, month: Int, year: Int) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    var day by rememberSaveable { mutableIntStateOf(initial?.day ?: 0) }
    var month by rememberSaveable { mutableIntStateOf(initial?.month ?: 0) }
    var year by rememberSaveable { mutableIntStateOf(initial?.year ?: Birthday.NO_YEAR) }
    val chosen = Birthday.isValid(day, month)

    if (picker) {
        // Календарь — системный, как в «отложенной отправке»: свой календарь
        // ради одной даты не нужен, а привычное окно человек знает.
        DisposableEffect(context, picker) {
            val today = LocalDate.now()
            // Календарь открывается на сохранённой дате. Если год не указан или
            // дата в нём не существует (29 февраля невисокосного года), берём
            // запасной год: ронять окно из-за этого нельзя.
            val selected = if (chosen) {
                runCatching {
                    LocalDate.of(
                        year.takeIf { it != Birthday.NO_YEAR } ?: PICKER_DEFAULT_YEAR,
                        month,
                        day,
                    )
                }.getOrElse { LocalDate.of(PICKER_DEFAULT_YEAR, month, 1) }
            } else {
                LocalDate.of(today.year - PICKER_DEFAULT_AGE, 1, 1)
            }
            val dialog = DatePickerDialog(
                context,
                { _, pickedYear, pickedMonth, pickedDay ->
                    day = pickedDay
                    month = pickedMonth + 1
                    year = pickedYear
                    picker = false
                },
                selected.year,
                selected.monthValue - 1,
                selected.dayOfMonth,
            )
            dialog.setOnDismissListener { picker = false }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.birthday_dialog_title)) },
        dismissButton = { ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss) },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.birthday_save),
                enabled = chosen,
                onClick = { if (chosen) onSave(day, month, year) },
            )
        },
        text = {
            Column {
                Text(
                    if (chosen) Birthday.label(day, month, year) else stringResource(R.string.settings_birthday_not_set),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(10.dp))
                ApuActionBubble(
                    label = stringResource(R.string.birthday_pick_date),
                    icon = Icons.Filled.DateRange,
                ) { picker = true }
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.birthday_year_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                if (initial != null) {
                    Spacer(Modifier.size(6.dp))
                    ApuTextAction(
                        label = stringResource(R.string.birthday_clear),
                        danger = true,
                        onClick = onClear,
                    )
                }
            }
        },
    )
}

/**
 * Пузырь «у кого-то сегодня день рождения» вверху ленты.
 *
 * Кружок с фотографией и значком-тортиком, имя и приглашение поздравить.
 * Крестик убирает пузырь до конца дня (правило — в `BirthdayBanner`).
 */
@Composable
fun BirthdayBannerRow(
    guest: BirthdayBanner.Guest,
    avatarB64: String?,
    othersCount: Int,
    onCongratulate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Появление: пузырь «всплывает» один раз, а не перерисовывается при каждом
    // обновлении ленты.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
    ) {
        ApuBubbleCard(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            premium = 7.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onCongratulate)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeerAvatar(
                    name = guest.name,
                    avatarB64 = avatarB64,
                    size = 44.dp,
                    overlay = {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(apuGoldBrush())
                                .border(1.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Cake,
                                contentDescription = null,
                                tint = ApuGoldInk,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Имя — жирным, остальное обычным: строка читается с одного
                    // взгляда и не превращается в однообразный абзац.
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(guest.name) }
                            append(" ")
                            append(stringResource(R.string.birthday_banner_suffix))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = ApuBubbleTextColor,
                        maxLines = 2,
                    )
                    Text(
                        stringResource(R.string.birthday_banner_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ApuBubbleAccentColor,
                    )
                    if (othersCount > 0) {
                        Text(
                            stringResource(R.string.birthday_banner_more, othersCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.birthday_banner_hide),
                        tint = ApuBubbleMutedColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Год по умолчанию для календаря, когда дата ещё не выбрана: тридцать лет
 * назад. Так человеку обычно достаточно поправить год, а не листать от 1900.
 */
/**
 * Запасной возраст для календаря: [LocalDate.of] принимает Int, поэтому тут
 * обычное число, а не Long (иначе вызов не компилируется).
 */
private const val PICKER_DEFAULT_AGE = 30
private const val PICKER_DEFAULT_YEAR = 2000
