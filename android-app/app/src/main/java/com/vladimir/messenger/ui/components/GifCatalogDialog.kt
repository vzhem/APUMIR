package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vladimir.messenger.data.gif.GifItem
import com.vladimir.messenger.data.gif.GifLibEntry
import com.vladimir.messenger.data.gif.SwarmGif

/**
 * Каталог GIF (v11.74.14), раунд 121 - два источника:
 * - «В нашей сети»: СВОЙ каталог на телефонах (без внешнего ресурса, без лимитов).
 *   Гифки, скачанные кем-то из своих, лежат на его телефоне; выбор шлёт
 *   хранителю просьбу, и он отправляет её защищённой передачей файлов.
 * - «Каталог»: внешний (Giphy через наш сервер) - только если гифки ещё
 *   нет нигде; скачанная оседает в библиотеке и становится частью роя.
 */
@Composable
fun GifCatalogDialog(
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
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Гифки") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { onTab("swarm") }) {
                        Text(
                            "В нашей сети",
                            fontWeight = if (tab == "swarm") FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == "swarm") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(onClick = { onTab("external") }) {
                        Text(
                            "Каталог",
                            fontWeight = if (tab == "external") FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == "external") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val pickOwnGif = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    ) { uri -> if (uri != null) onAddOwnGif(uri) }
                    TextButton(onClick = { pickOwnGif.launch("image/gif") }) {
                        Text(
                            "+ Своя",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                if (tab == "swarm") {
                    SwarmGifSection(
                        query = query,
                        onQuery = { query = it },
                        myGifs = myGifs,
                        swarmGifs = swarmGifs,
                        status = swarmStatus,
                        onAttachLocal = onAttachLocal,
                        onRequestSwarm = onRequestSwarm,
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск: котики, привет…") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { onSearch(query) }) {
                                Icon(Icons.Filled.Search, contentDescription = "Найти")
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    val error = error
                    if (error != null) {
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
                                .height(320.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            gridItems(items, key = { it.id }) { item ->
                                AsyncImage(
                                    model = item.preview,
                                    contentDescription = item.id,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onAttach(item) },
                                )
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
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun SwarmGifSection(
    query: String,
    onQuery: (String) -> Unit,
    myGifs: List<GifLibEntry>,
    swarmGifs: List<SwarmGif>,
    status: String?,
    onAttachLocal: (GifLibEntry) -> Unit,
    onRequestSwarm: (SwarmGif) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Фильтр: котики, привет…") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    val q = query.trim().lowercase()
    val my = if (q.isBlank()) {
        myGifs
    } else {
        myGifs.filter { it.tag.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
    }
    val swarm = if (q.isBlank()) {
        swarmGifs
    } else {
        swarmGifs.filter { it.entry.tag.lowercase().contains(q) || it.entry.displayName.lowercase().contains(q) }
    }
    if (my.isEmpty() && swarm.isEmpty()) {
        Text(
            if (q.isBlank()) {
                "Пока пусто. Скачайте гифку во вкладке «Каталог» или добавьте " +
                    "свою кнопкой «+ Своя» - она поселится на телефоне и станет " +
                    "частью нашей сети. Каталоги телефонов " +
                    "обмениваются автоматически: чем дольше пользуетесь, тем больше " +
                    "набор без внешнего ресурса."
            } else {
                "Не нашлось"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 8.dp),
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            gridItems(my, key = { "m-${it.sha256}" }) { entry ->
                val context = LocalContext.current
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
                    Text(
                        "мой",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            gridItems(swarm, key = { "s-${it.entry.sha256}" }) { swarmGif ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRequestSwarm(swarmGif) },
                ) {
                    PlaceholderCell(
                        label = swarmGif.entry.tag.ifBlank { "гифка" },
                        badge = "у ${swarmGif.holders.size}",
                    )
                }
            }
        }
    }
    if (status != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PlaceholderCell(label: String, badge: String? = null) {
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
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
