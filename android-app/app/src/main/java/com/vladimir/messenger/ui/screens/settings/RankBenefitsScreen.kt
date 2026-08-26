package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankBenefitsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val qualified = ReferralRankStore.qualifiedDirectCount(context)
    val current = FileTransferRankPolicy.entitlement(qualified)

    Scaffold(
        topBar = {
            TopAppBar(
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(current.rankName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Подтверждённых друзей: $qualified")
                        Text(
                            "Текст, отправка и получение файлов, вступление в группы/каналы доступны без ранга. " +
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
        }
    }
}
