package com.vladimir.messenger.ui.components

// =============================================================================
// APUDIAGNOSTICSUI.KT — окно «Логи» в фирменном стиле APU
// =============================================================================
// Раньше «Логи» были одной простыней моноширинного текста: человек не видел,
// что происходит, пока не прочитает всё. Теперь сверху — короткая сводка
// (сеть, брокер, ядро, передачи, прямой F4-канал, батарея) с цветными
// метками, ниже — тот же текст отчёта, который копируется и уезжает
// разработчику.
//
// Палитра и формы — из ApuBubble/ApuSettingsUi: светлый пузырь, бронзовый
// акцент, рамка 18dp. Ничего нового не изобретаем.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.diagnostics.DiagnosticsLevel
import com.vladimir.messenger.data.diagnostics.DiagnosticsLine

/** Цвет метки уровня: зелёный — порядок, бронзовый — справка, оранжевый — внимание, красный — поломка. */
fun diagnosticsLevelColor(level: DiagnosticsLevel): Color = when (level) {
    DiagnosticsLevel.OK -> Color(0xFF2E7D32)
    DiagnosticsLevel.INFO -> ApuBubbleAccentColor
    DiagnosticsLevel.WARN -> Color(0xFF8F4A00)
    DiagnosticsLevel.BAD -> ApuSettingsDangerColor
}

/** Сводка «что происходит сейчас»: список строк с метками уровней. */
@Composable
fun ApuDiagnosticsStatusCard(
    lines: List<DiagnosticsLine>,
    modifier: Modifier = Modifier,
) {
    // ApuSettingsCard, а не голый ApuBubbleCard: он же фиксирует локальную
    // палитру (тёмный текст и читаемые акценты при любых обоях и в ночной
    // теме) — та же карточка, что у остальных блоков настроек.
    ApuSettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lines.forEachIndexed { index, line ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = ApuBubbleAccentColor.copy(alpha = 0.15f),
                    )
                }
                ApuDiagnosticsStatusRow(line)
            }
        }
    }
}

/** Одна строка сводки: метка уровня, название (Сеть/Брокер/…) и состояние словами. */
@Composable
fun ApuDiagnosticsStatusRow(line: DiagnosticsLine, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(diagnosticsLevelColor(line.level)),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                line.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = ApuBubbleTextColor,
            )
            Text(
                line.value,
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }
    }
}

/**
 * Текст отчёта: моноширинный, с собственной прокруткой и выделением — его
 * копируют целиком или выделяют нужный кусок руками.
 */
@Composable
fun ApuDiagnosticsReportCard(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
) {
    val scroll = rememberScrollState()
    SelectionContainer {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF0EFEA))
                .border(
                    1.dp,
                    ApuBubbleAccentColor.copy(alpha = 0.22f),
                    RoundedCornerShape(14.dp),
                )
                .verticalScroll(scroll)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = ApuBubbleTextColor,
            )
        }
    }
}
