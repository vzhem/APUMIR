package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// APUDIAGNOSTICSUI.KT — окно «Логи»: премиальный вид в стиле современных
// мессенджеров (переработка 2026-10-07 по замечанию владельца: «как то всё по
// пенсионерски и по деревенски… чтобы и цвета были яркие и чёткие, и объём
// кнопок чтобы был выразителен, и блеск передавался премиальный»).
// =============================================================================
// Что изменилось против прежней версии:
//   * шапка — тёмный градиентный баннер (полуночный синий → бронза) с эмблемой,
//     итогом и стеклянными чипами; блик статичный, пока человек не нажал кнопку;
//   * строки проверок — крупные пиктограммы-«well» с яркими градиентами и
//     свечением, а не точки с серым текстом;
//   * отчёт — тёмная КОНСОЛЬ с «светофором» и подсветкой разделов, как в
//     премиальных приложениях; текст при копировании не меняется ни на символ;
//   * кнопки — объёмные: золотая глянцевая основная и стеклянные вторичные, с
//     мягкой тенью, верхним бликом и откликом на нажатие;
//   * журнал событий — отдельным списком (10-07: «нет логов списка вообще»):
//     время, область, уровень и суть, свежие сверху;
//   * без бесконечных переливов: движение есть только в коротком отклике нажатия.
//
// Правило прежних замечаний владельца соблюдено: НИЧЕГО stock Material — только
// наши цвета, наши формы и наши обёртки (ApuSettingsCard/ApuBubble). Читаемость
// не принесена в жертву яркости: насыщенные цвета живут в заливках, полосах и
// градиентах, а текстом остаются тёмные чернила, прошедшие проверку контраста
// (scripts/ci/check-diagnostics-report.py, test_premium_palette_keeps_contrast).
// =============================================================================

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.diagnostics.DiagnosticsJournalEntry
import com.vladimir.messenger.data.diagnostics.DiagnosticsLevel
import com.vladimir.messenger.data.diagnostics.DiagnosticsLine
import com.vladimir.messenger.data.diagnostics.DiagnosticsReport
import com.vladimir.messenger.data.diagnostics.DiagnosticsLineTone
import com.vladimir.messenger.data.diagnostics.diagnosticsLineTone

// ─────────────────────────────────────────────────────────────────────────────
// Палитра окна «Логи»
// ─────────────────────────────────────────────────────────────────────────────

/** Полированное золото: блик → металл → глубина. */
private val DiagGoldLight = Color(0xFFFFE9A8)
private val DiagGold = Color(0xFFF2B836)
private val DiagGoldDeep = Color(0xFFB87E08)

/** Тёмная основа шапки и консоли: полуночный синий. */
private val DiagNightTop = Color(0xFF16233A)
private val DiagNightBottom = Color(0xFF0A1018)
private val DiagConsoleSurface = Color(0xFF0D1520)
private val DiagConsoleBar = Color(0xFF182636)

/** Чернила на золоте и светлом: контраст ≥ 4.5 на всех стопах градиента. */
private val DiagOnGold = Color(0xFF241703)

/** Текст консоли: светлый и спокойный, ошибки и предупреждения — ярче. */
private val DiagConsoleText = Color(0xFFE6EEF8)
private val DiagConsoleMuted = Color(0xFF9FB3CB)
private val DiagConsoleTitle = Color(0xFFFFC24B)
private val DiagConsoleWarn = Color(0xFFFFB74D)
private val DiagConsoleBad = Color(0xFFFF8A80)

/**
 * Насыщенный цвет уровня: заливки, полосы, точки и градиенты. Именно он даёт
 * «яркие и чёткие» цвета; текстом он не используется — для текста есть
 * [diagnosticsLevelInkColor].
 */
fun diagnosticsLevelColor(level: DiagnosticsLevel): Color = when (level) {
    DiagnosticsLevel.OK -> Color(0xFF10B981)
    DiagnosticsLevel.INFO -> Color(0xFFF0A81E)
    DiagnosticsLevel.WARN -> Color(0xFFF97316)
    DiagnosticsLevel.BAD -> Color(0xFFEF4444)
}

/** Тёмные чернила уровня: ими подписаны плашки и легенда (контраст ≥ 4.5). */
fun diagnosticsLevelInkColor(level: DiagnosticsLevel): Color = when (level) {
    DiagnosticsLevel.OK -> Color(0xFF047857)
    DiagnosticsLevel.INFO -> Color(0xFF8A5A00)
    DiagnosticsLevel.WARN -> Color(0xFF9A3412)
    // Самая громкая краска дома: тот же «опасный» тон, что у остальных
    // предупреждений приложения, только применённый к тексту уровня.
    DiagnosticsLevel.BAD -> ApuSettingsDangerColor
}

/** Пара «заливка → глубина» для градиентов уровня: объём без грязи. */
private fun diagnosticsLevelGradient(level: DiagnosticsLevel): List<Color> = when (level) {
    DiagnosticsLevel.OK -> listOf(Color(0xFF34D399), Color(0xFF059669))
    DiagnosticsLevel.INFO -> listOf(Color(0xFFFFD257), Color(0xFFD99B1C))
    DiagnosticsLevel.WARN -> listOf(Color(0xFFFBBF24), Color(0xFFEA580C))
    DiagnosticsLevel.BAD -> listOf(Color(0xFFFB7185), Color(0xFFDC2626))
}

/** Слова для легенды: у каждого цвета на сводке есть понятное название. */
private fun diagnosticsLevelLabel(level: DiagnosticsLevel): String = when (level) {
    DiagnosticsLevel.OK -> "порядок"
    DiagnosticsLevel.INFO -> "справка"
    DiagnosticsLevel.WARN -> "внимание"
    DiagnosticsLevel.BAD -> "поломка"
}

/** Общий уровень по строкам: сначала поломки, потом внимание, иначе порядок. */
private fun diagnosticsWorstLevel(lines: List<DiagnosticsLine>): DiagnosticsLevel = when {
    lines.any { it.level == DiagnosticsLevel.BAD } -> DiagnosticsLevel.BAD
    lines.any { it.level == DiagnosticsLevel.WARN } -> DiagnosticsLevel.WARN
    else -> DiagnosticsLevel.OK
}

private fun diagnosticsVerdictText(level: DiagnosticsLevel): String = when (level) {
    DiagnosticsLevel.BAD -> "ЕСТЬ ОШИБКИ"
    DiagnosticsLevel.WARN -> "ЕСТЬ ПРЕДУПРЕЖДЕНИЯ"
    DiagnosticsLevel.INFO -> "ВСЁ В ПОРЯДКЕ"
    DiagnosticsLevel.OK -> "ВСЁ В ПОРЯДКЕ"
}

// ─────────────────────────────────────────────────────────────────────────────
// Глянец и объём: одни и те же приёмы во всех элементах окна
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Верхний блик: узкая светлая полоса у верхней кромки плюс мягкое свечение
 * сверху. Именно это читается как «полированный пластик» у современных
 * мессенджеров, и стоит ноль лишних перерисовок (drawWithCache).
 */
private fun Modifier.diagnosticsGloss(
    shape: Shape = RoundedCornerShape(16.dp),
    intensity: Float = 1f,
    /** 0..1 — где стоит «бегущая» световая полоса; null — блик на месте. */
    sweep: Float? = null,
): Modifier = clip(shape).drawWithCache {
    // Верхнее свечение: узкая светлая полоса у кромки — «полированный
    // пластик» премиальных приложений. Стоит ноль перерисовок (drawWithCache).
    val topSheen = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.34f * intensity),
            Color.White.copy(alpha = 0.10f * intensity),
            Color.Transparent,
        ),
        start = Offset(0f, 0f),
        end = Offset(0f, size.height * 0.55f),
    )
    val staticBand = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.42f * intensity),
            Color.White.copy(alpha = 0.06f * intensity),
            Color.Transparent,
        ),
        start = Offset(0f, 0f),
        end = Offset(size.width * 0.85f, 0f),
    )
    // Бегущая полоса мягче статичной: она проходит поверх содержимого, и
    // засвечивать текст ей нельзя — только «скользнуть» по стеклу.
    val movingBandWidth = size.width * 0.30f
    val movingBandLeft = -movingBandWidth + (size.width + movingBandWidth) * (sweep ?: 0f)
    val movingBand = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Color.White.copy(alpha = 0.20f * intensity),
            Color.Transparent,
        ),
        startX = movingBandLeft,
        endX = movingBandLeft + movingBandWidth,
    )
    // Блик ложится ПОД содержимым: значки и текст остаются чёткими, а свет
    // будто скользит по стеклу изнутри.
    onDrawWithContent {
        drawRect(topSheen)
        if (sweep == null) {
            drawRect(
                brush = staticBand,
                topLeft = Offset(size.width * 0.04f, size.height * 0.06f),
                size = Size(size.width * 0.92f, size.height * 0.10f),
            )
        } else {
            // Верхние три четверти высоты: низ карточки остаётся спокойным.
            val height = size.height * 0.75f
            clipRect(
                left = movingBandLeft.coerceIn(0f, size.width),
                top = 0f,
                right = (movingBandLeft + movingBandWidth).coerceIn(0f, size.width),
                bottom = height,
            ) {
                drawRect(
                    brush = movingBand,
                    topLeft = Offset(movingBandLeft, 0f),
                    size = Size(movingBandWidth, height),
                )
            }
        }
        drawContent()
    }
}

/** Мягкая тень под элементом: объём, а не плоская заливка. */
private fun Modifier.diagnosticsLift(elevation: Dp, shape: Shape, color: Color): Modifier =
    shadow(elevation = elevation, shape = shape, clip = false, ambientColor = color, spotColor = color)

// ─────────────────────────────────────────────────────────────────────────────
// Шапка окна: баннер со статусом и чипами
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Верх окна «Логи»: тёмный градиентный баннер с эмблемой, названием, статусом
 * и стеклянными чипами (проверок / записей / тревог). Это первое, что видит
 * человек, — и по нему сразу понятно, всё ли хорошо.
 */
@Composable
fun ApuDiagnosticsHero(
    lines: List<DiagnosticsLine>,
    modifier: Modifier = Modifier,
    appVersion: String? = null,
    collectedAt: String? = null,
    journalSize: Int = 0,
    warnCount: Int = 0,
    badCount: Int = 0,
) {
    // Пока проверок нет, «ВСЁ В ПОРЯДКЕ» было бы враньём: показываем сбор.
    val collecting = lines.isEmpty()
    val level = if (collecting) DiagnosticsLevel.INFO else diagnosticsWorstLevel(lines)
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .diagnosticsLift(16.dp, shape, Color(0xFF0A1018).copy(alpha = 0.6f))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(DiagNightTop, Color(0xFF2A3C5A), DiagNightBottom),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            )
            // «Аврора»: золотое свечение в углу. Далёкий отблеск металла —
            // то, что отличает премиальную вещь от плоской карточки.
            .drawWithCache {
                val aurora = Brush.radialGradient(
                    colors = listOf(
                        DiagGold.copy(alpha = 0.30f),
                        DiagGold.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.86f, -size.height * 0.08f),
                    radius = size.width * 0.75f,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(aurora)
                }
            }
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        DiagGold.copy(alpha = 0.85f),
                        DiagGoldLight.copy(alpha = 0.45f),
                        DiagGoldDeep.copy(alpha = 0.35f),
                    ),
                ),
                shape,
            )
            .diagnosticsGloss(shape, intensity = 0.78f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ApuDiagnosticsEmblem()
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.diag_state_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                    val subtitle = listOfNotNull(
                        collectedAt?.takeIf { it.isNotBlank() },
                        appVersion?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    Text(
                        subtitle.ifBlank { stringResource(R.string.diag_report_desc) },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ApuDiagnosticsVerdictPill(
                text = if (collecting) stringResource(R.string.diag_collecting) else diagnosticsVerdictText(level),
                level = level,
                checks = lines.size,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ApuDiagnosticsGlassChip("событий", journalSize.toString(), modifier = Modifier.weight(1f))
                ApuDiagnosticsGlassChip("предупр.", warnCount.toString(), modifier = Modifier.weight(1f))
                ApuDiagnosticsGlassChip(
                    "ошибок",
                    badCount.toString(),
                    alarm = badCount > 0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Эмблема: золотое кольцо с тёмным стеклом внутри — знак APU. Статический
 * sweep-градиент даёт металлический блик без постоянного вращения и мигания.
 */
@Composable
private fun ApuDiagnosticsEmblem() {
    Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .diagnosticsLift(10.dp, CircleShape, DiagGold.copy(alpha = 0.42f))
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            DiagGoldDeep,
                            DiagGold,
                            DiagGoldLight,
                            DiagGold,
                            DiagGoldDeep,
                            DiagGold,
                            DiagGoldLight,
                            DiagGoldDeep,
                        ),
                    ),
                )
                .diagnosticsGloss(CircleShape, intensity = 0.82f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF22334C), Color(0xFF0B1220))))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = DiagGold,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Статус-плашка: яркий градиент по уровню, тёмные чернила, глянец. */
@Composable
private fun ApuDiagnosticsVerdictPill(text: String, level: DiagnosticsLevel, checks: Int) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .diagnosticsLift(12.dp, shape, diagnosticsLevelColor(level).copy(alpha = 0.55f))
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        diagnosticsLevelGradient(level),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.75f),
                            Color.White.copy(alpha = 0.10f),
                        ),
                    ),
                    shape,
                )
                .diagnosticsGloss(shape, intensity = 0.88f)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.92f)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = DiagOnGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (checks > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.diag_checks_count, checks),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
            )
        }
    }
}

/** Стеклянный чип на тёмной шапке: подпись сверху, число крупно. */
@Composable
private fun ApuDiagnosticsGlassChip(
    label: String,
    value: String,
    alarm: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = if (alarm) diagnosticsLevelColor(DiagnosticsLevel.BAD) else DiagGold
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.20f), shape)
            .diagnosticsGloss(shape, intensity = 0.45f)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.66f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = accent,
            maxLines = 1,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Сводка: строки проверок и легенда
// ─────────────────────────────────────────────────────────────────────────────

/** Сводка «что происходит сейчас»: строки проверок с яркими метками и легенда. */
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lines.isEmpty()) {
                Text(
                    stringResource(R.string.diag_checks_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            } else {
                lines.forEach { line -> ApuDiagnosticsStatusRow(line) }
                ApuDiagnosticsDivider()
                ApuDiagnosticsLegend()
            }
        }
    }
}

/** Одна строка сводки: яркая пиктограмма-«well», название и состояние словами. */
@Composable
fun ApuDiagnosticsStatusRow(line: DiagnosticsLine, modifier: Modifier = Modifier) {
    val bright = diagnosticsLevelColor(line.level)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(bright.copy(alpha = 0.16f), bright.copy(alpha = 0.04f)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, 0f),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(bright.copy(alpha = 0.55f), bright.copy(alpha = 0.18f)),
                ),
                shape,
            )
            .diagnosticsGloss(shape, intensity = 0.35f)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ApuDiagnosticsLevelWell(line.level)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                line.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
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

/** Метка уровня: объёмный шар с бликом — читается и в 9dp, и в 40dp. */
@Composable
private fun ApuDiagnosticsLevelWell(level: DiagnosticsLevel) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    diagnosticsLevelGradient(level),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            )
            .diagnosticsGloss(shape, intensity = 0.75f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.94f)),
        )
    }
}

/** Легенда: четыре яркие пилюли теми же красками, что и метки в сводке. */
@Composable
private fun ApuDiagnosticsLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ApuDiagnosticsLegendItem(DiagnosticsLevel.OK, Modifier.weight(1f))
            ApuDiagnosticsLegendItem(DiagnosticsLevel.INFO, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ApuDiagnosticsLegendItem(DiagnosticsLevel.WARN, Modifier.weight(1f))
            ApuDiagnosticsLegendItem(DiagnosticsLevel.BAD, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ApuDiagnosticsLegendItem(level: DiagnosticsLevel, modifier: Modifier = Modifier) {
    val bright = diagnosticsLevelColor(level)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(bright.copy(alpha = 0.14f))
            .border(1.dp, bright.copy(alpha = 0.30f), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        diagnosticsLevelGradient(level),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
        )
        Text(
            diagnosticsLevelLabel(level),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = diagnosticsLevelInkColor(level),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Разделитель: золотая нить, растворяющаяся к краю. */
@Composable
private fun ApuDiagnosticsDivider() {
    // Спокойная золотая нить: подвижный блик оставлен только для нажатий.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        DiagGold.copy(alpha = 0.45f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Отчёт: тёмная консоль с подсветкой
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Отчёт: шапка-консоль («светофор», название, число строк), необязательная
 * подпись (когда и что собрано) и сам текст — моноширинный, с выделением и
 * своей прокруткой. Светлые чернила на тёмном фоне дают «яркие и чёткие»
 * цвета, а текст при копировании уходит разработчику символ в символ.
 */
@Composable
fun ApuDiagnosticsReportCard(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
    caption: String? = null,
) {
    val scroll = rememberScrollState()
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .diagnosticsLift(14.dp, shape, Color(0xFF0A1018).copy(alpha = 0.5f))
            .clip(shape)
            .background(DiagConsoleSurface)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        DiagGold.copy(alpha = 0.65f),
                        DiagGoldLight.copy(alpha = 0.30f),
                        DiagGoldDeep.copy(alpha = 0.25f),
                    ),
                ),
                shape,
            ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(DiagGold.copy(alpha = 0.12f), DiagConsoleBar, DiagConsoleSurface),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ApuDiagnosticsTrafficLights()
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.diag_full_report),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.diag_lines_count, lineCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = DiagConsoleMuted,
                    maxLines = 1,
                )
            }
            caption?.takeIf { it.isNotBlank() }?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = DiagConsoleMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .background(DiagConsoleSurface)
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        diagnosticsReportAnnotated(text),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = DiagConsoleText,
                    )
                }
            }
        }
    }
}

/**
 * Журнал событий списком — то, что человек и называет «логи» (владелец
 * 2026-10-07: «нет логов списка вообще»). Записи приходят уже очищенными
 * (см. TransferDiagnostics.append), показаны свежие сверху: время, область,
 * уровень и суть. Свой список прокрутки — окно при этом остаётся целым.
 */
@Composable
fun ApuDiagnosticsEventList(
    events: List<DiagnosticsJournalEntry>,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 210.dp,
) {
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .diagnosticsLift(12.dp, shape, Color(0xFF0A1018).copy(alpha = 0.42f))
            .clip(shape)
            .background(DiagConsoleSurface)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        DiagGold.copy(alpha = 0.55f),
                        DiagGoldLight.copy(alpha = 0.25f),
                        DiagGoldDeep.copy(alpha = 0.22f),
                    ),
                ),
                shape,
            ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(DiagGold.copy(alpha = 0.12f), DiagConsoleBar, DiagConsoleSurface),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(DiagGold)
                        .diagnosticsGloss(CircleShape, intensity = 0.8f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.diag_event_log),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (events.isEmpty()) stringResource(R.string.diag_none_yet) else stringResource(R.string.diag_newest_first),
                    style = MaterialTheme.typography.labelMedium,
                    color = DiagConsoleMuted,
                    maxLines = 1,
                )
            }
            if (events.isEmpty()) {
                Text(
                    stringResource(R.string.diag_no_events),
                    style = MaterialTheme.typography.bodySmall,
                    color = DiagConsoleMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .verticalScroll(scroll),
                ) {
                    events.forEachIndexed { index, event ->
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .height(1.dp)
                                    .background(DiagConsoleBar),
                            )
                        }
                        ApuDiagnosticsEventRow(event)
                    }
                }
            }
        }
    }
}

/** Одна запись журнала: метка уровня, время, область и суть. */
@Composable
private fun ApuDiagnosticsEventRow(event: DiagnosticsJournalEntry) {
    val levelColor = diagnosticsLevelColor(event.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(levelColor),
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DiagnosticsReport.clock(event.atMs),
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = DiagConsoleMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    event.area,
                    style = MaterialTheme.typography.labelSmall,
                    color = DiagConsoleTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    diagnosticsLevelLabel(event.level),
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    maxLines = 1,
                )
            }
            Text(
                event.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (event.level == DiagnosticsLevel.BAD) DiagConsoleBad else DiagConsoleText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** «Светофор» консоли: три цвета уровней — узнаваемый премиальный штрих. */
@Composable
private fun ApuDiagnosticsTrafficLights() {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(DiagnosticsLevel.OK, DiagnosticsLevel.WARN, DiagnosticsLevel.BAD).forEach { level ->
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(diagnosticsLevelGradient(level)[0]),
            )
        }
    }
}

/**
 * Раскрашивает отчёт, не меняя ни одного символа: разделы — золотом консоли,
 * ошибки — красным, предупреждения — оранжевым. Решение о тоне строки
 * принимает чистая [diagnosticsLineTone] (её проверяют JVM-тесты), здесь только
 * краски. Текст остаётся тем же, поэтому копируется и уезжает разработчику
 * ровно так, как выглядит.
 */
fun diagnosticsReportAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val style = when (diagnosticsLineTone(line)) {
            DiagnosticsLineTone.SECTION ->
                SpanStyle(color = DiagConsoleTitle, fontWeight = FontWeight.Bold)
            DiagnosticsLineTone.BAD -> SpanStyle(color = DiagConsoleBad)
            DiagnosticsLineTone.WARN -> SpanStyle(color = DiagConsoleWarn)
            null -> null
        }
        if (style == null) append(line) else withStyle(style) { append(line) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Кнопки: объёмные, с блеском и откликом
// ─────────────────────────────────────────────────────────────────────────────

/** Назначение кнопки: золотая основная, стеклянная вторичная, тихая третья, опасная красная. */
enum class DiagnosticsActionStyle { PRIMARY, GLASS, QUIET, DANGER }

/**
 * Общая объёмная кнопка APU. Стиль один для окна «Логи» и остальных разделов;
 * compact раскладывает иконку над подписью — так длинные действия не обрезаются
 * в узких колонках. content нужен для редких действий со сложным содержимым.
 */
@Composable
fun ApuDiagnosticsActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: DiagnosticsActionStyle = DiagnosticsActionStyle.GLASS,
    enabled: Boolean = true,
    compact: Boolean = false,
    progress: Boolean = false,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val emphatic = style == DiagnosticsActionStyle.PRIMARY || style == DiagnosticsActionStyle.DANGER
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "apu-button-press",
    )
    val baseLift = when (style) {
        DiagnosticsActionStyle.PRIMARY -> 13.dp
        DiagnosticsActionStyle.DANGER -> 11.dp
        else -> 6.dp
    }
    val lift by animateDpAsState(
        targetValue = if (pressed && enabled) baseLift - 5.dp else baseLift,
        animationSpec = tween(durationMillis = 110),
        label = "apu-button-lift",
    )
    val shape = RoundedCornerShape(if (emphatic) 16.dp else 14.dp)
    val fill = when (style) {
        DiagnosticsActionStyle.PRIMARY -> Brush.linearGradient(
            colors = listOf(DiagGoldLight, DiagGold, DiagGoldDeep),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
        DiagnosticsActionStyle.GLASS -> Brush.linearGradient(
            colors = listOf(Color.White, Color(0xFFF1F3F7)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
        DiagnosticsActionStyle.QUIET -> Brush.linearGradient(
            colors = listOf(Color(0xFFF7F8FA), Color(0xFFECEEF2)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
        DiagnosticsActionStyle.DANGER -> Brush.linearGradient(
            colors = listOf(Color(0xFFFFEEF0), Color(0xFFF8D8DC), Color(0xFFEAB5BD)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    }
    val ink = when (style) {
        DiagnosticsActionStyle.PRIMARY -> DiagOnGold
        DiagnosticsActionStyle.GLASS -> ApuBubbleAccentColor
        DiagnosticsActionStyle.QUIET -> ApuBubbleMutedColor
        DiagnosticsActionStyle.DANGER -> Color(0xFF721D2B)
    }
    val glow = when (style) {
        DiagnosticsActionStyle.PRIMARY -> DiagGold.copy(alpha = 0.55f)
        DiagnosticsActionStyle.DANGER -> Color(0xFFA12D3A).copy(alpha = 0.40f)
        else -> Color(0xFF0A1018).copy(alpha = 0.28f)
    }
    val surface = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .diagnosticsLift(lift, shape, glow)
        .clip(shape)
        .background(fill)
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    if (style == DiagnosticsActionStyle.PRIMARY) Color.White.copy(alpha = 0.85f)
                    else if (style == DiagnosticsActionStyle.DANGER) Color.White.copy(alpha = 0.78f)
                    else DiagGold.copy(alpha = 0.55f),
                    if (style == DiagnosticsActionStyle.DANGER) Color(0xFFA12D3A).copy(alpha = 0.42f)
                    else DiagGoldDeep.copy(alpha = 0.35f),
                ),
            ),
            shape = shape,
        )
        .diagnosticsGloss(shape, intensity = if (emphatic) 1f else 0.7f)
        .clickableDiag(interaction, enabled, onClick)

    CompositionLocalProvider(LocalContentColor provides ink.copy(alpha = if (enabled) 1f else 0.48f)) {
        if (compact && content == null) {
            Column(
                modifier = surface
                    .heightIn(min = 68.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (progress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = ink,
                        strokeWidth = 2.dp,
                    )
                } else {
                    icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ink.copy(alpha = if (enabled) 1f else 0.48f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                modifier = surface
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp, vertical = if (emphatic) 11.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (content == null) {
                    Arrangement.spacedBy(8.dp)
                } else {
                    Arrangement.Center
                },
            ) {
                if (content != null) {
                    content()
                } else {
                    if (progress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            color = ink,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ink.copy(alpha = if (enabled) 1f else 0.48f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Нажатие без «ряби» Material: кнопка отвечает сжатием и тенью. */
private fun Modifier.clickableDiag(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interaction,
    indication = null,
    enabled = enabled,
    role = Role.Button,
    onClick = onClick,
)

/** Иконки для кнопок окна: одни на все места, чтобы стиль не разъезжался. */
object DiagnosticsActionIcons {
    val Send: ImageVector get() = Icons.Default.Send
    val Copy: ImageVector get() = Icons.Default.ContentCopy
    val Refresh: ImageVector get() = Icons.Default.Refresh
    val Shield: ImageVector get() = Icons.Default.Shield
}

// ─────────────────────────────────────────────────────────────────────────────
// Мелкие общие детали
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Полоса приватности: тёмное стекло с золотой кромкой и щитом. Тем же
 * обещанием, что и раньше, но так, чтобы его читали, а не пролистывали.
 */
@Composable
fun ApuDiagnosticsPrivacyStrip(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF17253A), Color(0xFF0E1725))))
            .border(1.dp, DiagGold.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = DiagGold,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.82f),
        )
    }
}
