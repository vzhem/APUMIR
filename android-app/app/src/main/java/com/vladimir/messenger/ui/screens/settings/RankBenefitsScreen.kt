package com.vladimir.messenger.ui.screens.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.PromoCodes
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.util.OwnInvite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankBenefitsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    // Счётчик обновляется после промокода: увеличиваем метку - и ранг с
    // числом друзей перечитываются из хранилища.
    var refresh by remember { mutableIntStateOf(0) }
    val qualified = remember(refresh) { ReferralRankStore.qualifiedDirectCount(context) }
    val earned = remember(refresh) { ReferralRankStore.earnedDirectCount(context) }
    val promoBonus = remember(refresh) { PromoCodes.bonus(context) }
    val current = FileTransferRankPolicy.entitlement(qualified)

    // Обои APU подложкой, как на остальных экранах: каркас и шапка прозрачные.
    Box(modifier = Modifier.fillMaxSize()) {
    ChatWallpaper()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = { Text("Ранги и возможности") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // Ранг растёт только от приглашённых, поэтому кнопка «позвать друга»
                // стоит прямо здесь, а не спрятана в настройках.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ранг растёт от приглашённых друзей", fontWeight = FontWeight.Medium)
                        Text(
                            "Отправьте ссылку другу. Приглашение засчитается, когда он добавит " +
                                "вас в контакты и вы обменяетесь сообщениями.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                OwnInvite.link(context)?.let { link ->
                                    AppShare.shareInvite(context, OwnInvite.displayName(context), link)
                                }
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Пригласить друга")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(current.rankName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Подтверждённых друзей: $earned")
                        if (promoBonus > 0) {
                            // Видно, что пришло от друзей, а что от промокода -
                            // иначе число выглядело бы взявшимся из ниоткуда.
                            Text("Бонус по промокоду: +$promoBonus")
                            Text("Всего к рангу: $qualified", fontWeight = FontWeight.Medium)
                        }
                        Text(
                            "Без ранга: текст, получение файлов и вступление в группы/каналы. " +
                                "Отправка файлов, фото и видео — с 3 подтверждённых приглашений. " +
                                "Засчитываются только прямые приглашения после handshake и DELIVERED.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            items(FileTransferRankPolicy.tiers, key = { it.minimumQualifiedReferrals }) { tier ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${tier.minimumQualifiedReferrals} — ${tier.rankName}",
                            fontWeight = if (tier == current) FontWeight.Bold else FontWeight.Medium,
                        )
                        tier.unlockedFeatureSummary().forEach { feature -> Text("• $feature") }
                    }
                }
            }
            item {
                Text(
                    "Приложение не задаёт лимит размера файла. Фактическая передача зависит от " +
                        "свободного места, возможностей устройства и доступной сети.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Пузырь с промокодом - в самом низу раздела.
            item {
                PromoCodeCard(
                    onRedeemed = { refresh++ },
                )
            }
        }
    }
    }
}

/**
 * Пузырь ввода промокода.
 *
 * Код проверяется на самом телефоне: сервера у APU нет, сверять не с чем.
 * Поэтому промокод - это подарок, а не платная покупка: тот, кто узнал код,
 * применит его у себя. Повторно на одном телефоне код не сработает.
 */
@Composable
private fun PromoCodeCard(onRedeemed: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Redeem, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Промокод", fontWeight = FontWeight.Bold)
            }
            Text(
                "Есть промокод? Введите его - и к рангу прибавится " +
                    "${PromoCodes.BONUS_PER_CODE} подтверждённых друзей.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    message = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Например, APU-START-2026") },
                singleLine = true,
            )
            Button(
                onClick = {
                    when (PromoCodes.redeem(context, code)) {
                        PromoCodes.Result.APPLIED -> {
                            isError = false
                            message = "Промокод принят: +${PromoCodes.BONUS_PER_CODE} к рангу"
                            code = ""
                            onRedeemed()
                        }
                        PromoCodes.Result.UNKNOWN -> {
                            isError = true
                            message = "Такого промокода нет - проверьте написание"
                        }
                        PromoCodes.Result.ALREADY_USED -> {
                            isError = true
                            message = "Этот промокод здесь уже использован"
                        }
                        PromoCodes.Result.LIMIT_REACHED -> {
                            isError = true
                            message = "Промокодами набран предел: " +
                                "${PromoCodes.MAX_PROMO_BONUS}"
                        }
                    }
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Применить")
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
