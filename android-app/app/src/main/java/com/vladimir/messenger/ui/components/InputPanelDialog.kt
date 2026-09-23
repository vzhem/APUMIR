package com.vladimir.messenger.ui.components

// =============================================================================
// INPUTPANEDIALOG.KT - единая панель ввода (раунд 138)
// =============================================================================
// Просьба владельца: при нажатии на кнопку появляется ОБЛАКО с тремя
// разделами в одном пузыре - «Эмодзи», «Гиф», «Стикеры»; а когда листаешь
// содержимое вниз, сверху горизонтально листаются пузыри РАЗДЕЛОВ (у каждого
// своя иконка), и активный раздел подсвечивается - как в привычных
// мессенджерах. Нажатие на пузырь прокручивает содержимое к этому разделу.
//
// Разделы:
//  - Эмодзи - категории из EmojiCatalog, выбор вставляет эмодзи в поле ввода;
//  - Гиф - тот же единый каталог сети (GifCatalogBody, раунд 127);
//  - Стикеры - «Недавние» и «Мои» (StickerLibrary), выбор отправляет стикер
//    в чат картинкой; «+» добавляет свой из хранилища телефона.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridEntries
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.vladimir.messenger.data.gif.GifItem
import com.vladimir.messenger.data.gif.GifLibEntry
import com.vladimir.messenger.data.gif.SwarmGif
import com.vladimir.messenger.data.sticker.StickerLibrary
import com.vladimir.messenger.ui.components.EmojiCatalog
import kotlinx.coroutines.launch

/** Разделы главного ряда панели. */
private const val SECTION_EMOJI = "emoji"
private const val SECTION_GIF = "gif"
private const val SECTION_STICKERS = "stickers"

/**
 * Единая панель ввода: пузырь в стиле APU (золотая рамка, наша подложка)
 * с тремя главными пузырями-разделами и горизонтальной лентой пузырей
 * подразделов, следящей за прокруткой.
 *
 * @param onOpened панель открылась - экран может перечитать списки.
 */
@Composable
fun InputPanelDialog(
    // ── Гиф: те же параметры, что у каталога (GifCatalogBody) ──
    myGifs: List<GifLibEntry>,
    swarmGifs: List<SwarmGif>,
    swarmStatus: String?,
    items: List<GifItem>,
    next: String,
    loading: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onMore: () -> Unit,
    onAttach: (GifItem) -> Unit,
    onAttachLocal: (GifLibEntry) -> Unit,
    onRequestSwarm: (SwarmGif) -> Unit,
    onRequestThumbs: (List<SwarmGif>) -> Unit = {},
    onAddOwnGif: (android.net.Uri) -> Unit = {},
    // ── Стикеры ──
    stickers: List<StickerLibrary.StickerEntry>,
    stickerRecents: List<StickerLibrary.StickerEntry>,
    swarmStickers: List<com.vladimir.messenger.data.sticker.SwarmSticker> = emptyList(),
    onSticker: (StickerLibrary.StickerEntry) -> Unit,
    onAddSticker: (android.net.Uri) -> Unit,
    /** Выбрали стикер из сети - скачать тихо у хранителей и отправить. */
    onRequestSwarmSticker: (com.vladimir.messenger.data.sticker.SwarmSticker) -> Unit = {},
    // ── Эмодзи ──
    onEmoji: (String) -> Unit,
    onOpened: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpened() }
    var section by remember { mutableStateOf(SECTION_GIF) }
    val gold = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(
                    width = 2.dp,
                    brush = Brush.verticalGradient(listOf(gold, gold.copy(alpha = 0.35f))),
                    shape = shape,
                )
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Наша подложка внутри пузыря + лёгкая вуаль для читаемости.
            Box(modifier = Modifier.matchParentSize()) {
                ChatWallpaper()
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── Главный ряд: три пузыря разделов ──
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionPill(
                        icon = "😀",
                        label = "Эмодзи",
                        selected = section == SECTION_EMOJI,
                        onClick = { section = SECTION_EMOJI },
                        modifier = Modifier.weight(1f),
                    )
                    SectionPill(
                        icon = "🎞",
                        label = "Гиф",
                        selected = section == SECTION_GIF,
                        onClick = { section = SECTION_GIF },
                        modifier = Modifier.weight(1f),
                    )
                    SectionPill(
                        icon = "🧩",
                        label = "Стикеры",
                        selected = section == SECTION_STICKERS,
                        onClick = { section = SECTION_STICKERS },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (section == SECTION_EMOJI) {
                    EmojiSection(onEmoji = onEmoji)
                } else if (section == SECTION_GIF) {
                    GifCatalogBody(
                        myGifs = myGifs,
                        swarmGifs = swarmGifs,
                        swarmStatus = swarmStatus,
                        items = items,
                        next = next,
                        loading = loading,
                        error = error,
                        onSearch = onSearch,
                        onMore = onMore,
                        onAttach = onAttach,
                        onAttachLocal = onAttachLocal,
                        onRequestSwarm = onRequestSwarm,
                        onRequestThumbs = onRequestThumbs,
                        onAddOwnGif = onAddOwnGif,
                    )
                } else {
                    StickerSection(
                        stickers = stickers,
                        recents = stickerRecents,
                        swarm = swarmStickers,
                        swarmStatus = swarmStatus,
                        onSticker = onSticker,
                        onAddSticker = onAddSticker,
                        onRequestSwarmSticker = onRequestSwarmSticker,
                    )
                }
            }
        }
    }
}

/** Пузырь раздела в главном ряду: иконка + подпись, выбранный подсвечен. */
@Composable
private fun SectionPill(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(
                1.dp,
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                },
                RoundedCornerShape(16.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Раздел «Эмодзи»: лента пузырей категорий сверху (следит за прокруткой,
 * нажатие прокручивает к категории) и сетка с заголовками категорий.
 */
@Composable
private fun EmojiSection(onEmoji: (String) -> Unit) {
    val categories = EmojiCatalog.categories
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Индекс первой ячейки каждой категории в сетке (заголовок + эмодзи).
    val starts = remember(categories) {
        var index = 0
        categories.map { category ->
            val start = index
            index += 1 + category.emojis.size
            start
        }
    }
    // Активная категория - по первой видимой ячейке сетки (раунд 138:
    // листаешь вниз - пузырь сверху перескакивает сам).
    val current by remember {
        derivedStateOf {
            var active = 0
            for (k in starts.indices) {
                if (gridState.firstVisibleItemIndex >= starts[k]) active = k
            }
            active
        }
    }
    SubPillRow(
        pills = categories.map { SubPill(icon = it.icon, label = it.name) },
        current = current,
        onSelect = { k -> scope.launch { gridState.scrollToItem(starts[k]) } },
    )
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(8),
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        categories.forEachIndexed { k, category ->
            item(key = "h$k", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    category.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(
                count = category.emojis.size,
                key = { i -> "e$k-$i" },
            ) { i ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEmoji(category.emojis[i]) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        category.emojis[i],
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Раздел «Стикеры»: пузыри паков («Недавние», «Мои») сверху - следят за
 * прокруткой; в сетке заголовок пака, его стикеры и ячейка «+» для
 * добавления своего из хранилища.
 */
@Composable
private fun StickerSection(
    stickers: List<StickerLibrary.StickerEntry>,
    recents: List<StickerLibrary.StickerEntry>,
    swarm: List<com.vladimir.messenger.data.sticker.SwarmSticker>,
    swarmStatus: String?,
    onSticker: (StickerLibrary.StickerEntry) -> Unit,
    onAddSticker: (android.net.Uri) -> Unit,
    onRequestSwarmSticker: (com.vladimir.messenger.data.sticker.SwarmSticker) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val panelContext = androidx.compose.ui.platform.LocalContext.current
    // Миниатюры приезжают тихо - тик заставляет сетку перечитать кэш.
    val thumbTick by remember {
        StickerLibrary.thumbArrivalsFlow()
    }.collectAsState("")
    // Три пака: Недавние, Мои, Из сети. Индексы первых ячеек - по числу.
    val recentsStart = 0
    val myStart = 1 + recents.size + 1 // заголовок + стикеры + заголовок «Мои»
    val swarmStart = myStart + stickers.size + 1 + 1 // «+» строка + заголовок «Из сети»
    val groups = listOf(
        Triple("🕘", "Недавние", recentsStart),
        Triple("📦", "Мои", myStart),
        Triple("🕸", "Из сети", swarmStart),
    )
    val current by remember {
        derivedStateOf {
            when {
                gridState.firstVisibleItemIndex >= swarmStart -> 2
                gridState.firstVisibleItemIndex >= myStart -> 1
                else -> 0
            }
        }
    }
    SubPillRow(
        pills = groups.map { SubPill(icon = it.first, label = it.second) },
        current = current,
        onSelect = { k -> scope.launch { gridState.scrollToItem(groups[k].third) } },
    )
    val pickSticker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) onAddSticker(uri) }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(5),
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item(key = "hr", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Недавние",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        if (recents.isEmpty()) {
            item(key = "hr-empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Пока пусто - отправленные стикеры появятся здесь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        rowStickers(recents, "r", onSticker)
        item(key = "hm", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Мои стикеры",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
            )
        }
        rowStickers(stickers, "m", onSticker)
        item(key = "add", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { pickSticker.launch("image/*") }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("+", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Добавить стикер из телефона (картинка)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item(key = "hs", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Из сети",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
            )
        }
        if (!swarmStatus.isNullOrEmpty()) {
            item(key = "hs-status", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    swarmStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }
        if (swarm.isEmpty()) {
            item(key = "hs-empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Пока пусто. Добавьте стикер на любом телефоне - он появится здесь на всех и будет храниться в сети.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        // Стикеры из роя: миниатюра с хранителя или заглушка; нажатие -
        // тихо скачать у трёх хранителей и отправить в чат.
        gridEntries(
            swarm,
            key = { "sw-$thumbTick-${it.sha256}" },
        ) { item ->
            val thumb = remember(item.sha256, thumbTick) {
                StickerLibrary.tinyThumbFile(panelContext, item.sha256)
            }
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .clickable { onRequestSwarmSticker(item) },
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(4.dp),
                    )
                } else {
                    Text(
                        "🧩",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Стикеры одного пака в сетке. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.rowStickers(
    entries: List<StickerLibrary.StickerEntry>,
    prefix: String,
    onSticker: (StickerLibrary.StickerEntry) -> Unit,
) {
    gridEntries(
        entries,
        key = { "$prefix-${it.sha256}" },
    ) { entry ->
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .clickable { onSticker(entry) },
        ) {
            AsyncImage(
                model = entry.file,
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(4.dp),
            )
        }
    }
}

/** Пузырь подраздела в горизонтальной ленте. */
private data class SubPill(val icon: String, val label: String)

/** Горизонтальная лента пузырей подразделов: активный подсвечен. */
@Composable
private fun SubPillRow(
    pills: List<SubPill>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        rowItems(pills) { pill ->
            val index = pills.indexOf(pill)
            val selected = index == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                    .border(
                        1.dp,
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        },
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(pill.icon, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    pill.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
