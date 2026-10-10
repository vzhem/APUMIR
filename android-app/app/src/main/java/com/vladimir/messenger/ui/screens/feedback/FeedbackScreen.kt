package com.vladimir.messenger.ui.screens.feedback

import com.vladimir.messenger.ui.components.ApuBubbleField
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.BuildConfig
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuPremiumCheckbox
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.swipeBack
import com.vladimir.messenger.util.FeedbackMail
import java.io.File
import kotlinx.coroutines.launch

/**
 * «Написать разработчику»: описание, галочка для логов, скриншоты, которые
 * человек выбирает сам из галереи, и одна кнопка - письмо открывается в
 * почтовом приложении.
 */
@Composable
fun FeedbackScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var attachLogs by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    // Скриншоты храним путями к копиям в кэше, чтобы они переживали поворот экрана.
    var shots by rememberSaveable(
        saver = listSaver<List<File>, String>(
            save = { list -> list.map { it.path } },
            restore = { paths -> paths.map { File(it) }.toMutableList() },
        ),
    ) { mutableStateOf(listOf<File>()) }

    val pickShots = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val room = FeedbackMail.MAX_SCREENSHOTS - shots.size
            val copied = uris.take(room).mapNotNull { FeedbackMail.copyScreenshot(context, it) }
            shots = shots + copied
            busy = false
            if (copied.size < uris.take(room).size) {
                Toast.makeText(context, context.getString(R.string.feedback_shot_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .swipeBack(onBack = onBackClick),
    ) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = { ApuSettingsHeader(stringResource(R.string.feedback_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.feedback_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.feedback_to, FeedbackMail.EMAIL),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ApuBubbleField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.feedback_text_label)) },
                    placeholder = { Text(stringResource(R.string.feedback_text_hint)) },
                    minLines = 6,
                )

                Text(
                    stringResource(R.string.feedback_shots_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.feedback_shots_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shots.forEachIndexed { index, file ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.feedback_shot_item, index + 1),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                shots = shots.filterNot { it == file }
                                file.delete()
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feedback_shot_remove))
                        }
                    }
                }
                ApuPremiumContentButton(
                    onClick = { pickShots.launch("image/*") },
                    style = DiagnosticsActionStyle.GLASS,
                    enabled = !busy && shots.size < FeedbackMail.MAX_SCREENSHOTS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.feedback_add_shots))
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { attachLogs = !attachLogs }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ApuPremiumCheckbox(checked = attachLogs, onCheckedChange = { attachLogs = it })
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.feedback_attach),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    stringResource(R.string.feedback_attach_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ApuPremiumContentButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val files = mutableListOf<File>()
                            files += shots
                            if (attachLogs) {
                                val logs = FeedbackMail.writeLogs(context)
                                if (logs != null) {
                                    files += logs
                                } else {
                                    Toast.makeText(context, context.getString(R.string.feedback_logs_failed), Toast.LENGTH_LONG).show()
                                }
                            }
                            busy = false
                            val subject = context.getString(R.string.feedback_subject, BuildConfig.VERSION_NAME)
                            val body = buildString {
                                append(text.trim())
                                append("\n\n")
                                append(
                                    context.getString(
                                        R.string.feedback_body_meta,
                                        BuildConfig.VERSION_NAME,
                                        Build.VERSION.RELEASE,
                                        "${Build.MANUFACTURER} ${Build.MODEL}",
                                    ),
                                )
                            }
                            if (!FeedbackMail.send(context, subject, body, files)) {
                                Toast.makeText(context, context.getString(R.string.feedback_no_mail), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    style = DiagnosticsActionStyle.PRIMARY,
                    enabled = text.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(if (busy) R.string.feedback_collecting else R.string.feedback_send),
                    )
                }
            }
        }
    }
}
