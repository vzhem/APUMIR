package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPADMINSCREEN.KT — административный кабинет группы
// =============================================================================
// Вкладки: Обзор, Участники (с поиском), Заявки, Ссылки (текст + QR),
// Статистика, Разрешения.
// =============================================================================

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuHeaderBubble
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.ApuVipBadge
import com.vladimir.messenger.ui.components.PeerAvatar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ChatWallpaper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuTabBar
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.AvatarPickerDialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.group.GroupPermissions
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.InviteSummary
import com.vladimir.messenger.data.group.JoinRequestSummary
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.util.QrCodeGenerator
import com.vladimir.messenger.ui.components.ApuPremiumCheckbox
import com.vladimir.messenger.ui.components.ApuPremiumSwitch

/**
 * Вкладки админ-кабинета.
 *
 * Обычному участнику показываются только «Обзор» (без полей редактирования) и
 * «Участники»: раньше он видел все вкладки и мог создавать и удалять ссылки.
 */
private enum class AdminTab(@androidx.annotation.StringRes val titleRes: Int) {
    Overview(R.string.ga_tab_overview),
    Admins(R.string.ga_tab_admins),
    Members(R.string.ga_tab_members),
    Requests(R.string.ga_tab_requests),
    Invites(R.string.ga_tab_invites),
    Stats(R.string.ga_tab_stats),
    Permissions(R.string.ga_tab_permissions),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAdminScreen(
    onBackClick: () -> Unit,
    onLeftGroup: () -> Unit,
    viewModel: GroupAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleTabs = if (uiState.isAdmin) {
        AdminTab.entries.toList()
    } else {
        listOf(AdminTab.Overview, AdminTab.Members)
    }
    var tab by remember { mutableStateOf(AdminTab.Overview) }
    // Права могли измениться (сняли администратора) - уходим на доступную вкладку.
    LaunchedEffect(visibleTabs) {
        if (tab !in visibleTabs) tab = AdminTab.Overview
    }

    // Выбранная вкладка и страница листалки ходят парой: тап по вкладке листает
    // страницу анимацией, а остановившаяся страница выбирает вкладку.
    val pagerState = rememberPagerState(
        initialPage = visibleTabs.indexOf(tab).coerceAtLeast(0),
        pageCount = { visibleTabs.size.coerceAtLeast(1) },
    )
    // Листание по тапу запускается из обработчика нажатия, а не отсюда: иначе
    // смена страницы на середине анимации перезапускала бы этот эффект и
    // отменяла его же анимацию - метка застревала между вкладками.
    val pagerScope = rememberCoroutineScope()
    LaunchedEffect(pagerState, visibleTabs) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            visibleTabs.getOrNull(page)?.let { if (it != tab) tab = it }
        }
    }
    LaunchedEffect(tab, visibleTabs) {
        val target = visibleTabs.indexOf(tab)
        if (target >= 0 && target != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(target)
        }
    }
    // Просмотры могут измениться без изменения карточки группы/канала.
    LaunchedEffect(tab) {
        if (tab == AdminTab.Stats) viewModel.refreshStats()
    }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                title = {
                    ApuHeaderBubble {
                        Text(
                            uiState.group?.title
                                ?: if (uiState.group?.isChannel == true) stringResource(R.string.contacts_channel) else stringResource(R.string.contacts_group),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { ApuTextAction(label = stringResource(R.string.action_back), onClick = onBackClick) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {

            uiState.error?.let {
                ApuBubble(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            uiState.notice?.let {
                ApuBubble(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }

            ApuTabBar(
                titles = visibleTabs.map { stringResource(it.titleRes) },
                selectedIndex = pagerState.currentPage,
                offsetFraction = pagerState.currentPageOffsetFraction,
                onSelect = { index -> pagerScope.launch { pagerState.animateScrollToPage(index) } },
            )

            // Вкладки листаются пальцем так же, как разделы на главной:
            // страницы едут за пальцем, в движении видно сразу две.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> visibleTabs.getOrNull(page)?.name ?: page.toString() },
                pageSize = PageSize.Fill,
                beyondViewportPageCount = 1,
            ) { page ->
            when (visibleTabs.getOrNull(page) ?: AdminTab.Overview) {
                AdminTab.Overview -> OverviewTab(
                    groupId = uiState.groupId,
                    title = uiState.group?.title.orEmpty(),
                    about = uiState.group?.about.orEmpty(),
                    isPublic = uiState.group?.isPublic == true,
                    isChannel = uiState.group?.isChannel == true,
                    isOwner = uiState.isOwner,
                    canChangeInfo = uiState.canChangeInfo,
                    canChangeVisibility = uiState.isAdmin,
                    topicsEnabled = uiState.group?.topicsEnabled == true,
                    // р250: медленный режим - сколько секунд участник ждёт
                    // между сообщениями (0 - выключен).
                    slowModeSeconds = uiState.group?.slowModeSeconds ?: 0,
                    onSetAvatar = viewModel::setGroupAvatar,
                    onSave = viewModel::updateProfile,
                    onTogglePublic = viewModel::setPublic,
                    onEnableTopics = viewModel::enableTopics,
                    onSetSlowMode = viewModel::setSlowMode,
                    onLeave = { viewModel.leaveGroup(onLeftGroup) },
                    onDeleteGroup = { viewModel.deleteGroup(onLeftGroup) },
                )

                AdminTab.Admins -> AdminsTab(
                    members = uiState.members,
                    isOwner = uiState.isOwner,
                    ownership = uiState.ownership,
                    onToggleAdmin = viewModel::toggleAdmin,
                    onTogglePermission = viewModel::setAdminPermission,
                    onTransferOwnership = viewModel::transferOwnership,
                    onClaimOwnership = viewModel::claimOwnership,
                )

                AdminTab.Members -> MembersTab(
                    isAdmin = uiState.isAdmin,
                    members = uiState.searchResults,
                    vipNodeIds = uiState.vipNodeIds,
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onToggleAdmin = viewModel::toggleAdmin,
                    onTogglePermission = viewModel::setAdminPermission,
                    onBlock = viewModel::blockMember,
                    onResync = viewModel::resyncMembers,
                )

                AdminTab.Requests -> RequestsTab(requests = uiState.requests, onDecide = viewModel::decideRequest)

                AdminTab.Invites -> InvitesTab(
                    invites = uiState.invites,
                    groupTitle = uiState.group?.title.orEmpty(),
                    isChannel = uiState.group?.isChannel == true,
                    isPublic = uiState.group?.isPublic == true,
                    canManage = uiState.canManageInvites,
                    onCreate = viewModel::createInvite,
                    onRevoke = viewModel::revokeInvite,
                    onDelete = viewModel::deleteInvite,
                )

                AdminTab.Stats -> GroupStatisticsTab(
                    stats = uiState.stats,
                    topics = uiState.topics,
                    isChannel = uiState.group?.isChannel == true,
                    isRefreshing = uiState.isStatsRefreshing,
                    onRefresh = viewModel::refreshStats,
                )

                AdminTab.Permissions -> PermissionsTab(
                    mask = uiState.memberPermissions,
                    onToggle = viewModel::setMemberPermission,
                )
            }
            }
        }
    }
    }
}

@Composable
private fun OverviewTab(
    groupId: String,
    title: String,
    about: String,
    isPublic: Boolean,
    /** Канал или группа: от этого зависят все подписи на экране. */
    isChannel: Boolean,
    isOwner: Boolean,
    canChangeInfo: Boolean,
    canChangeVisibility: Boolean,
    /** Раунд 153: у группы темы выключены - предлагаем включить. */
    topicsEnabled: Boolean,
    /** р250: медленный режим, секунд (0 - выключен). */
    slowModeSeconds: Int,
    onSetAvatar: (android.net.Uri) -> Unit,
    onSave: (String, String) -> Unit,
    onTogglePublic: (Boolean) -> Unit,
    onEnableTopics: () -> Unit,
    /** р250: включить или выключить медленный режим. */
    onSetSlowMode: (Int) -> Unit,
    onLeave: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    // Ключи в remember обязательны: без них черновики запоминали пустые строки
    // с первой композиции, когда группа ещё не загрузилась, и название с
    // описанием появлялись только после переключения вкладок туда-сюда.
    var titleDraft by remember(title) { mutableStateOf(title) }
    var aboutDraft by remember(about) { mutableStateOf(about) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Раунд 42: аватар группы/канала.
    var showAvatarPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Что сейчас обрезают под аватар группы. null - окна выбора области нет.
    var groupAvatarToCrop by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        // Не ставим сразу: сперва человек выбирает область, как в профиле.
        if (uri != null) {
            groupAvatarToCrop = com.vladimir.messenger.util.AvatarFiles.readForCrop(context, uri)
        }
    }

    // Снимок для аватара группы: тот же путь, что и в профиле.
    var pendingGroupPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    val groupPhotoTaker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val shot = pendingGroupPhoto
        if (ok && shot != null) {
            groupAvatarToCrop = com.vladimir.messenger.util.AvatarFiles.readForCrop(context, shot)
        }
        pendingGroupPhoto = null
    }
    val groupCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val target = com.vladimir.messenger.util.AvatarFiles.captureTarget(context)
            pendingGroupPhoto = target
            groupPhotoTaker.launch(target)
        }
    }
    val groupAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
        .collectAsState()
    // Общий кэш вместо разбора в отрисовке: base64 раскодируется в фоне.
    val groupAvatarBitmap = com.vladimir.messenger.ui.components.AvatarBitmaps
        .rememberAvatar(groupAvatars["g:$groupId"])

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Каждый блок настроек - в своём светлом пузыре: экран рисуется поверх
        // обоев, и голый текст на тёмной подложке не читался.
        ApuBubble {
        if (canChangeInfo) {
            // Поля внутри светлого пузыря: цвета ФИКСИРОВАННЫЕ, из темы брать
            // нельзя - в тёмной теме поле красилось в белый и на светлой
            // подложке «Название» и «Описание» пропадали (жалоба владельца).
            ApuBubbleField(
                value = titleDraft,
                onValueChange = { titleDraft = it },
                label = { Text(stringResource(R.string.groups_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ApuBubbleField(
                value = aboutDraft,
                onValueChange = { aboutDraft = it },
                label = { Text(stringResource(R.string.groups_desc)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ApuTextAction(label = stringResource(R.string.admin_save), onClick = { onSave(titleDraft, aboutDraft) })
        } else {
            // Без права менять информацию показываем только текст: поля и
            // кнопка «Сохранить» обещали правку, которой на самом деле нет -
            // сохранение молча отклонялось.
            Text(stringResource(R.string.groups_name), style = MaterialTheme.typography.labelLarge)
            Text(title.ifBlank { "—" })
            Text(stringResource(R.string.groups_desc), style = MaterialTheme.typography.labelLarge)
            Text(about.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.admin_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }
        }

        // Аватар группы/канала: меняют те же, кто название и описание.
        if (canChangeInfo) {
            ApuBubble {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (groupAvatarBitmap != null) {
                    Image(
                        bitmap = groupAvatarBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isChannel) stringResource(R.string.admin_avatar_channel) else stringResource(R.string.admin_avatar_group),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.admin_avatar_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
                ApuTextAction(label = stringResource(R.string.admin_change), onClick = { showAvatarPicker = true })
            }
            }
        }

        ApuBubble {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isChannel) stringResource(R.string.groups_public_channel) else stringResource(R.string.groups_public_group),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (isPublic) stringResource(R.string.groups_join_no_approval) else stringResource(R.string.admin_join_approval_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
                if (canChangeVisibility) {
                    ApuPremiumSwitch(checked = isPublic, onCheckedChange = onTogglePublic)
                }
            }
        }

        // Раунд 153: перевод «без тем» -> «с темами» (владелец: «нужно
        // в настройках переводить»). Односторонний: после включения
        // переключатель исчезает - темы уже есть.
        if (!isChannel && !topicsEnabled) {
            ApuBubble {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_topics_title), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.admin_topics_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                    ApuPremiumSwitch(checked = false, onCheckedChange = { onEnableTopics() })
                }
            }
        }

        // р250: медленный режим. Блок видят все - режим заметен в чате, и
        // скрывать его нет смысла; переключают только те, кто вправе менять
        // информацию о группе (как название и описание).
        ApuBubble {
            Column {
                Text(stringResource(R.string.admin_slow_mode), fontWeight = FontWeight.Medium)
                Text(
                    if (slowModeSeconds > 0) {
                        "Участники пишут не чаще одного сообщения в ${slowModeLabel(slowModeSeconds)}. " +
                            stringResource(R.string.admin_no_pause)
                    } else {
                        stringResource(R.string.admin_slow_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    com.vladimir.messenger.data.group.GroupWire.SLOW_MODE_CHOICES.forEach { seconds ->
                        val selected = seconds == slowModeSeconds
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    }
                                )
                                .then(
                                    if (canChangeInfo) {
                                        Modifier.clickable { onSetSlowMode(seconds) }
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                slowModeShort(seconds),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) Color.White else ApuBubbleAccentColor,
                            )
                        }
                    }
                }
                if (!canChangeInfo) {
                    Text(
                        stringResource(R.string.admin_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }
        }

        ApuBubble {
            ApuTextAction(
                label = if (isChannel) stringResource(R.string.admin_unsubscribe_channel) else stringResource(R.string.admin_leave_group),
                onClick = { showLeaveConfirm = true },
            )

            if (isOwner) {
                ApuSettingsDivider(startPadding = 16.dp)
                Text(
                    if (isChannel) {
                        stringResource(R.string.admin_delete_channel_hint)
                    } else {
                        stringResource(R.string.admin_delete_group_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ApuTextAction(
                    label = if (isChannel) stringResource(R.string.menu_delete_channel) else stringResource(R.string.menu_delete_group),
                    onClick = { showDeleteConfirm = true },
                    danger = true,
                )
            }
        }
    }

    if (showLeaveConfirm) {
        ApuSettingsDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(if (isChannel) stringResource(R.string.groups_unsubscribe_channel_q) else stringResource(R.string.admin_leave_group_q)) },
            text = {
                Text(
                    if (isChannel) {
                        stringResource(R.string.admin_leave_channel_body)
                    } else {
                        stringResource(R.string.admin_leave_group_body)
                    }
                )
            },
            confirmButton = {
                ApuTextAction(
                    label = if (isChannel) stringResource(R.string.menu_unsubscribe) else stringResource(R.string.admin_leave),
                    onClick = {
                        showLeaveConfirm = false
                        onLeave()
                    },
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { showLeaveConfirm = false })
            },
        )
    }

    if (showDeleteConfirm) {
        DeleteGroupDialog(
            expectedTitle = title,
            isChannel = isChannel,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDeleteGroup()
            },
        )
    }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            context = context,
            onPickUri = { uri ->
                showAvatarPicker = false
                onSetAvatar(android.net.Uri.parse(uri))
            },
            onPickGallery = {
                showAvatarPicker = false
                galleryPicker.launch("image/*")
            },
            onTakePhoto = {
                showAvatarPicker = false
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.CAMERA,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    val target = com.vladimir.messenger.util.AvatarFiles.captureTarget(context)
                    pendingGroupPhoto = target
                    groupPhotoTaker.launch(target)
                } else {
                    groupCameraPermission.launch(android.Manifest.permission.CAMERA)
                }
            },
            onDismiss = { showAvatarPicker = false },
        )
    }

    // Выбор области для аватара группы: и для снимка, и для картинки из
    // галереи. Раньше окно было только в профиле, а здесь кадр брался целиком.
    groupAvatarToCrop?.let { source ->
        com.vladimir.messenger.ui.components.AvatarCropDialog(
            source = source,
            onConfirm = { cropped ->
                com.vladimir.messenger.util.AvatarFiles.saveCropped(context, cropped)
                    ?.let { onSetAvatar(android.net.Uri.parse(it)) }
                groupAvatarToCrop = null
            },
            onDismiss = { groupAvatarToCrop = null },
        )
    }
}

/** р250: подпись выбранной паузы словами (для пояснения в настройках). */
private fun slowModeLabel(seconds: Int): String = when (seconds) {
    10 -> "10 секунд"
    30 -> "30 секунд"
    60 -> "1 минуту"
    300 -> "5 минут"
    900 -> "15 минут"
    else -> "$seconds с"
}

/** р250: короткая подпись на кнопке выбора паузы. */
private fun slowModeShort(seconds: Int): String = when (seconds) {
    0 -> "Выкл"
    10 -> "10 с"
    30 -> "30 с"
    60 -> "1 мин"
    300 -> "5 мин"
    900 -> "15 мин"
    else -> "$seconds с"
}

/**
 * Защита от случайного удаления: кроме нажатия кнопки нужно ввести название
 * группы. Если названия нет, просим ввести слово УДАЛИТЬ.
 */
@Composable
private fun DeleteGroupDialog(
    expectedTitle: String,
    isChannel: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val expected = expectedTitle.trim().ifBlank { "УДАЛИТЬ" }
    var typed by remember { mutableStateOf("") }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isChannel) stringResource(R.string.admin_delete_channel_q) else stringResource(R.string.admin_delete_group_q)) },
        text = {
            Column {
                Text(
                    if (isChannel) {
                        stringResource(R.string.admin_delete_channel_text)
                    } else {
                        stringResource(R.string.admin_delete_group_text)
                    },
                )
                Spacer(Modifier.height(12.dp))
                ApuBubbleField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.admin_type_prompt, expected)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.action_delete),
                onClick = onConfirm,
                enabled = typed.trim() == expected,
                danger = true,
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

/** Вкладка «Администраторы»: кто управляет группой и с какими правами. */
@Composable
private fun AdminsTab(
    members: List<MemberSummary>,
    isOwner: Boolean,
    ownership: GroupRepository.OwnershipState?,
    onToggleAdmin: (String, Boolean) -> Unit,
    onTogglePermission: (String, Long, Boolean) -> Unit,
    onTransferOwnership: (String) -> Unit,
    onClaimOwnership: () -> Unit,
) {
    val admins = members.filter { it.role == GroupRole.OWNER || it.role == GroupRole.ADMIN }
    // Передача владения - шаг серьёзный: спрашиваем подтверждение по имени.
    var transferTarget by remember { mutableStateOf<MemberSummary?>(null) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Наследование: владелец долго не выходил на связь - администратору
        // (а без администраторов и участнику) предлагаем забрать права.
        if (ownership?.canClaim == true) {
            item {
                ApuBubble {
                    Column {
                        val silentDays = (ownership.silenceMs ?: 0L) / (24L * 60 * 60 * 1000)
                        Text(
                            if (ownership.noAdmins) {
                                "Владелец не выходил на связь $silentDays дн., администраторов в группе нет."
                            } else {
                                "Владелец не выходил на связь $silentDays дн."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.admin_claim_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                        ApuTextAction(label = stringResource(R.string.admin_claim_owner), onClick = onClaimOwnership)
                    }
                }
            }
        }
        item {
            ApuBubble {
                Text(
                    stringResource(R.string.admin_assign_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
        }
        if (admins.isEmpty()) {
            item { ApuBubble { Text(stringResource(R.string.admin_no_admins)) } }
        }
        items(admins, key = { it.nodeId }) { admin ->
            ApuBubble {
                Column {
                    Text(admin.displayName.ifBlank { admin.nodeId }, fontWeight = FontWeight.Medium)
                    Text(
                        if (admin.role == GroupRole.OWNER) stringResource(R.string.admin_owner_rights) else stringResource(R.string.admin_role_admin),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                    if (admin.role == GroupRole.ADMIN) {
                        // Разрешения занимают пол-экрана, поэтому список сворачивается:
                        // нажал на строку - развернулось, нажал ещё раз - свернулось.
                        var expanded by remember(admin.nodeId) { mutableStateOf(false) }
                        ApuSettingsDivider(startPadding = 16.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded },
                        ) {
                            Text(
                                if (expanded) {
                                    stringResource(R.string.admin_perms_collapse)
                                } else {
                                    stringResource(R.string.admin_perms_expand)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (expanded) {
                            GroupPermissions.Admin.entries.forEach { entry ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.title)
                                        Text(
                                            entry.hint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ApuBubbleMutedColor,
                                        )
                                    }
                                    ApuPremiumSwitch(
                                        checked = GroupPermissions.has(admin.permissions, entry.flag),
                                        onCheckedChange = { onTogglePermission(admin.nodeId, entry.flag, it) },
                                    )
                                }
                            }
                        }
                        if (!admin.isMe) {
                            ApuTextAction(
                                label = stringResource(R.string.admin_remove_admin),
                                onClick = { onToggleAdmin(admin.nodeId, false) },
                            )
                        }
                        // Передача владения: только владелец и только другому
                        // администратору (наследование после удаления владельца
                        // работает и без этой кнопки).
                        if (isOwner && !admin.isMe) {
                            ApuSettingsDivider(startPadding = 16.dp)
                            ApuTextAction(
                                label = stringResource(R.string.admin_transfer_ownership),
                                onClick = { transferTarget = admin },
                            )
                        }
                    }
                }
            }
        }
    }

    transferTarget?.let { target ->
        ApuSettingsDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text(stringResource(R.string.admin_transfer_q)) },
            text = {
                Text(
                    "«${target.displayName.ifBlank { target.nodeId }}» станет владельцем группы. " +
                        "Вы останетесь администратором со всеми правами. Отменить передачу нельзя - " +
                        "новый владелец сам решит, кому передавать дальше."
                )
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.admin_transfer),
                    onClick = {
                        transferTarget = null
                        onTransferOwnership(target.nodeId)
                    },
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { transferTarget = null })
            },
        )
    }
}

@Composable
private fun MembersTab(
    isAdmin: Boolean,
    members: List<MemberSummary>,
    /** Узлы-элита: кольцо и знак VIP у участников. */
    vipNodeIds: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleAdmin: (String, Boolean) -> Unit,
    onTogglePermission: (String, Long, Boolean) -> Unit,
    onBlock: (String) -> Unit,
    onResync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // У вступивших раньше, чем появились темы, список тем пустой.
        // Кнопка рассылает карточку группы, темы и состав заново.
        // Обычному участнику не показываем: рассылка - дело администратора.
        if (isAdmin) {
            ApuBubble(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ApuTextAction(label = stringResource(R.string.admin_resync), onClick = onResync)
            }
        }
        ApuSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.admin_search_placeholder),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(members, key = { it.nodeId }) { member ->
                MemberRow(
                    isAdmin = isAdmin,
                    member = member,
                    vip = member.nodeId.lowercase() in vipNodeIds,
                    onToggleAdmin = { onToggleAdmin(member.nodeId, member.role != GroupRole.ADMIN) },
                    onTogglePermission = { flag, enabled -> onTogglePermission(member.nodeId, flag, enabled) },
                    onBlock = { onBlock(member.nodeId) },
                )
                ApuSettingsDivider(startPadding = 16.dp)
            }
        }
    }
}

@Composable
private fun MemberRow(
    isAdmin: Boolean,
    member: MemberSummary,
    /** Участник из элиты: кольцо вокруг аватарки и знак VIP у имени. */
    vip: Boolean = false,
    onToggleAdmin: () -> Unit,
    onTogglePermission: (Long, Boolean) -> Unit,
    onBlock: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ApuBubble(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Аватарка участника: у элиты — в объёмном золотом кольце.
                val memberAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
                    .collectAsState()
                PeerAvatar(
                    name = member.displayName.ifBlank { member.nodeId },
                    avatarB64 = memberAvatars[member.nodeId],
                    vip = vip,
                    size = 40.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            member.displayName.ifBlank { member.nodeId },
                            fontWeight = FontWeight.Medium,
                        )
                        if (vip) {
                            Spacer(Modifier.width(6.dp))
                            ApuVipBadge(compact = true)
                        }
                    }
                    Text(
                        when (member.role) {
                            GroupRole.OWNER -> stringResource(R.string.admin_role_owner)
                            GroupRole.ADMIN -> stringResource(R.string.admin_role_admin)
                            else -> stringResource(R.string.admin_role_member)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
                // Управление участниками видно только администраторам:
                // обычный участник не должен видеть чужие «Права» и «Исключить».
                if (isAdmin && !member.isMe && member.role != GroupRole.OWNER) {
                    ApuTextAction(label = stringResource(R.string.admin_perms), onClick = { expanded = !expanded })
                    ApuTextAction(label = stringResource(R.string.admin_exclude), onClick = onBlock)
                }
            }

            if (expanded && member.role == GroupRole.ADMIN) {
                ApuSettingsDivider(startPadding = 16.dp)
                Text(stringResource(R.string.admin_perms_title), style = MaterialTheme.typography.labelLarge)
                GroupPermissions.Admin.entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title)
                            Text(
                                entry.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                        ApuPremiumSwitch(
                            checked = GroupPermissions.has(member.permissions, entry.flag),
                            onCheckedChange = { onTogglePermission(entry.flag, it) },
                        )
                    }
                }
            } else if (expanded) {
                ApuSettingsDivider(startPadding = 16.dp)
                ApuTextAction(
                    label = if (member.role == GroupRole.ADMIN) stringResource(R.string.admin_remove_admin) else stringResource(R.string.admin_make_admin),
                    onClick = onToggleAdmin,
                )
            }
        }
    }
}

@Composable
private fun RequestsTab(requests: List<JoinRequestSummary>, onDecide: (String, Boolean) -> Unit) {
    if (requests.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ApuBubble(modifier = Modifier.padding(24.dp)) { Text(stringResource(R.string.admin_no_requests)) }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(requests, key = { it.nodeId }) { request ->
            ApuBubble {
                Column {
                    Text(request.displayName.ifBlank { request.nodeId }, fontWeight = FontWeight.Medium)
                    if (request.note.isNotBlank()) {
                        Text(
                            request.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApuTextAction(
                            label = stringResource(R.string.admin_approve),
                            onClick = { onDecide(request.nodeId, true) },
                        )
                        ApuTextAction(
                            label = stringResource(R.string.admin_reject),
                            onClick = { onDecide(request.nodeId, false) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitesTab(
    invites: List<InviteSummary>,
    groupTitle: String,
    /** Раунд 162: тексту «Поделиться» - честное «канал»/«группа». */
    isChannel: Boolean,
    isPublic: Boolean,
    canManage: Boolean,
    onCreate: (Boolean) -> Unit,
    onRevoke: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ApuBubble {
            if (canManage) {
                // Одна ссылка и один понятный переключатель.
                //
                // Две кнопки («с одобрением» и «без одобрения») читались
                // двояко: «с одобрением» можно понять и как «уже одобрено,
                // заходи». Из-за этого создавалась ссылка не того типа, и
                // владелец либо получал лишнюю заявку, либо не получал её вовсе.
                var needsApproval by remember { mutableStateOf(!isPublic) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_approval_only), fontWeight = FontWeight.Medium)
                        Text(
                            if (needsApproval) {
                                stringResource(R.string.admin_by_link_request)
                            } else {
                                stringResource(R.string.admin_by_link_direct)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                    ApuPremiumSwitch(checked = needsApproval, onCheckedChange = { needsApproval = it })
                }
                ApuTextAction(label = stringResource(R.string.chat_create_link), onClick = { onCreate(needsApproval) })
            } else {
                Text(
                    stringResource(R.string.admin_links_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
            if (!isPublic) {
                Text(
                    stringResource(R.string.admin_private_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
            }
        }
        items(invites, key = { it.slug }) { invite ->
            InviteCard(
                invite = invite,
                groupTitle = groupTitle,
                isChannel = isChannel,
                canManage = canManage,
                onRevoke = { onRevoke(invite.slug) },
                onDelete = { onDelete(invite.slug) },
            )
        }
    }
}

/**
 * Ссылка-приглашение и QR-код того же текста рядом с ней.
 * Ссылку можно выделить пальцем, скопировать одной кнопкой и отдать в любое
 * приложение системным меню «Поделиться».
 */
@Composable
private fun InviteCard(
    invite: InviteSummary,
    groupTitle: String,
    /** Раунд 162: «Поделиться» пишет честное «канал»/«группа». */
    isChannel: Boolean,
    canManage: Boolean,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
) {
    // QR - по основной ссылке (сканер APU разбирает её без сети), а текст,
    // «Копировать» и «Поделиться» - короткая ссылка для пересылки: она не
    // показывает идентификаторы группы и владельца.
    val bitmap = remember(invite.link) { QrCodeGenerator.generateQrCode(invite.link) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Раунд 198: получателя ссылки может не быть с APU - по умолчанию
    // прикладываем установочный APK; галочка позволяет не таскать 40 МБ.
    var attachApk by remember { mutableStateOf(true) }

    ApuBubble {
        Column {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    // SelectionContainer делает текст выделяемым: без него
                    // ссылку нельзя было ни отметить, ни скопировать.
                    SelectionContainer {
                        Text(invite.shareLink, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(if (invite.requestApproval) "по заявке" else "вход сразу")
                            append(" • использований: ").append(invite.useCount)
                            if (invite.maxUses > 0) append("/").append(invite.maxUses)
                            if (invite.revoked) append(" • отозвана")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.admin_qr_desc),
                        modifier = Modifier.size(120.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.admin_qr_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { attachApk = !attachApk }
                    .padding(horizontal = 4.dp),
            ) {
                ApuPremiumCheckbox(checked = attachApk, onCheckedChange = { attachApk = it })
                Text(
                    stringResource(R.string.contacts_attach_apk),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ApuTextAction(
                    label = stringResource(R.string.action_copy),
                    onClick = { clipboard.setText(AnnotatedString(invite.shareLink)) },
                )
                ApuTextAction(
                    label = stringResource(R.string.admin_share),
                    onClick = { AppShare.shareGroupInvite(context, groupTitle, invite.shareLink, isChannel, attachApk) },
                )
                // Отозвать и удалить ссылку может только администратор:
                // участник без права приглашать этих кнопок не видит.
                if (canManage) {
                    if (invite.revoked) {
                        ApuTextAction(label = stringResource(R.string.action_delete), onClick = onDelete)
                    } else {
                        ApuTextAction(label = stringResource(R.string.admin_revoke), onClick = onRevoke)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsTab(mask: Long, onToggle: (Long, Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        ApuBubble {
            Text(stringResource(R.string.admin_member_perms), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.admin_member_perms_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }
        Spacer(Modifier.height(8.dp))
        GroupPermissions.Member.entries.forEach { entry ->
            ApuBubble(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title)
                        Text(
                            entry.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                    ApuPremiumSwitch(
                        checked = GroupPermissions.has(mask, entry.flag),
                        onCheckedChange = { onToggle(entry.flag, it) },
                    )
                }
            }
        }
    }
}
