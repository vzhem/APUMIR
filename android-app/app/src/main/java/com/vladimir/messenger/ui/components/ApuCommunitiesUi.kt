package com.vladimir.messenger.ui.components

// =============================================================================
// APUCOMMUNITIESUI.KT — детали раздела «Сообщества» в современном стиле APU
// =============================================================================
// Владелец 2026-10-15 (скрин раздела): «Нужно этот раздел сделать под
// современный стиль. Лучше чем раздел логи».
//
// До этого «Сообщества» были плоским списком: пустое состояние — белый пузырь
// с голым текстом, загрузка — одинокий спиннер, а объём раздела (сколько
// групп, каналов и что нашлось в сети) не был виден вообще. Здесь появляются
// три детали, которых не было:
//
//   * ApuCommunitiesHero — шапка-сводка: золотая эмблема, название раздела и
//     живые счётчики (группы / каналы / в сети). Объём раздела понятен до
//     прокрутки — так делают современные мессенджеры;
//   * ApuCommunitiesEmpty — пустое состояние: золотая эмблема со свечением,
//     объяснение словами и объёмные кнопки действий вместо подсказки, из
//     которой непонятно, что нажимать;
//   * ApuCommunitiesSkeleton — заглушка загрузки: очертания будущих строк
//     вместо пустого экрана и молчащего спиннера;
//   * ApuCommunityKindBadge — знак вида сообщества на аватаре (группа/канал),
//     чтобы списки читались с одного взгляда.
//
// Стиль тот же, что во всём приложении (docs/PROFILE_SETTINGS_STYLE.md):
// ОДНА палитра ApuPremium.kt, подъём, золотая нить и блеск ПОД содержимым,
// текст — тёмными чернилами, в строках списков нет вечно бегущих анимаций
// (переливы только одноразовые, скелетон живёт лишь пока идёт загрузка).
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.R

/**
 * Шапка раздела «Сообщества»: золотая эмблема, название и три счётчика.
 *
 * Счётчики — про этот телефон и про сеть: сколько своих групп, сколько своих
 * каналов и сколько чужих сообществ сеть предложила прямо сейчас. Человек
 * видит объём раздела, не прокручивая список до конца.
 */
@Composable
fun ApuCommunitiesHero(
    groups: Int,
    channels: Int,
    online: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .apuPremiumLift(8.dp, shape)
            .apuBubbleSurface(shape = shape)
            .apuPremiumThread(shape = shape, inset = 18.dp)
            .apuPremiumGloss(shape, intensity = 0.5f, topFraction = 0.6f)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Эмблема: золотая плитка в круге золотого свечения. Тот же знак,
            // что у эмблемы окна «Логи», поэтому раздел узнаётся сразу.
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(ApuGold.copy(alpha = 0.34f), Color.Transparent),
                            ),
                        ),
                )
                ApuPremiumIconTile(
                    icon = Icons.Filled.Groups,
                    contentDescription = null,
                    size = 42.dp,
                    corner = 14.dp,
                    lift = 6.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.groups_hero_kicker),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ApuGoldDeep,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.groups_hero_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ApuBubbleTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApuCommunityMetric(
                value = groups,
                label = stringResource(R.string.groups_filter_groups),
                modifier = Modifier.weight(1f),
            )
            ApuCommunityMetric(
                value = channels,
                label = stringResource(R.string.groups_filter_channels),
                modifier = Modifier.weight(1f),
            )
            ApuCommunityMetric(
                value = online,
                label = stringResource(R.string.groups_hero_online),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Плитка счётчика шапки: крупное число и подпись под ним. */
@Composable
private fun ApuCommunityMetric(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.72f))
            .border(1.dp, ApuGold.copy(alpha = 0.30f), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = ApuBubbleAccentColor,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ApuBubbleMutedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Пустое состояние раздела: золотая эмблема, объяснение и действия.
 *
 * Пустой экран — не тупик: человек всегда видит, почему здесь пусто, и что
 * нажать дальше. Кнопки те же объёмные, что в окне «Логи», поэтому раздел не
 * выпадает из общего стиля приложения.
 */
@Composable
fun ApuCommunitiesEmpty(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    primaryIcon: ImageVector = Icons.Filled.Link,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    secondaryIcon: ImageVector = Icons.Filled.Add,
    onSecondary: (() -> Unit)? = null,
    quietLabel: String? = null,
    quietIcon: ImageVector = Icons.Filled.Refresh,
    onQuiet: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .apuPremiumLift(8.dp, shape)
            .apuBubbleSurface(color = Color(0xFFF7F9FC).copy(alpha = 0.94f), shape = shape)
            .apuPremiumThread(shape = shape, inset = 26.dp)
            .apuPremiumGloss(shape, intensity = 0.5f, topFraction = 0.5f)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Эмблема со свечением: медальон раздела, а не блёклый серый значок.
        Box(
            modifier = Modifier.size(88.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(ApuGold.copy(alpha = 0.30f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .apuPremiumLift(10.dp, CircleShape, ApuGold.copy(alpha = 0.5f))
                    .clip(CircleShape)
                    .background(apuGoldBrush())
                    .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                    .apuPremiumGloss(CircleShape, intensity = 0.9f, topFraction = 0.7f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = ApuGoldInk,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ApuBubbleTextColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = ApuBubbleMutedColor,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(18.dp))
            ApuPremiumButton(
                label = primaryLabel,
                onClick = onPrimary,
                icon = primaryIcon,
                style = DiagnosticsActionStyle.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            ApuPremiumButton(
                label = secondaryLabel,
                onClick = onSecondary,
                icon = secondaryIcon,
                style = DiagnosticsActionStyle.GLASS,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (quietLabel != null && onQuiet != null) {
            Spacer(Modifier.height(8.dp))
            ApuPremiumButton(
                label = quietLabel,
                onClick = onQuiet,
                icon = quietIcon,
                style = DiagnosticsActionStyle.QUIET,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Заглушка загрузки: очертания будущих строк сообществ. Экран наполняется
 * сразу, а не стоит пустым с одиноким спиннером по центру.
 */
@Composable
fun ApuCommunitiesSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .apuBubbleSurface()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Плашки — те же полосы, что у заглушки ленты (ApuSkeleton):
                // аватар и две строки будущего сообщества.
                Spacer(Modifier.size(46.dp).clip(CircleShape).apuSkeleton())
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .apuSkeleton(),
                    )
                    Spacer(
                        Modifier
                            .fillMaxWidth(0.8f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .apuSkeleton(),
                    )
                }
            }
        }
    }
}

/**
 * Знак вида сообщества на аватаре: золотой медальон с группой или каналом.
 * В общем списке видно с одного взгляда, куда ведёт строка.
 */
@Composable
fun ApuCommunityKindBadge(
    isChannel: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(19.dp)
            .clip(CircleShape)
            .background(apuGoldBrush())
            .border(1.5.dp, Color.White.copy(alpha = 0.92f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isChannel) Icons.Filled.Campaign else Icons.Filled.Groups,
            contentDescription = null,
            tint = ApuGoldInk,
            modifier = Modifier.size(11.dp),
        )
    }
}
