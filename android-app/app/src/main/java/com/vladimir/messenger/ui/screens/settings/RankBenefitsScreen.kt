package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsChip
import com.vladimir.messenger.ui.components.ApuSettingsFeatureRow
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ApuSettingsProgress
import com.vladimir.messenger.ui.components.ApuSettingsSectionTitle
import com.vladimir.messenger.ui.components.ApuVipBadge
import com.vladimir.messenger.ui.components.ApuFormTextField
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.PromoCodes
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuScrollbar
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.RankMedal
import com.vladimir.messenger.ui.components.swipeBack
import com.vladimir.messenger.data.link.ShortShare
import com.vladimir.messenger.util.OwnInvite
import kotlinx.coroutines.delay
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankBenefitsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    // Счётчик обновляется после промокода: увеличиваем метку - и ранг с
    // числом друзей перечитываются из хранилища.
    var refresh by remember { mutableIntStateOf(0) }
    var showInviteShare by remember { mutableStateOf(false) }
    val qualified = remember(refresh) { ReferralRankStore.qualifiedDirectCount(context) }
    val earned = remember(refresh) { ReferralRankStore.earnedDirectCount(context) }
    val promoBonus = remember(refresh) { PromoCodes.bonus(context) }
    val current = FileTransferRankPolicy.entitlement(qualified)
    val next = FileTransferRankPolicy.nextTier(qualified)

    // Обои APU подложкой, как на остальных экранах: каркас и шапка прозрачные.
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
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = Color.Transparent,
                ),
                title = { ApuSettingsHeader(stringResource(R.string.rank_benefits_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
    ) { padding ->
        // Бегунок справа: видно, где мы в длинном списке.
        val scrollState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = scrollState,
                // Клавиатура сжимает список, а не накрывает поле промокода:
                // тот же приём, что на экранах защиты личности и резервных копий.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    // Ранг растёт только от приглашённых, поэтому кнопка «позвать друга»
                    // стоит прямо здесь, а не спрятана в настройках.
                    ApuSettingsCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            IconTitleRow(
                                icon = Icons.Default.Share,
                                title = stringResource(R.string.rank_grows_title),
                            )
                            Text(
                                stringResource(R.string.rank_invite_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                            ApuPremiumContentButton(
                                onClick = { showInviteShare = true },
                                style = DiagnosticsActionStyle.PRIMARY,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.rank_invite_friend))
                            }
                        }
                    }
                }
                item {
                    ApuSettingsCard(modifier = Modifier.fillMaxWidth(), highlighted = true) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Та же медаль, что и на главной: значок ранга должен
                            // узнаваться в обоих местах.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // У VIP медаль особая: лента фиолетово-золотая и
                                // кольцо элиты вокруг диска.
                                RankMedal(size = 40.dp, vip = current.isVip)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            current.rankName,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (current.isVip) {
                                            Spacer(Modifier.width(8.dp))
                                            ApuVipBadge()
                                        }
                                    }
                                    Text(
                                        "Подтверждённых друзей: $earned",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = HintBubbleMutedColor,
                                    )
                                }
                                ApuSettingsChip(stringResource(R.string.rank_your_rank))
                            }
                            // VIP - знак признания, а не новая возможность:
                            // говорим об этом прямо, чтобы значок не выглядел
                            // обещанием платных функций.
                            if (current.isVip) {
                                Text(
                                    stringResource(R.string.rank_vip_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HintBubbleMutedColor,
                                )
                            } else {
                                val toVip = FileTransferRankPolicy.referralsToVip(qualified)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ApuVipBadge()
                                    Text(
                                        "До VIP осталось приглашений: $toVip",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            if (promoBonus > 0) {
                                // Видно, что пришло от друзей, а что от промокода -
                                // иначе число выглядело бы взявшимся из ниоткуда.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ApuSettingsChip("промокод +$promoBonus")
                                    Text(
                                        "Всего к рангу: $qualified",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            val ahead = next
                            if (ahead != null) {
                                // Полоска вместо сухой строки: видно, сколько уже
                                // пройдено и сколько осталось.
                                val taken = qualified - current.minimumQualifiedReferrals
                                val span = (ahead.minimumQualifiedReferrals -
                                    current.minimumQualifiedReferrals).coerceAtLeast(1)
                                val needed = ahead.minimumQualifiedReferrals - qualified
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "До ранга «${ahead.rankName}»",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ApuSettingsChip("осталось $needed", highlighted = false)
                                }
                                ApuSettingsProgress(fraction = taken.toFloat() / span.toFloat())
                                // Что именно откроет следующий ранг - списком, а не
                                // строкой через запятую: видно, ради чего звать друзей.
                                val opensLater = ahead.unlockedFeatureSummary()
                                    .filterNot { it in current.unlockedFeatureSummary() }
                                if (opensLater.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.rank_unlocks_next),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = HintBubbleMutedColor,
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        opensLater.forEach { feature ->
                                            ApuSettingsFeatureRow(feature, available = false)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            // Описание строится от РЕАЛЬНОГО ранга, а не от нулевого:
                            // ранг, полученный по промокоду, работает так же, как
                            // заработанный приглашениями.
                            Text(
                                stringResource(R.string.rank_available_now),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                current.unlockedFeatureSummary().forEach { feature ->
                                    ApuSettingsFeatureRow(feature)
                                }
                            }
                            if (ahead == null) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.rank_top),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HintBubbleMutedColor,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.rank_referral_rules),
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                        }
                    }
                }
                // Ранги разделены на две части (решение владельца 2026-10-07):
                // «Проводник» (10-й) и выше - VIP, элита приложения; ниже -
                // обычные ранги.
                item {
                    ApuSettingsSectionTitle(stringResource(R.string.rank_section_ranks))
                }
                items(FileTransferRankPolicy.regularTiers, key = { it.minimumQualifiedReferrals }) { tier ->
                    RankTierCard(tier = tier, current = current, qualified = qualified)
                }
                item {
                    // Вторая половина списка: VIP. Заголовок обязателен - по нему
                    // видно, где кончаются обычные ранги и начинается элита.
                    ApuSettingsSectionTitle(stringResource(R.string.rank_section_vip))
                }
                items(FileTransferRankPolicy.vipTiers, key = { it.minimumQualifiedReferrals }) { tier ->
                    RankTierCard(tier = tier, current = current, qualified = qualified)
                }
                item {
                    // Пояснение про размер файлов - отдельным пузырём-подсказкой,
                    // как на остальных экранах, а не строкой поверх обоев.
                    HintBubble {
                        Column {
                            Text(
                                stringResource(R.string.rank_file_size_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = HintBubbleTextColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.rank_file_size_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                        }
                    }
                }
                // Пузырь с промокодом - в самом низу раздела.
                item {
                    PromoCodeCard(
                        onRedeemed = { refresh++ },
                    )
                }
            }
            ApuScrollbar(state = scrollState)
        }
    }
    }

    // Раунд 200: перед отправкой приглашения спрашиваем про APK.
    if (showInviteShare) {
        com.vladimir.messenger.ui.components.InviteAttachDialog(
            title = stringResource(R.string.rank_invite_in_apu),
            onDismiss = { showInviteShare = false },
            onShare = { attach ->
                showInviteShare = false
                OwnInvite.link(context)?.let { link ->
                    ShortShare.shareInvite(
                        context, OwnInvite.displayName(context), link, attach,
                    )
                }
            },
        )
    }
}

/**
 * Карточка одной ступени ранга. Одна на обе половины списка: обычные ранги и
 * VIP отличаются только знаком [ApuVipBadge] у названия, поэтому второй такой
 * же карточки в файле быть не должно.
 */
@Composable
private fun RankTierCard(
    tier: FileTransferRankPolicy.Entitlement,
    current: FileTransferRankPolicy.Entitlement,
    qualified: Int,
) {
    val isCurrent = tier == current
    val reached = qualified >= tier.minimumQualifiedReferrals
    ApuSettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${tier.minimumQualifiedReferrals} — ${tier.rankName}",
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        )
                        if (tier.isVip) {
                            Spacer(Modifier.width(8.dp))
                            ApuVipBadge(compact = true)
                        }
                    }
                    if (!reached) {
                        Text(
                            "Нужно приглашений: ${tier.minimumQualifiedReferrals}",
                            style = MaterialTheme.typography.bodySmall,
                            color = HintBubbleMutedColor,
                        )
                    }
                }
                if (isCurrent) {
                    ApuSettingsChip(stringResource(R.string.rank_your_rank))
                } else if (reached) {
                    ApuSettingsChip(stringResource(R.string.rank_reached), highlighted = false)
                }
            }
            // Возможности недостигнутого ранга показаны закрытыми
            // (серый замок), достигнутого - золотой галочкой.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tier.unlockedFeatureSummary().forEach { feature ->
                    ApuSettingsFeatureRow(feature, available = reached)
                }
            }
        }
    }
}

/** Заголовок карточки: значок в фирменном квадратике и текст рядом. */
@Composable
private fun IconTitleRow(
    icon: ImageVector,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(36.dp)
                .background(ApuBubbleAccentColor.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = ApuBubbleAccentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Пузырь ввода промокода.
 *
 * Код проверяется на самом телефоне: сервера у APU нет, сверять не с чем.
 * Поэтому промокод - это подарок, а не платная покупка: тот, кто узнал код,
 * применит его у себя. Повторно на одном телефоне код не сработает.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PromoCodeCard(onRedeemed: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // Клавиатура не должна накрывать ни поле, ни кнопку «Применить»: когда поле
    // получает фокус, список подъезжает так, чтобы над клавиатурой оказался весь
    // блок «поле + кнопка». Просим подвести именно блок: если просить одно поле,
    // кнопка под ним остаётся за клавиатурой (поймано на телефоне 2026-10-06,
    // v11.74.187). Две попытки с разной паузой: первая - как только клавиатура
    // начала подниматься, вторая - когда она уже заняла своё место и список
    // успел сжаться (анимация клавиатуры занимает около трети секунды).
    val promoBlock = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            delay(250)
            promoBlock.bringIntoView()
            delay(400)
            promoBlock.bringIntoView()
        }
    }

    ApuSettingsCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = true,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTitleRow(icon = Icons.Default.Redeem, title = stringResource(R.string.rank_promo))
            Text(
                "Есть промокод? Введите его - и к рангу прибавится " +
                    "${PromoCodes.BONUS_PER_CODE} подтверждённых друзей.",
                style = MaterialTheme.typography.bodySmall,
                color = HintBubbleMutedColor,
            )
            // Поле и кнопка - один блок: список подводит его целиком, поэтому
            // кнопка «Применить» видна вместе с полем, а не остаётся под
            // клавиатурой.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(promoBlock),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ApuFormTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        message = null
                    },
                    // Подсказка НЕ показывает настоящий код: пример выдал бы
                    // рабочий промокод любому, кто просто открыл раздел.
                    label = stringResource(R.string.rank_your_promo),
                    placeholder = stringResource(R.string.rank_enter_promo),
                    isError = isError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state -> focused = state.isFocused },
                )
                ApuPremiumContentButton(
                    onClick = {
                        when (PromoCodes.redeem(context, code)) {
                            PromoCodes.Result.APPLIED -> {
                                isError = false
                                message = context.getString(R.string.rb_promo_applied, PromoCodes.BONUS_PER_CODE)
                                code = ""
                                onRedeemed()
                            }
                            PromoCodes.Result.UNKNOWN -> {
                                isError = true
                                message = context.getString(R.string.rb_promo_unknown)
                            }
                            PromoCodes.Result.ALREADY_USED -> {
                                isError = true
                                message = context.getString(R.string.rb_promo_used)
                            }
                            PromoCodes.Result.LIMIT_REACHED -> {
                                isError = true
                                message = "Промокодами набран предел: " +
                                    "${PromoCodes.MAX_PROMO_BONUS}"
                            }
                        }
                    },
                    style = DiagnosticsActionStyle.PRIMARY,
                    enabled = code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rank_apply))
                }
            }
            message?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
