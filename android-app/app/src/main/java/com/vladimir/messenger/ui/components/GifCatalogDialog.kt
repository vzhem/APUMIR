package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vladimir.messenger.data.gif.GifItem
import com.vladimir.messenger.data.gif.GifLibEntry
import com.vladimir.messenger.data.gif.SwarmGif

/**
 * ЕДИНЫЙ каталог гифок (раунд 127, решение владельца): внешний каталог и
 * гифки нашей сети - ОДНА сетка. Выбираешь любую гифку, а приложение само
 * определяет, есть ли она уже в нашей сети:
 * - если есть - в правом верхнем углу горит переливающаяся СИНЯЯ ТОЧКА,
 *   и отправка идёт с телефонов сети (внешний ресурс не тратится);
 * - если нет - гифка скачивается из внешнего каталога и оседает в сети.
 * Под сеткой - пометка, что означает точка.
 */
@Composable
fun GifCatalogDialog(
    /** Каталог теперь единый: вкладок больше нет (параметр оставлен для совместимости). */
    tab: String,
    onTab: (String) -> Unit,
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
    /** Раунд 124: добавить СВОЮ гифку из хранилища телефона. */
    onAddOwnGif: (android.net.Uri) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Гифки") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Поиск: котики, привет…") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { onSearch(query) }) {
                                Icon(Icons.Filled.Search, contentDescription = "Найти")
                            }
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    val pickOwnGif = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    ) { uri -> if (uri != null) onAddOwnGif(uri) }
                    TextButton(onClick = { pickOwnGif.launch("image/gif") }) {
                        Text("+ Своя", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))

                val q = query.trim().lowercase()
                // Сеть уже покрывает эти внешние гифки (по метке внешнего каталога).
                val netGiphyIds = HashSet<String>()
                for (entry in myGifs) if (entry.giphyId.isNotBlank()) netGiphyIds.add(entry.giphyId)
                for (sg in swarmGifs) if (sg.entry.giphyId.isNotBlank()) netGiphyIds.add(sg.entry.giphyId)
                val externalIds = items.mapTo(HashSet()) { it.id }
                val myCells = if (q.isBlank()) {
                    myGifs
                } else {
                    myGifs.filter { it.tag.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
                }
                val peerCells = swarmGifs.filter { sg ->
                    val gid = sg.entry.giphyId
                    // Без дублей: тот же giphyId уже показан моими ячейками или
                    // внешними результатами - там и будет синяя точка.
                    if (gid.isNotBlank() && (gid in externalIds || myGifs.any { it.giphyId == gid })) {
                        return@filter false
                    }
                    q.isBlank() || sg.entry.tag.lowercase().contains(q) || sg.entry.displayName.lowercase().contains(q)
                }

                val nothingAtAll = myCells.isEmpty() && peerCells.isEmpty() && items.isEmpty() && error == null
                if (nothingAtAll) {
                    Text(
                        "Пока пусто. Нажмите поиск - гифки из внешнего каталога; " +
                            "скачанные оседают в нашей сети. Свою гифку добавьте кнопкой «+ Своя». " +
                            "Каталоги телефонов обмениваются автоматически: чем дольше пользуетесь, " +
                            "тем больше набор без внешнего ресурса.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp, horizontal = 8.dp),
                    )
                } else if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // ── Мои гифки: превью с телефона, уходят мгновенно ──
                        gridItems(myCells, key = { "m-${it.sha256}" }) { entry ->
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val preview = remember(entry.sha256) {
                                com.vladimir.messenger.data.gif.GifLibrary
                                    .previewFile(context, entry.sha256)?.absolutePath
                            }
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onAttachLocal(entry) },
                            ) {
                                if (preview != null) {
                                    AsyncImage(
                                        model = java.io.File(preview),
                                        contentDescription = entry.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                    )
                                } else {
                                    PlaceholderCell(label = entry.displayName)
                                }
                                Badge("мой", Modifier.align(Alignment.TopStart))
                                NetDot(Modifier.align(Alignment.TopEnd))
                            }
                        }
                        // ── Гифки сети (у других телефонов): просьба хранителю ──
                        gridItems(peerCells, key = { "s-${it.entry.sha256}" }) { swarmGif ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onRequestSwarm(swarmGif) },
                            ) {
                                PlaceholderCell(
                                    label = swarmGif.entry.tag.ifBlank { "гифка" },
                                )
                                Badge("у ${swarmGif.holders.size}", Modifier.align(Alignment.TopStart))
                                NetDot(Modifier.align(Alignment.TopEnd))
                            }
                        }
                        // ── Внешний каталог: точка = уже в нашей сети ──
                        gridItems(items, key = { "e-${it.id}" }) { item ->
                            val myEntry = myGifs.firstOrNull { it.giphyId == item.id }
                            val peer = swarmGifs.firstOrNull { it.entry.giphyId == item.id }
                            val inNet = myEntry != null || peer != null
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        when {
                                            // В нашей сети - наружный ресурс не тратим.
                                            myEntry != null -> onAttachLocal(myEntry)
                                            peer != null -> onRequestSwarm(peer)
                                            else -> onAttach(item)
                                        }
                                    },
                            ) {
                                AsyncImage(
                                    model = item.preview,
                                    contentDescription = item.id,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                )
                                if (peer != null && myEntry == null) {
                                    Badge("у ${peer.holders.size}", Modifier.align(Alignment.TopStart))
                                }
                                if (inNet) NetDot(Modifier.align(Alignment.TopEnd))
                            }
                        }
                    }
                    if (loading) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    } else if (next.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onMore,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) { Text("Ещё") }
                    }
                }

                // ── Пометка: что означает синяя точка (решение владельца) ──
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFF2F80ED), CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "синяя точка - гифка уже в нашей сети: придёт с телефонов " +
                            "своих, внешний каталог не тратится",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (swarmStatus != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        swarmStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

/** Переливающаяся синяя точка «уже в нашей сети» (правый верхний угол). */
@Composable
private fun NetDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "netdot")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "netdotGlow",
    )
    val tint by transition.animateColor(
        initialValue = Color(0xFF2F80ED),
        targetValue = Color(0xFF8FC1FF),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "netdotTint",
    )
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(12.dp)
            .graphicsLayer { alpha = glow }
            .background(tint, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
    )
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(3.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun PlaceholderCell(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(6.dp),
        )
    }
}
