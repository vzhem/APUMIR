package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// =============================================================================
// Владелец 2026-10-07: «Теперь в таком стиле нужно переделать всё приложение…
// чтобы был стиль как в логах. Начни с вкладки настройки.»
//
// Все общие компоненты настроек/профиля переведены на премиальный слой
// (ui/components/ApuPremium.kt): золотые плитки и капсулы, глянец у кромок,
// мягкие тени, золотая нить над карточкой. Через эти компоненты стиль
// автоматически приходит во все разделы, которые их используют (профиль, ранги,
// группы, каналы), — экраны не переписываются по одному.
//
// Неприкосновенно: house-подложка (ApuBubbleCard/поверхность пузыря), читаемость
// (текст — тёмные чернила, контраст ≥4.5) и правило «в строках списка нет вечно
// бегущих анимаций» (см. PROFILE_SETTINGS_STYLE.md).
// =============================================================================

/**
 * Красный акцент для опасных действий и предупреждений (удаление, блокировка,
 * жалоба): тот же тон, что у `error` в фирменной палитре ниже.
 */
val ApuSettingsDangerColor: Color = Color(0xFFA12D3A)

/** Fixed, readable controls inside the light house bubble, including in night mode. */
@Composable
private fun ApuSettingsPalette(content: @Composable () -> Unit) {
    val colours = MaterialTheme.colorScheme.copy(
        primary = ApuBubbleAccentColor,
        onPrimary = Color(0xFFFFF8E6),
        primaryContainer = Color(0xFFF5E8C6),
        onPrimaryContainer = Color(0xFF463205),
        secondary = ApuBubbleLinkColor,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE4EDF8),
        onSecondaryContainer = Color(0xFF263A4C),
        surface = ApuBubbleSurfaceColor,
        onSurface = ApuBubbleTextColor,
        surfaceVariant = Color(0xFFF0EFEA),
        onSurfaceVariant = ApuBubbleMutedColor,
        outline = ApuBubbleAccentColor.copy(alpha = 0.38f),
        outlineVariant = ApuBubbleAccentColor.copy(alpha = 0.16f),
        error = Color(0xFFA12D3A),
        surfaceTint = ApuBubbleAccentColor,
    )
    MaterialTheme(colorScheme = colours, content = content)
}

/** Uses the exact shared bubble, not a second Material-card design. */
@Composable
fun ApuSettingsCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ApuBubbleCard(
        // Объём и золотая нить: карточка «стоит» над обоями, а не нарисована
        // краской (премиальный слой, как в окне «Логи»). Слои рисует сама
        // карточка: переданные в modifier они легли бы ПОД её фоном и были бы
        // не видны.
        modifier = modifier.fillMaxWidth(),
        backgroundColor = if (highlighted) Color(0xFFFFF7E5).copy(alpha = 0.94f) else ApuBubbleSurfaceColor,
        premium = if (highlighted) 10.dp else 7.dp,
    ) {
        val scope = this
        ApuSettingsPalette { scope.content() }
    }
}

@Composable
fun ApuSettingsHeader(title: String) {
    ApuHeaderBubble {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Золотая «искра» перед названием: тот же знак, что у эмблемы в
            // «Логах», — раздел узнаётся как часть одного приложения.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(apuGoldBrush()),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ApuBubbleTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ApuSettingsSectionTitle(title: String, modifier: Modifier = Modifier) {
    // Капсула раздела: золотая заливка, тёмные чернила, глянец и мягкая тень.
    // Справа — золотая нить, растворяющаяся к краю: секция «открывается» ею.
    Row(
        modifier = modifier.padding(start = 18.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(11.dp)
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = ApuGoldInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .apuPremiumLift(6.dp, shape, ApuGold.copy(alpha = 0.5f))
                .clip(shape)
                .background(apuGoldBrush())
                .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
                .apuPremiumGloss(shape, intensity = 0.9f, topFraction = 0.7f)
                .padding(horizontal = 11.dp, vertical = 5.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            ApuGold.copy(alpha = 0.55f),
                            ApuGoldLight.copy(alpha = 0.30f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@Composable
fun ApuSettingsDivider() {
    // Та же золотая нить, что разделяет блоки в окне «Логи»: тонкая, светится
    // к середине и растворяется к краям. Отступ от иконки сохранён прежним.
    Box(
        modifier = Modifier
            .padding(start = 72.dp, end = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        ApuGold.copy(alpha = 0.45f),
                        ApuGoldLight.copy(alpha = 0.30f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/** Stable crisp icon; the row does not spend battery on an always-running shimmer. */
@Composable
fun ApuSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Плитка со значком — золотая, с глянцем и тенью (как кнопки в «Логах»).
        // Никаких непрерывных анимаций: строка не тратит батарею на перерисовку.
        ApuPremiumIconTile(icon = icon)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = ApuBubbleTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ApuBubbleAccentColor.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Строка списка возможностей ранга: значок в фирменном квадратике + текст.
 *
 * Появилась, чтобы «Ранги и возможности» не выглядели списком с маркерами
 * «•»: возможность либо уже открыта (золотая галочка), либо ещё заперта
 * (серый замок) — видно с одного взгляда, без чтения пояснений.
 */
@Composable
fun ApuSettingsFeatureRow(
    text: String,
    available: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ApuPremiumIconTile(
            icon = if (available) Icons.Default.Check else Icons.Default.Lock,
            contentDescription = if (available) "Открыто" else "Ещё закрыто",
            size = 26.dp,
            corner = 8.dp,
            lift = 3.dp,
            gloss = 0.7f,
            background = if (available) {
                apuGoldBrush()
            } else {
                Brush.linearGradient(
                    listOf(
                        ApuBubbleMutedColor.copy(alpha = 0.22f),
                        ApuBubbleMutedColor.copy(alpha = 0.12f),
                    ),
                )
            },
            tint = if (available) ApuGoldInk else ApuBubbleMutedColor,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (available) ApuBubbleTextColor else ApuBubbleMutedColor,
        )
    }
}

/** Короткая плашка-ярлык («ваш ранг», «промокод +10»): тот же тон, что у подписей. */
@Composable
fun ApuSettingsChip(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = true,
) {
    val shape = RoundedCornerShape(9.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (highlighted) ApuGoldInk else ApuBubbleMutedColor,
        maxLines = 1,
        modifier = modifier
            .then(
                if (highlighted) {
                    Modifier
                        .apuPremiumLift(4.dp, shape, ApuGold.copy(alpha = 0.45f))
                        .clip(shape)
                        .background(apuGoldBrush())
                        .border(1.dp, Color.White.copy(alpha = 0.45f), shape)
                        .apuPremiumGloss(shape, intensity = 0.8f, topFraction = 0.7f)
                } else {
                    Modifier
                        .clip(shape)
                        .background(ApuBubbleMutedColor.copy(alpha = 0.10f))
                },
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Полоска «сколько осталось до следующего ранга»: спокойное золото APU. */
@Composable
fun ApuSettingsProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    // Заполнение — золотой градиент с глянцем: «сколько осталось до ранга»
    // читается как награда, а не как серая полоска загрузки.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(ApuBubbleAccentColor.copy(alpha = 0.14f))
            .border(1.dp, ApuBubbleAccentColor.copy(alpha = 0.18f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(shape)
                .background(apuGoldBrush())
                .apuPremiumGloss(shape, intensity = 0.8f, topFraction = 0.75f),
        )
    }
}

@Composable
fun ApuProfileQuickAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp))
            .background(ApuBubbleAccentColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ApuPremiumIconTile(icon = icon, size = 34.dp, corner = 11.dp, lift = 5.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = ApuBubbleAccentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Profile/settings modal shares the same light surface and readable control palette. */
@Composable
fun ApuSettingsDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    // Владелец 2026-10-07: «Доделывай все разделы с новым стилем».
    // Раньше это был stock AlertDialog, лишь перекрашенный в цвета пузыря:
    // плоские углы Material и «рябь» при нажатии оставались чужими. Теперь это
    // тот же премиальный слой, что у окон «Логи» и настроек: мягкая тень,
    // единая подложка, золотая капсула заголовка и нить к телу, блеск ПОД
    // содержимым. Параметры совпадают с AlertDialog, поэтому все вызовы в
    // разделах переходят сюда без правок.
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        ApuSettingsPalette {
            val shape = RoundedCornerShape(22.dp)
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .apuPremiumLift(14.dp, shape, ApuPremiumShadowColor)
                    .clip(shape)
                    .background(Color(0xFFF7F9FC).copy(alpha = 0.98f))
                    .border(1.dp, ApuGold.copy(alpha = 0.45f), shape)
                    .apuPremiumGloss(shape, intensity = 0.5f, topFraction = 0.35f)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(modifier = Modifier.padding(end = 8.dp)) { icon() }
                    }
                    if (title != null) {
                        val capsule = RoundedCornerShape(11.dp)
                        Box(
                            modifier = Modifier
                                .clip(capsule)
                                .background(apuGoldBrush())
                                .border(1.dp, Color.White.copy(alpha = 0.45f), capsule)
                                .apuPremiumGloss(capsule, intensity = 0.85f, topFraction = 0.7f)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            CompositionLocalProvider(LocalContentColor provides ApuGoldInk) { title() }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.5.dp)
                                .background(apuGoldBrush()),
                        )
                    }
                }
                if (text != null) {
                    Spacer(modifier = Modifier.size(12.dp))
                    // Длинные тексты (правила, подтверждения) прокручиваются:
                    // окно не растягивается выше экрана.
                    Box(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides ApuBubbleTextColor) { text() }
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}
