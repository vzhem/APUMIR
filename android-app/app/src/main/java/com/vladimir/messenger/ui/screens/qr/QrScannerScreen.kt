package com.vladimir.messenger.ui.screens.qr

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// QRSCANNERSCREEN.KT — раздел QR: сканировать чужой код или показать свой
// =============================================================================
// Раньше кнопка на главной вела сразу в камеру, а свой код лежал в другом
// месте. Теперь наверху две вкладки: «Сканировать» и «Мой код» — обмен кодами
// делается на одном экране, без хождения по меню.
// =============================================================================

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.pager.HorizontalPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.data.link.ShortShare
import com.vladimir.messenger.util.OwnInvite
import com.vladimir.messenger.util.QrCodeGenerator
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

/** Что показывает раздел QR прямо сейчас. */
private enum class QrMode(val title: String) {
    Scan("Сканировать"),
    Mine("Мой код"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onBackClick: () -> Unit,
    onQrScanned: (String) -> Unit,
) {
    // Листалка: вкладки переключаются и кнопками, и пальцем. Состояние одно на
    // оба способа, поэтому кнопки и жест не спорят друг с другом.
    val pagerState = rememberPagerState(pageCount = { QrMode.entries.size })
    val scope = rememberCoroutineScope()
    val mode = QrMode.entries[pagerState.currentPage]

    Box(modifier = Modifier.fillMaxSize()) {
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
                    title = { Text(stringResource(R.string.qr_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                // Выбор простой и заметный: две большие кнопки во всю ширину.
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    QrMode.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = mode == item,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, QrMode.entries.size),
                            icon = {
                                Icon(
                                    if (item == QrMode.Scan) {
                                        Icons.Default.QrCodeScanner
                                    } else {
                                        Icons.Default.QrCode2
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(item.title) },
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // Соседнюю страницу заранее НЕ готовим: иначе камера
                    // запускалась бы, пока открыт «Мой код», и наоборот.
                    beyondViewportPageCount = 0,
                ) { page ->
                    when (QrMode.entries[page]) {
                        // Камера живёт только во вкладке сканера: при переходе
                        // на «Мой код» AndroidView уходит из композиции и
                        // камера освобождается сама.
                        QrMode.Scan -> ScanPane(onQrScanned = onQrScanned)
                        QrMode.Mine -> MyCodePane()
                    }
                }
            }
        }
    }
}

/** Вкладка «Сканировать»: камера и подсказка. */
@Composable
private fun ScanPane(onQrScanned: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted -> hasCameraPermission = isGranted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    // Один и тот же код камера показывает много кадров подряд,
                    // поэтому запоминаем последний обработанный текст.
                    var reported = ""
                    DecoratedBarcodeView(ctx).apply {
                        resume()
                        decodeContinuous(object : BarcodeCallback {
                            override fun barcodeResult(result: BarcodeResult?) {
                                val scanned = result?.text?.trim().orEmpty()
                                if (scanned.isNotEmpty() && scanned != reported) {
                                    // Принимаем ЛЮБОЙ прочитанный текст и сообщаем его
                                    // ровно один раз. Раньше здесь требовался префикс
                                    // "p2p://invite/", и сканер молча глотал коды групп и
                                    // контактов, поэтому между телефонами "не читал".
                                    // Разбором занимается навигация (NavGraph): она понимает
                                    // и группы, и контакты, и QR профиля p2p://invite/pk_...
                                    reported = scanned
                                    pause()
                                    onQrScanned(scanned)
                                }
                            }
                        })
                    }
                },
                onRelease = { view -> runCatching { view.pause() } },
                modifier = Modifier.fillMaxSize(),
            )

            ApuBubble(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.qr_scan_hint),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ApuBubble {
                    Text(
                        text = stringResource(R.string.qr_camera_needed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                ApuPremiumContentButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    style = DiagnosticsActionStyle.PRIMARY,
                ) {
                    Text(stringResource(R.string.qr_allow_camera))
                }
            }
        }
    }
}

/** Вкладка «Мой код»: крупный QR своей ссылки, копирование и «Поделиться». */
@Composable
private fun MyCodePane() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var showInviteShare by remember { mutableStateOf(false) }

    // Ссылка может быть ещё не готова на первом запуске (ключи создаются),
    // поэтому держим её отдельным nullable и разворачиваем один раз ниже.
    val maybeLink = remember { OwnInvite.link(context) }
    val displayName = remember { OwnInvite.displayName(context) }
    val username = remember { OwnInvite.username(context) }
    // Раунд 208: QR несёт УНИВЕРСАЛЬНУЮ https-ссылку (короткую с нашего
    // сервиса). У кого APU уже стоит - Android откроет приложение сразу
    // на добавлении контакта; у кого нет - откроется страница сервиса с
    // кнопкой установки: скачает APU по этому же коду (владелец).
    // Пока сервис не ответил (или ответил отказом) - прежний apu:// код.
    var qrLink by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(maybeLink) {
        val own = maybeLink ?: return@LaunchedEffect
        qrLink = own
        val short = withContext(Dispatchers.IO) {
            runCatching {
                dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    com.vladimir.messenger.data.link.LinkShortenerEntryPoint::class.java,
                ).linkShortener().shorten(own)
            }.getOrNull()
        }
        if (!short.isNullOrBlank()) qrLink = short
    }
    val bitmap = remember(qrLink) { qrLink?.let { QrCodeGenerator.generateQrCode(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (maybeLink == null || bitmap == null) {
            ApuBubble {
                Text(
                    stringResource(R.string.qr_not_ready),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        // Код на белом поле и во всю ширину: чем крупнее модули, тем быстрее
        // его ловит камера другого телефона.
        val link = qrLink ?: maybeLink

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.settings_my_qr),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .padding(10.dp),
        )

        ApuBubble {
            Text(
                displayName.ifBlank { stringResource(R.string.section_my_profile) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (username.isNotBlank()) {
                Text(
                    "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "Покажите этот код собеседнику: камера телефона добавит вас в контакты, а если у него ещё нет APU - по этому же коду он скачает приложение.",
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ApuPremiumContentButton(
                onClick = {
                    clipboard.setText(AnnotatedString(link))
                    copied = true
                },
                style = DiagnosticsActionStyle.PRIMARY,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (copied) stringResource(R.string.toast_copied) else stringResource(R.string.share_copy_btn))
            }
            ApuPremiumContentButton(
                onClick = { showInviteShare = true },
                style = DiagnosticsActionStyle.PRIMARY,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.admin_share))
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // Раунд 200: перед отправкой приглашения спрашиваем про APK.
    if (showInviteShare) {
        com.vladimir.messenger.ui.components.InviteAttachDialog(
            title = stringResource(R.string.chat_invite_apu),
            onDismiss = { showInviteShare = false },
            onShare = { attach ->
                showInviteShare = false
                val link = maybeLink
                if (link.isNullOrBlank()) {
                    android.widget.Toast.makeText(
                        context, "Личность ещё не создана",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    ShortShare.shareInvite(context, displayName, link, attach)
                }
            },
        )
    }
}
