package com.vladimir.messenger.ui.components

// =============================================================================
// APUDIAGNOSTICSUI.KT — окно «Логи» в фирменном стиле APU
// =============================================================================
// Раньше «Логи» были одной простыней моноширинного текста: человек не видел,
// что происходит, пока не прочитает всё. Теперь сверху — короткая сводка
// (сеть, брокер, ядро, передачи, прямой F4-канал, батарея) с цветными
// метками, итогом и легендой цветов, ниже — отчёт в отдельной карточке
// с шапкой; разделы [сводка]/[ядро]/… подсвечены бронзовым, а строки
// с «!» и «✖» сразу видны своим цветом.
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.diagnostics.DiagnosticsLevel
import com.vladimir.messenger.data.diagnostics.DiagnosticsLine
import com.vladimir.messenger.data.diagnostics.DiagnosticsLineTone
import com.vladimir.messenger.data.diagnostics.diagnosticsLineTone

/** Единый оттенок «внимания»: им же подсвечиваются строки журнала в отчёте. */
private val DiagnosticsWarnColor = Color(0xFF8F4A00)

/** Цвет метки уровня: зелёный — порядок, бронзовый — справка, оранжевый — внимание, красный — поломка. */
fun diagnosticsLevelColor(level: DiagnosticsLevel): Color = when (level) {
    DiagnosticsLevel.OK -> Color(0xFF2E7D32)
    DiagnosticsLevel.INFO -> ApuBubbleAccentColor
    DiagnosticsLevel.WARN -> DiagnosticsWarnColor
    DiagnosticsLevel.BAD -> ApuSettingsDangerColor
}

/** Слова для легенды: у каждого цвета на сводке есть понятное название. */
private fun diagnosticsLevelLabel(level: DiagnosticsLevel): String = when (level) {
    DiagnosticsLevel.OK -> "порядок"
    DiagnosticsLevel.INFO -> "справка"
    DiagnosticsLevel.WARN -> "внимание"
    DiagnosticsLevel.BAD -> "поломка"
}

/** Сводка «что происходит сейчас»: итог, строки проверок с метками уровней и легенда. */
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
            if (lines.isEmpty()) {
                Text(
                    "Проверки ещё не собраны: откройте «Логи» снова через пару секунд.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            } else {
                ApuDiagnosticsVerdictRow(lines)
                ApuDiagnosticsDivider()
                lines.forEachIndexed { index, line ->
                    if (index > 0) ApuDiagnosticsDivider()
                    ApuDiagnosticsStatusRow(line)
                }
                ApuDiagnosticsDivider()
                ApuDiagnosticsLegend()
            }
        }
    }
}

/** Общий итог одной строкой: сразу видно, есть ли ошибки, не читая все проверки. */
@Composable
private fun ApuDiagnosticsVerdictRow(lines: List<DiagnosticsLine>) {
    val bads = lines.count { it.level == DiagnosticsLevel.BAD }
    val warns = lines.count { it.level == DiagnosticsLevel.WARN }
    val level = when {
        bads > 0 -> DiagnosticsLevel.BAD
        warns > 0 -> DiagnosticsLevel.WARN
        else -> DiagnosticsLevel.OK
    }
    val color = diagnosticsLevelColor(level)
    val label = when {
        bads > 0 -> "есть ошибки: $bads"
        warns > 0 -> "есть предупреждения: $warns"
        else -> "всё в порядке"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(9.dp))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "проверок ${lines.size}",
            style = MaterialTheme.typography.bodySmall,
            color = ApuBubbleMutedColor,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Легенда цветов: четыре точки теми же красками, что и метки в сводке. */
@Composable
private fun ApuDiagnosticsLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ApuDiagnosticsLegendItem(DiagnosticsLevel.OK, Modifier.weight(1f))
            ApuDiagnosticsLegendItem(DiagnosticsLevel.INFO, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ApuDiagnosticsLegendItem(DiagnosticsLevel.WARN, Modifier.weight(1f))
            ApuDiagnosticsLegendItem(DiagnosticsLevel.BAD, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ApuDiagnosticsLegendItem(level: DiagnosticsLevel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(diagnosticsLevelColor(level)),
        )
        Text(
            diagnosticsLevelLabel(level),
            style = MaterialTheme.typography.labelSmall,
            color = ApuBubbleMutedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ApuDiagnosticsDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = ApuBubbleAccentColor.copy(alpha = 0.15f),
    )
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
 * Отчёт: шапка «Полный отчёт», необязательная подпись (когда и что собрано),
 * число строк и моноширинный текст в своей рамке — с выделением и прокруткой,
 * чтобы его копировали целиком или выделяли нужный кусок руками.
 */
@Composable
fun ApuDiagnosticsReportCard(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
    caption: String? = null,
) {
    val scroll = rememberScrollState()
    ApuSettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = ApuBubbleAccentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Полный отчёт",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ApuBubbleTextColor,
                    )
                }
                Text(
                    "строк ${text.count { it == '\n' } + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                    maxLines = 1,
                )
            }
            caption?.takeIf { it.isNotBlank() }?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
            SelectionContainer {
                Box(
                    modifier = Modifier
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
                        diagnosticsReportAnnotated(text),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = ApuBubbleTextColor,
                    )
                }
            }
        }
    }
}

/**
 * Раскрашивает отчёт, не меняя ни одного символа: разделы — бронзовым,
 * ошибки — красным, предупреждения — оранжевым. Решение о тоне строки
 * принимает чистая [diagnosticsLineTone] (её проверяют JVM-тесты), здесь
 * только краски house style. Текст остаётся тем же, поэтому копируется
 * и уезжает разработчику ровно так, как выглядит.
 */
fun diagnosticsReportAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val style = when (diagnosticsLineTone(line)) {
            DiagnosticsLineTone.SECTION ->
                SpanStyle(color = ApuBubbleAccentColor, fontWeight = FontWeight.Bold)
            DiagnosticsLineTone.BAD -> SpanStyle(color = ApuSettingsDangerColor)
            DiagnosticsLineTone.WARN -> SpanStyle(color = DiagnosticsWarnColor)
            null -> null
        }
        if (style == null) append(line) else withStyle(style) { append(line) }
    }
}
