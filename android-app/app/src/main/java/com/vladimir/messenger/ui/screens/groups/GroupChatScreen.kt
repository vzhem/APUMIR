package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPCHATSCREEN.KT — чат группы: слева значки групп, справа темы пузырями
// =============================================================================
// Раскладка по требованию владельца (2026-08-31, как в мессенджере со
// скриншота): после входа в группу слева остаётся вертикальная колонка
// значков всех групп и каналов с бейджами непрочитанных, а справа темы
// выбранной группы идут вертикальным списком, каждая в своём пузыре,
// и у каждой — бейдж непрочитанных. Нажатие на тему открывает ленту.

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuHeaderBubble
import com.vladimir.messenger.ui.components.ApuNotificationBadge
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuBubbleCard
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuAntiRatingInlineBadge
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.PeerAvatar
import com.vladimir.messenger.ui.components.ApuCircleCheckIndicator
import com.vladimir.messenger.ui.components.ApuMessageModerationDialog
import com.vladimir.messenger.ui.components.ApuPollCard
import com.vladimir.messenger.ui.components.CreatePollDialog
import com.vladimir.messenger.ui.components.ApuSettingsDangerColor
import com.vladimir.messenger.ui.components.PeerProfileSheet
import com.vladimir.messenger.ui.components.apuBubbleSurface
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.apuPremiumThread
import com.vladimir.messenger.ui.components.swipeBack
import com.vladimir.messenger.ui.components.swipeToReply
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Search
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import com.vladimir.messenger.util.GroupFileMarker
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.data.local.MessagePinPolicy
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.ui.components.AnimatedTopicIcon
import com.vladimir.messenger.ui.theme.LocalMessengerColors
import com.vladimir.messenger.ui.components.ApuAction
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ApuActionsMenu
import com.vladimir.messenger.ui.components.NotificationMuteDialog
import com.vladimir.messenger.ui.components.ApuMenuDots
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.FileCardState
import com.vladimir.messenger.ui.components.GifCatalogDialog
import com.vladimir.messenger.ui.components.InputPanelDialog
import com.vladimir.messenger.ui.components.GroupFileCard
import com.vladimir.messenger.ui.components.GroupQrInviteDialog
import com.vladimir.messenger.ui.components.fileIconFor
import com.vladimir.messenger.ui.components.ImagePreview
import com.vladimir.messenger.ui.components.TopicEmojiCatalog
import com.vladimir.messenger.ui.components.TopicEmojiPicker
import com.vladimir.messenger.ui.components.TopicIconCatalog
import com.vladimir.messenger.ui.components.TopicIconView
import com.vladimir.messenger.util.ImageLinkDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    onOpenAdmin: (groupId: String) -> Unit,
    onBackClick: () -> Unit,
    /** Нажатие на значок другой группы в левой колонке. */
    onSwitchGroup: (groupId: String) -> Unit = {},
    /** Нажатие на значок канала в левой колонке. */
    onSwitchChannel: (channelId: String) -> Unit = {},
    /** Раунд 203: тап по источнику пересылки - открыть чат друга. */
    onOpenChat: (chatId: String, contactName: String, contactId: String) -> Unit = { _, _, _ -> },
    /** Раунд 203: тап по источнику пересылки - открыть группу/канал (тему). */
    onOpenGroup: (groupId: String, topicId: String?) -> Unit = { _, _ -> },
    /** Раунд 203: источника нет на телефоне - карточка «Вступить»/«Подписаться». */
    onJoinByLink: (link: String) -> Unit = {},
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val muteRevision by viewModel.notificationMuteRevision.collectAsStateWithLifecycle()
    // р237: черновик темы живёт в модели (uiState.draft): он же уезжает на
    // второе устройство личности и возвращается, если чат открыть заново.
    var showNewTopic by remember { mutableStateOf(false) }
    // р250: окно создания опроса (кнопка «Опрос» у поля ввода).
    var showCreatePoll by remember { mutableStateOf(false) }
    // Раунд 260: тап по значку темы в шапке открывает её редактирование.
    var showEditTopic by remember { mutableStateOf(false) }
    // Раунд 213: «три точки» шапки + приглашение по QR коду.
    var showTopMenu by remember { mutableStateOf(false) }
    var showNotificationMuteDialog by remember { mutableStateOf(false) }
    var showQrInvite by remember { mutableStateOf(false) }
    var qrLink by remember { mutableStateOf<String?>(null) }
    var qrLoading by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(showQrInvite) {
        if (showQrInvite) {
            qrLoading = true
            qrLink = viewModel.inviteQrLink()
            qrLoading = false
        }
    }
    // Файл к сообщению (рой, этап 9): системный выбор → хэш и копия →
    // визитка в тексте; сам файл участники просят у автора и друг у друга.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFileSelected)
    }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> viewModel.onSaveTargetPicked(uri) }
    val pendingSave = uiState.pendingSave
    LaunchedEffect(pendingSave) {
        pendingSave?.let { transfer -> savePicker.launch(transfer.displayName) }
    }
    // Открыта ли лента конкретной темы. Пока не открыта и темы есть —
    // показываем вертикальный список тем пузырями, как просил владелец.
    var showFeed by remember { mutableStateOf(uiState.startInTopic) }
    // Раунд 143: заявки на вступление - пузырь над содержимым для админов.
    val joinRequests by viewModel.joinRequests.collectAsStateWithLifecycle()
    var showRequestsSheet by remember { mutableStateOf(false) }
    var requestQuery by remember { mutableStateOf("") }
    val canDecideRequests = uiState.me?.let {
        com.vladimir.messenger.data.group.GroupRole.isAdminOrOwner(it.role)
    } == true
    // Раунд 267: тап по пузырю шапки группы/канала ведёт в настройки -
    // но только админам/владельцу, у остальных тап ничего не делает.
    val iAmGroupAdmin = canDecideRequests
    // Каталог GIF (кнопка «GIF» у скрепки).
    var showGifCatalog by remember { mutableStateOf(false) }
    // Модальное окно удаления и модерации сообщений (одиночное или пакетное).
    var deleteForAllTarget by remember { mutableStateOf<com.vladimir.messenger.data.local.entity.MessageEntity?>(null) }
    var showBulkModeration by remember { mutableStateOf(false) }

    // Канал это или группа и корневой пост канала: нужно и окну модерации,
    // и ленте, поэтому считаем до обоих.
    val isChannel = uiState.group?.isChannel == true
    val channelRootMessageId = if (isChannel) {
        uiState.messages.firstOrNull {
            !com.vladimir.messenger.util.InlineImage.isPart(it.content)
        }?.id
    } else {
        null
    }

    // Имена авторов нужны и окну модерации, и ленте: держим их до обоих.
    val senderNames = remember(uiState.members) {
        uiState.members.associate { it.nodeId to it.displayName }
    }

    val moderationMessages = remember(deleteForAllTarget, showBulkModeration, uiState.selectedMessageIds, uiState.messages) {
        when {
            deleteForAllTarget != null -> listOfNotNull(deleteForAllTarget)
            showBulkModeration && uiState.selectedMessageIds.isNotEmpty() ->
                uiState.messages.filter { it.id in uiState.selectedMessageIds }
            else -> emptyList()
        }
    }
    if (moderationMessages.isNotEmpty()) {
        val primaryMsg = moderationMessages.first()
        val distinctAuthors = moderationMessages.map { it.senderId }.distinct()
        val targetAuthorId = if (distinctAuthors.size == 1) distinctAuthors.first() else primaryMsg.senderId
        val targetMember = uiState.members.firstOrNull { it.nodeId == targetAuthorId }
        val targetAuthorName = senderNames[targetAuthorId]?.takeIf { it.isNotBlank() }
            ?: targetMember?.displayName?.takeIf { it.isNotBlank() }
            ?: ("Участник " + targetAuthorId.takeLast(4))
        val isAuthorMe = primaryMsg.isFromMe || (uiState.me?.nodeId != null && uiState.me?.nodeId == targetAuthorId)
        val isAuthorOwner = (uiState.group?.ownerId != null && uiState.group?.ownerId == targetAuthorId) ||
            targetMember?.role == com.vladimir.messenger.data.group.GroupRole.OWNER
        var authorMsgCount by remember(targetAuthorId, uiState.messages.size) {
            mutableStateOf(uiState.messages.count { it.senderId == targetAuthorId }.coerceAtLeast(1))
        }
        LaunchedEffect(targetAuthorId) {
            if (targetAuthorId.isNotBlank()) {
                val total = viewModel.countAuthorMessages(targetAuthorId)
                if (total > 0) authorMsgCount = total
            }
        }
        val baseGroupMask = uiState.group?.memberPermissions?.takeIf { it != 0L }
            ?: com.vladimir.messenger.data.group.GroupPermissions.Member.DEFAULT
        val initialAuthorMask = if (targetMember != null) {
            com.vladimir.messenger.data.group.GroupPermissions.effectiveMemberPermissions(
                targetMember.permissions,
                baseGroupMask,
            )
        } else {
            baseGroupMask
        }
        ApuMessageModerationDialog(
            selectedMessageCount = moderationMessages.size,
            isChannel = isChannel,
            isChannelPost = isChannel && primaryMsg.id == channelRootMessageId,
            authorId = targetAuthorId,
            authorName = targetAuthorName,
            isAuthorMe = isAuthorMe,
            isAuthorOwner = isAuthorOwner,
            canModerate = uiState.canModerate,
            canDeleteForAll = uiState.canModerate || isAuthorMe,
            authorMessageCount = authorMsgCount,
            authorAntiCount = uiState.antiRatings[targetAuthorId] ?: 0,
            authorAntiWarning = uiState.antiWarnings.containsKey(targetAuthorId),
            alreadyHasMyAnti = targetAuthorId in uiState.myAntiRatings,
            initialMemberPermissionsMask = initialAuthorMask,
            onOpenAuthorProfile = if (targetAuthorId.isNotBlank()) {
                {
                    deleteForAllTarget = null
                    showBulkModeration = false
                    viewModel.openPeerProfile(targetAuthorId, targetAuthorName)
                }
            } else {
                null
            },
            onDismiss = {
                deleteForAllTarget = null
                showBulkModeration = false
            },
            onConfirm = { result ->
                val ids = moderationMessages.map { it.id }
                deleteForAllTarget = null
                showBulkModeration = false
                viewModel.applyModeration(ids, targetAuthorId, result)
            },
        )
    }

    // Карточка профиля участника (сердечки ❤️ и анти-рейтинг 👎).
    val inspectedPeerId = uiState.inspectedPeerId
    if (!inspectedPeerId.isNullOrBlank()) {
        val isSelfPeer = uiState.me?.nodeId == inspectedPeerId
        val peerClipboardCtx = androidx.compose.ui.platform.LocalContext.current
        PeerProfileSheet(
            name = uiState.inspectedPeerName.ifBlank { "Участник " + inspectedPeerId.takeLast(4) },
            contactId = inspectedPeerId,
            isOnline = true,
            // Элита в группе: знак и кольцо в карточке участника тоже.
            vip = inspectedPeerId.lowercase() in uiState.vipNodeIds,
            heartCount = uiState.inspectedPeerHearts,
            heartMine = uiState.inspectedPeerHeartMine,
            onHeartClick = if (!isSelfPeer) {
                { viewModel.togglePeerHeart(inspectedPeerId) }
            } else {
                null
            },
            antiRatingCount = uiState.inspectedPeerAntiCount,
            antiRatingWarning = uiState.inspectedPeerAntiWarning,
            antiRatingUntilMs = uiState.inspectedPeerAntiUntilMs,
            antiRatingMine = uiState.inspectedPeerAntiMine,
            onAntiRatingClick = if (!isSelfPeer) {
                { viewModel.togglePeerAntiRating(inspectedPeerId) }
            } else {
                null
            },
            onDismiss = { viewModel.closePeerProfile() },
            onCopyId = {
                val clipboard = peerClipboardCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(peerClipboardCtx.getString(R.string.grp_node), inspectedPeerId))
                Toast.makeText(peerClipboardCtx, peerClipboardCtx.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Раунд 203: «Поделиться в APU» в группах и каналах - та же пересылка
    // с указанием источника, что и в личном чате.
    var forwardPayload by remember {
        mutableStateOf<com.vladimir.messenger.ui.components.ForwardPayload?>(null)
    }
    val fwdScope = rememberCoroutineScope()
    val fwdCtx = androidx.compose.ui.platform.LocalContext.current
    // Раунд 203: подтверждение пересылки - плашка по центру экрана.
    var fwdSent by remember { mutableStateOf(false) }
    if (fwdSent) {
        com.vladimir.messenger.ui.components.ForwardSentOverlay(visible = true, onTimeout = { fwdSent = false })
    }
    forwardPayload?.let { payload ->
        val fCtx = androidx.compose.ui.platform.LocalContext.current
        var fLoading by remember { mutableStateOf(true) }
        var fTargets by remember {
            mutableStateOf<List<com.vladimir.messenger.ui.components.ForwardTarget>>(emptyList())
        }
        var fPicked by remember { mutableStateOf<com.vladimir.messenger.ui.components.ForwardTarget?>(null) }
        var fTopics by remember {
            mutableStateOf<List<com.vladimir.messenger.ui.components.ForwardTopic>>(emptyList())
        }
        var fTopicsLoading by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(payload) {
            fTargets = viewModel.forwardTargets()
            fLoading = false
        }
        androidx.compose.runtime.LaunchedEffect(fPicked?.id) {
            val picked = fPicked ?: return@LaunchedEffect
            fTopicsLoading = true
            fTopics = viewModel.forwardTopics(picked.id)
            fTopicsLoading = false
        }
        com.vladimir.messenger.ui.components.ForwardChooserDialog(
            targets = fTargets,
            loading = fLoading,
            onDismiss = { forwardPayload = null },
            onPick = { target ->
                if (target.kind == com.vladimir.messenger.ui.components.ForwardKind.FRIEND) {
                    forwardPayload = null
                    viewModel.forwardMessage(payload.text, payload.label, target) { ok ->
                        if (ok) {
                            fwdSent = true
                        } else {
                            android.widget.Toast.makeText(fCtx, fCtx.getString(R.string.toast_forward_failed), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    fPicked = target
                }
            },
        )
        fPicked?.let { target ->
            com.vladimir.messenger.ui.components.ForwardTopicPickerDialog(
                targetTitle = target.title,
                isChannel = target.isChannel,
                topics = fTopics,
                loading = fTopicsLoading,
                onDismiss = { fPicked = null },
                onPick = { topic ->
                    fPicked = null
                    forwardPayload = null
                    viewModel.forwardMessage(
                        payload.text,
                        payload.label,
                        target.copy(topicId = topic.id, topicTitle = topic.name),
                    ) { ok ->
                        if (ok) {
                            fwdSent = true
                        } else {
                            android.widget.Toast.makeText(fCtx, fCtx.getString(R.string.toast_forward_failed), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }

    if (showGifCatalog) {
        // Раунд 138: единая панель ввода - Эмодзи / Гиф / Стикеры в одном
        // пузыре, сверху лента пузырей разделов, следящая за прокруткой.
        val stickerEntries by viewModel.stickerEntries.collectAsStateWithLifecycle()
        val stickerRecents by viewModel.stickerRecents.collectAsStateWithLifecycle()
        val swarmStickers by viewModel.swarmStickers.collectAsStateWithLifecycle()
        InputPanelDialog(
            myGifs = uiState.myGifs,
            swarmGifs = uiState.swarmGifs,
            swarmStatus = uiState.swarmStatus,
            items = uiState.gifItems,
            next = uiState.gifNext,
            loading = uiState.gifLoading,
            error = uiState.gifError,
            notice = uiState.gifNotice,
            onSearch = { viewModel.searchGifs(it) },
            onMore = { viewModel.searchGifs("", more = true) },
            onAttach = { item ->
                showGifCatalog = false
                viewModel.attachGif(item) { }
            },
            onAttachLocal = { entry ->
                showGifCatalog = false
                viewModel.attachLocalGif(entry.sha256)
            },
            onRequestSwarm = { swarm ->
                viewModel.requestSwarmGif(swarm) { showGifCatalog = false }
            },
            onRequestThumbs = { viewModel.requestPeerThumbs(it) },
            onAddOwnGif = { uri -> viewModel.addOwnGif(uri) },
            stickers = stickerEntries,
            stickerRecents = stickerRecents,
            swarmStickers = swarmStickers,
            onSticker = {
                // Раунд 170: выбрал стикер - окно закрывается, видно чат.
                showGifCatalog = false
                viewModel.sendSticker(it)
            },
            onRemoveSticker = { viewModel.removeSticker(it) },
            onRemoveGif = { viewModel.removeOwnGif(it.sha256) },
            onAddSticker = { uri -> viewModel.addSticker(uri) },
            onAddStickerZip = { uri -> viewModel.addStickerZip(uri) },
            onRequestSwarmSticker = { swarm -> viewModel.requestSwarmSticker(swarm) },
            // Раунд 206: пустые плитки сами докачиваются; ↻ - всем держателям.
            onAutoFetchSticker = { viewModel.autoFetchSticker(it) },
            onRetryFetchSticker = { viewModel.retryFetchSticker(it) },
            onEmoji = { emoji -> viewModel.onDraftChanged(uiState.draft + emoji) },
            onOpened = { viewModel.refreshStickers() },
            onDismiss = {
                showGifCatalog = false
                viewModel.closeGifCatalog()
            },
        )
    }
    // В КАНАЛЕ список тем не показываем. Пост и комментарии к нему устроены
    // как тема внутри, но человеку это знать незачем: он открыл комментарии к
    // конкретному посту и должен видеть обычную переписку, а «Назад» обязано
    // вернуть его к ленте постов. Раньше здесь всплывал список «тем» -
    // одинаковые пузыри-оболочки постов, и выйти к ленте можно было только
    // вторым нажатием.
    val hasTopics = !isChannel &&
        uiState.group?.topicsEnabled == true &&
        uiState.topics.isNotEmpty()
    val showTopicsList = hasTopics && !showFeed
    val displayedError = MessagePinPolicy.visibleError(
        uiState.error, uiState.pinned.size, inMessageFeed = !showTopicsList,
    )
    val selectedTopic = uiState.topics.firstOrNull { it.id == uiState.selectedTopicId }
    val selectedTopicName = selectedTopic?.name
    // Внутри темы группы наверху крупно - сама тема (значок и имя), а
    // название группы уходит в подзаголовок: раньше имя темы шло мелким
    // серым «дом · 3 участн.», и было не видно, куда зашёл.
    val topicHeader = !isChannel && hasTopics && showFeed && selectedTopic != null
    // В открытой теме группы или ветке комментариев канала настраиваем паузу
    // именно для неё; в списке тем/ленте канала - для всей группы или канала.
    val muteTopicId = if (
        showFeed && !uiState.selectedTopicId.isNullOrBlank() && (topicHeader || isChannel)
    ) uiState.selectedTopicId else null
    val muteUntilMs = remember(muteRevision, muteTopicId, uiState.group?.mutedUntilMs) {
        if (muteTopicId != null) viewModel.topicMutedUntilMs(muteTopicId)
        else uiState.group?.mutedUntilMs ?: 0L
    }
    val muteTargetName = when {
        muteTopicId != null && isChannel -> "Комментарии к «${selectedTopicName ?: "публикации"}»"
        muteTopicId != null -> "Тема «${selectedTopicName ?: "группы"}»"
        isChannel -> uiState.group?.title ?: stringResource(R.string.contacts_channel)
        else -> uiState.group?.title ?: stringResource(R.string.contacts_group)
    }
    val notificationsMuted = muteUntilMs > System.currentTimeMillis()
    // Системный жест «Назад» (смахивание от края экрана, в т.ч. справа
    // налево) и кнопка «Назад» телефона. Без этого перехватчика Android
    // закрывал весь экран группы, и из темы человек попадал сразу в список
    // групп, минуя список тем (владелец, 2026-09-15). Внутри темы - к списку
    // тем; в списке тем и в группе без тем перехватчик выключен, и жест, как
    // и прежде, закрывает экран.
    BackHandler(enabled = hasTopics && showFeed) { showFeed = false }

    // Подложка на весь экран, в том числе под верхней панелью.
            val feedListState = androidx.compose.foundation.lazy.rememberLazyListState()
            val feedScope = rememberCoroutineScope()
            LaunchedEffect(uiState.selectedTopicId, uiState.messages.size) {
                val jump = viewModel.feedJump.value ?: return@LaunchedEffect
                if (jump.topicId != uiState.selectedTopicId || uiState.messages.isEmpty()) {
                    return@LaunchedEffect
                }
                val offset = if (uiState.moreComments > 0) 1 else 0
                feedListState.scrollToItem(
                    (offset + jump.index).coerceIn(0, offset + uiState.messages.size - 1),
                )
                viewModel.consumeFeedJump()
            }
            // Раунд 150: отправил сообщение - лента доехала до него
            // (раньше оно оставалось за полем ввода, владелец, скрин).
            LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.id) {
                val last = uiState.messages.lastOrNull() ?: return@LaunchedEffect
                if (last.isFromMe) {
                    val offset = if (uiState.moreComments > 0) 1 else 0
                    feedListState.animateScrollToItem(offset + uiState.messages.size - 1)
                }
            }
            val feedRemaining by remember(uiState.messages.size, uiState.moreComments) {
                derivedStateOf {
                    val last = feedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    (uiState.messages.size + (if (uiState.moreComments > 0) 1 else 0) - 1 - last)
                        .coerceAtLeast(0)
                }
            }
            // р250: тап по цитате - лента прыгает к исходному сообщению.
            // Исходного может не быть в загруженной части ветки (большой канал
            // отдаёт комментариями по запросу): тогда честно говорим об этом,
            // а не молча ничего не делаем.
            val quoteCtx = androidx.compose.ui.platform.LocalContext.current
            fun scrollToMessage(messageId: String) {
                val index = uiState.messages.indexOfFirst { it.id == messageId }
                if (index < 0) {
                    android.widget.Toast.makeText(
                        quoteCtx,
                        quoteCtx.getString(R.string.grp_not_loaded),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                val offset = if (uiState.moreComments > 0) 1 else 0
                feedScope.launch { feedListState.animateScrollToItem(offset + index) }
            }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        // Раунд 266: клавиатура не закрывает переписку - сообщения и лента
        // сжимаются над клавиатурой, последнее видно сразу.
        Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    // Права на действия не меняются: тема — только с правом
                    // управления темами, группа/канал — для админов/владельца.
                    val openHeader: (() -> Unit)? = when {
                        topicHeader && selectedTopic != null && uiState.canManageTopics -> ({ showEditTopic = true })
                        !topicHeader && iAmGroupAdmin -> ({ onOpenAdmin(uiState.groupId) })
                        else -> null
                    }
                    ApuHeaderBubble(onClick = openHeader) {
                        // Аватар группы слева от названия, если задан.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val storeAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
                                .collectAsState()
                            val gid = uiState.group?.id.orEmpty()
                            // Через общий кэш: разбор base64 идёт в фоне и
                            // результат переиспользуется. Раньше картинка
                            // раскодировалась ПРЯМО В ОТРИСОВКЕ на главном
                            // потоке - на входе в группу это давало зависание.
                            val bmp = com.vladimir.messenger.ui.components.AvatarBitmaps
                                .rememberAvatar(storeAvatars["g:$gid"])
                            if (bmp != null && !topicHeader) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (topicHeader && selectedTopic != null) {
                                // Открыта тема: её значок вместо аватара группы.
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE8EEF5)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TopicIconView(
                                        selectedTopic.iconEmoji.ifBlank { TopicIconCatalog.DEFAULT },
                                        26.dp,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column {
                            Text(
                                if (topicHeader && selectedTopic != null) {
                                    selectedTopic.name
                                } else {
                                    uiState.group?.title ?: stringResource(R.string.contacts_group)
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E2430),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // В канале это комментарии к посту: показываем
                                // название поста, а не число участников -
                                // человек пришёл из ленты и должен видеть,
                                // под чем он находится.
                                when {
                                    isChannel -> selectedTopicName?.let { stringResource(R.string.topic_comments_dash, it) }
                                        ?: stringResource(R.string.stat_comments)
                                    // В теме: «Тема · группа», участники - на
                                    // списке тем, там они и нужны.
                                    topicHeader -> "Тема · " + (uiState.group?.title ?: stringResource(R.string.contacts_group))
                                    else -> (uiState.group?.memberCount ?: 0).toString() + " участн."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF5A6472),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            }
                        }
                    }
                },
                navigationIcon = {
                    ApuTextAction(
                        label = stringResource(R.string.action_back),
                        onClick = {

                        if (hasTopics && showFeed) showFeed = false else onBackClick()
                    },
                    )
                },
                actions = {
                    IconButton(onClick = { onOpenAdmin(uiState.groupId) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.group_chat_manage))
                    }
                    // Действия группы/канала и уведомлений собраны в «три точки».
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more))
                        }
                        ApuActionsMenu(
                            expanded = showTopMenu,
                            onDismiss = { showTopMenu = false },
                            actions = listOf(
                                ApuAction(
                                    title = if (notificationsMuted) stringResource(R.string.menu_unmute) else stringResource(R.string.menu_mute),
                                    icon = if (notificationsMuted) {
                                        Icons.Default.NotificationsActive
                                    } else {
                                        Icons.Default.NotificationsOff
                                    },
                                    onClick = { showNotificationMuteDialog = true },
                                ),
                                ApuAction(stringResource(R.string.channel_invite_qr), Icons.Filled.QrCode2) {
                                    showQrInvite = true
                                },
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // Смахивание вправо: внутри темы - назад к списку тем,
                // в списке тем и в группе без тем - назад к группам.
                .swipeBack {
                    if (hasTopics && showFeed) showFeed = false else onBackClick()
                },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {

            // ── Левая колонка. В списке тем — значки групп и каналов,
            // а внутри открытой темы — значки тем текущей группы.
            // Если тем нет, колонки тоже нет: просто чат на всю ширину.
            if (hasTopics) {
                if (showTopicsList) {
                    GroupRail(
                        groups = uiState.allGroups,
                        currentGroupId = uiState.groupId,
                        onGroupClick = onSwitchGroup,
                        onChannelClick = onSwitchChannel,
                    )
                } else {
                    TopicRail(
                        topics = uiState.topics,
                        currentTopicId = uiState.selectedTopicId,
                        onTopicClick = { viewModel.selectTopic(it) },
                    )
                }
            }

            // ── Правая часть: список тем пузырями либо лента выбранной темы.
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {

            // Раунд 143: пузырь заявок - на главной группы/канала, в темах
            // и в комментариях; содержимое НЕ перекрывает (оно уезжает вниз).
            if (canDecideRequests && joinRequests.isNotEmpty()) {
                JoinRequestsBanner(
                    count = joinRequests.size,
                    expanded = showRequestsSheet,
                    onClick = { showRequestsSheet = !showRequestsSheet },
                )
            }

            if (displayedError != null) {
                Text(
                    displayedError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (showTopicsList) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.topics, key = { it.id }) { topic ->
                        TopicBubble(topic = topic) {
                            viewModel.selectTopic(topic.id)
                            showFeed = true
                        }
                    }
                    if (uiState.canManageTopics) {
                        item {
                            ApuBubbleCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showNewTopic = true },
                                shape = RoundedCornerShape(18.dp),
                                premium = 6.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = Color(0xFF5A6472),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.group_new_topic), color = Color(0xFF5A6472))
                                }
                            }
                        }
                    }
                }
            } else {

            // ── Закреплённые сообщения
            if (uiState.pinned.isNotEmpty()) {
                // Закрепы у каждой темы свои, поэтому показываем и имя темы.
                val pinnedTopicName = uiState.topics
                    .firstOrNull { it.id == uiState.selectedTopicId }
                    ?.name
                ApuBubbleCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    premium = 6.dp,
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.grp_pinned) +
                                    (pinnedTopicName?.let { " · $it" }.orEmpty()) +
                                    " (" + uiState.pinned.size + "/" +
                                    com.vladimir.messenger.data.local.MessagePinPolicy.MAX_PINNED_PER_SCOPE + ")",
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        // Каждое закреплённое сообщение — своей строкой, и рядом
                        // кнопка «Открепить»: снять закреп можно прямо отсюда,
                        // не разыскивая сообщение в ленте.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 168.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            uiState.pinned.forEach { m ->
                                // Раунд 172: тап по закрепу - лента прыгает к
                                // самому сообщению (просьба владельца).
                                val pinnedIndex = uiState.messages.indexOfFirst { it.id == m.id }
                                val rowModifier = if (pinnedIndex >= 0) {
                                    Modifier.fillMaxWidth().clickable {
                                        feedScope.launch {
                                            feedListState.animateScrollToItem(pinnedIndex)
                                        }
                                    }
                                } else {
                                    Modifier.fillMaxWidth()
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = rowModifier,
                                ) {
                                    // Закреплённый файл (этап 9-10): значок по
                                    // типу и подпись «📎 имя (размер)» - она и
                                    // так в тексте, служебная строка визитки
                                    // отрезается вместе со строками фото.
                                    val pinnedFile = remember(m.content) { GroupFileMarker.parse(m.content) }
                                    if (pinnedFile != null) {
                                        Icon(
                                            fileIconFor(pinnedFile.mediaType),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        // Без служебных строк фото и длинного текста;
                                        // р156: и без гифка-ссылок/стикер-конвертов.
                                        com.vladimir.messenger.util.ChatPreviews.human(
                                            com.vladimir.messenger.util.InlineImage.stripImage(m.content)
                                                .ifBlank { pinnedFile?.let { GroupFileMarker.caption(it) }.orEmpty() }
                                        ) ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (uiState.canPin) {
                                        IconButton(
                                            onClick = { viewModel.togglePin(m.id, false) },
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.channel_unpin),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Панель множественного выбора сообщений (как в современных мессенджерах)
            if (uiState.selectedMessageIds.isNotEmpty()) {
                val selCount = uiState.selectedMessageIds.size
                val selCtx = androidx.compose.ui.platform.LocalContext.current
                ApuBubbleCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    premium = 6.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { viewModel.clearMessageSelection() },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.channel_clear_selection),
                                tint = ApuBubbleTextColor,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.selected_count, selCount),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = ApuBubbleTextColor,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                val combined = uiState.messages
                                    .filter { it.id in uiState.selectedMessageIds }
                                    .joinToString("\n\n") { it.content }
                                val clipboard = selCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(selCtx.getString(R.string.stat_messages), combined))
                                Toast.makeText(selCtx, selCtx.getString(R.string.toast_copied_count, selCount), Toast.LENGTH_SHORT).show()
                                viewModel.clearMessageSelection()
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.channel_copy_selected),
                                tint = ApuBubbleAccentColor,
                            )
                        }
                        IconButton(
                            onClick = { showBulkModeration = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.channel_delete_selected),
                                tint = ApuSettingsDangerColor,
                            )
                        }
                    }
                }
            }

            // ── Лента темы
            // Раунд 144: при входе в тема открывается на первом непрочитанном
            // (или внизу, если всё прочитано), ниже - остальные непрочитанные.
            LazyColumn(
                state = feedListState,
                // Раунд 151: лента сжимается над клавиатурой (edge-to-edge:
                // окно само не сжимается - переписка пряталась за пузырями
                // ввода; запас 200dp снизу отводит место под пузыри).
                modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
                // Раунд 147: снизу запас под оверлей поля ввода.
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Большой канал: комментарии приходят от владельца по запросу,
                // и здесь может быть не вся ветка. Кнопка тянет более ранние.
                if (isChannel && uiState.moreComments > 0) {
                    item(key = "more-comments") {
                        ApuTextAction(
                            label = "Показать ещё " + uiState.moreComments,
                            onClick = { viewModel.loadOlderComments() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(uiState.messages, key = { it.id }) { message ->
                    val card = remember(message.content) { GroupFileMarker.parse(message.content) }
                    val cardState = if (card == null) {
                        null
                    } else {
                        FileCardState.of(
                            chatId = uiState.groupId,
                            info = card,
                            isFromMe = message.isFromMe,
                            transfers = uiState.transfers,
                            pendingKeys = uiState.pendingFiles,
                            receivedFileFor = { viewModel.receivedFileFor(it) },
                            authorCopyFor = { viewModel.authorCopyFor(it) },
                            onDownload = { viewModel.requestFile(message, card) },
                            onSave = { viewModel.requestSaveReceivedFile(it) },
                            onShare = { f -> viewModel.shareFile(f, card.displayName, card.mediaType) },
                            onFavorite = { viewModel.saveFileToFavorites(it) },
                            servedCount = uiState.servedFiles[GroupFileMarker.key(uiState.groupId, card.sha256)] ?: 0,
                        )
                    }
                    // Раунд 166: стикер маленький - получатель ТИХО просит
                    // файл сам, и анимированная картинка появляется без
                    // кнопки «Скачать».
                    val st = cardState
                    val stickerCard = card
                    if (st != null && stickerCard != null && !message.isFromMe &&
                        stickerCard.displayName.startsWith(stringResource(R.string.sv_sticker)) &&
                        st.transfer == null && !st.pending
                    ) {
                        LaunchedEffect(stickerCard.sha256) {
                            viewModel.requestFile(message, stickerCard)
                        }
                    }
                    val resolvedSenderName = senderNames[message.senderId]?.takeIf { it.isNotBlank() }
                        ?: "Участник " + message.senderId.takeLast(4)
                    MessageBubble(
                        message = message,
                        senderName = resolvedSenderName,
                        senderVip = message.senderId.lowercase() in uiState.vipNodeIds,
                        senderAntiCount = uiState.antiRatings[message.senderId] ?: 0,
                        senderAntiWarning = uiState.antiWarnings.containsKey(message.senderId),
                        canModerate = uiState.canModerate,
                        selectionMode = uiState.selectedMessageIds.isNotEmpty(),
                        isSelected = message.id in uiState.selectedMessageIds,
                        onToggleSelect = { viewModel.toggleSelectMessage(message.id) },
                        onOpenAuthorProfile = {
                            viewModel.openPeerProfile(message.senderId, resolvedSenderName)
                        },
                        // Публикацию закрепляют в ленте канала (лично); в
                        // обсуждении разрешаем только снять старый общий pin.
                        canPin = uiState.canPin &&
                            (message.id != channelRootMessageId || message.isPinned),
                        onTogglePin = { viewModel.togglePin(message.id, !message.isPinned) },
                        onSaveToFavorites = {
                            // Раунд 203: в избранное - без служебных строк пересылки.
                            viewModel.saveToFavorites(com.vladimir.messenger.util.ForwardMarker.stripHeader(message.content))
                        },
                        onShareToApu = {
                            // Раунд 203: источник - группа; для чужих сообщений
                            // добавляем автора после названия.
                            val author = senderNames[message.senderId]?.takeIf { it.isNotBlank() }
                            val base = uiState.group?.title ?: "группа"
                            val label = if (!message.isFromMe && !author.isNullOrBlank()) {
                                base + " · автор: " + author
                            } else {
                                base
                            }
                            forwardPayload = com.vladimir.messenger.ui.components.ForwardPayload(
                                message.content,
                                label,
                            )
                        },
                        onOpenForward = { ref ->
                            fwdScope.launch {
                                when (val open = viewModel.resolveForwardTap(ref)) {
                                    is com.vladimir.messenger.util.ForwardMarker.Open.Chat ->
                                        onOpenChat(open.chatId, open.contactName, open.contactId)
                                    is com.vladimir.messenger.util.ForwardMarker.Open.Group ->
                                        onOpenGroup(open.groupId, open.topicId)
                                    is com.vladimir.messenger.util.ForwardMarker.Open.Join ->
                                        onJoinByLink(open.link)
                                    is com.vladimir.messenger.util.ForwardMarker.Open.Missing ->
                                        android.widget.Toast.makeText(
                                            fwdCtx,
                                            open.message,
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        },
                        reactions = uiState.reactions[message.id].orEmpty(),
                        onToggleReaction = { emoji -> viewModel.toggleReaction(message.id, emoji) },
                        onRemoveReaction = { viewModel.removeReaction(message.id) },
                        fileCard = cardState,
                        onEnsureGif = { sha -> viewModel.ensureGifRef(sha) },
                        onDeleteForMe = { viewModel.deleteMessageForMe(message.id) },
                        onDeleteForAll = { deleteForAllTarget = message },
                        // ── р250: ответы и опросы ──
                        onReply = { viewModel.startReply(message) },
                        poll = uiState.polls[message.id],
                        onTogglePollChoice = { index ->
                            uiState.polls[message.id]?.let { viewModel.togglePollChoice(it, index) }
                        },
                        onClosePoll = { uiState.polls[message.id]?.let { viewModel.closePoll(it.pollId) } },
                        onQuoteClick = { targetId -> scrollToMessage(targetId) },
                    )
                }
            }

            // ── Приложенный файл (этап 9): карточка над полем ввода до отправки.
            val staged = uiState.stagedFile
            if (staged != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .apuBubbleSurface()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(fileIconFor(staged.mediaType), contentDescription = null, modifier = Modifier.size(22.dp), tint = ApuBubbleAccentColor)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(staged.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ApuBubbleTextColor)
                        Text(GroupFileMarker.formatSize(staged.sizeBytes), style = MaterialTheme.typography.labelSmall, color = ApuBubbleMutedColor)
                    }
                    IconButton(onClick = { viewModel.clearStagedFile() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.channel_remove_file), modifier = Modifier.size(16.dp), tint = ApuBubbleMutedColor)
                    }
                }
            }

            } // else: лента темы
            } // правая колонка
            } // Row: левая колонка + правая
        }
    }
    // Показ - только ВНУТРИ темы (или в группе без тем): на списке
    // тем поля ввода и «Отправить» нет (владелец, раунд 148).
    if (!showTopicsList) {
    // Раунд 147: поле ввода - оверлей НА ВЕСЬ экран, включая область
    // пузырей тем слева (просьба владельца); imePadding поднимает всё
    // над клавиатурой, «Отправить» всегда видна.
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .imePadding(),
    ) {
            // Раунд 144: сколько сообщений осталось ниже - тап прокручивает вниз.
            if (feedRemaining > 0) {
                val lastIndex = (if (uiState.moreComments > 0) 1 else 0) + uiState.messages.size - 1
                Text(
                    "↓  Ещё " + messagesLabel(feedRemaining),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        // Владелец 2026-10-07: фирменная поверхность и у
                        // «служебных» пилюль ленты — подъём, золотая нить.
                        .apuPremiumLift(5.dp, RoundedCornerShape(16.dp))
                        .apuBubbleSurface(shape = RoundedCornerShape(16.dp))
                        .apuPremiumThread(shape = RoundedCornerShape(16.dp), inset = 14.dp)
                        .clickable {
                            feedScope.launch {
                                feedListState.animateScrollToItem(lastIndex.coerceAtLeast(0))
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            // ── Поле ввода: подложка следует теме и пропускает обои (раунд 45).
            // Раунд 146: пузырь поля - от края до края экрана; «Отправить» -
            // своим пузырём той же ширины под полем (просьба владельца).
            var inputFocused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Раунд 266 + 2026-10-07: фирменный премиальный пузырь.
                        .apuPremiumLift(4.dp)
                        .apuBubbleSurface()
                        .apuPremiumThread(inset = 16.dp)
                        .apuPremiumGloss(intensity = 0.4f, topFraction = 0.55f)
                        .padding(6.dp),
                ) {
                    // р249: «печатает…» в группе - та же строка, что и в личке,
                    // только с именами: в группе печатать могут несколько
                    // человек сразу, и в теме канала индикатор свой у каждой
                    // темы. Состояние мимолётное: гаснет само (TypingPeer).
                    if (uiState.typingMembers.isNotEmpty()) {
                        Text(
                            text = groupTypingLabel(uiState.typingMembers),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                        )
                    }
                    // р250: ответ на сообщение - цитата над полем ввода, чтобы
                    // человек видел, кому и на что отвечает (крестик отменяет).
                    val replyTarget = uiState.replyTo
                    if (replyTarget != null) {
                        val replyAuthor = if (replyTarget.isFromMe) {
                            uiState.me?.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.grp_you)
                        } else {
                            senderNames[replyTarget.senderId]?.takeIf { it.isNotBlank() }
                                ?: ("Участник " + replyTarget.senderId.takeLast(4))
                        }
                        ReplyStrip(
                            author = replyAuthor,
                            text = viewModel.quoteText(replyTarget),
                            onClear = { viewModel.clearReply() },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (inputFocused) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (uiState.canAttach) filePicker.launch(arrayOf("*/*")) else viewModel.onAttachLocked()
                                },
                                enabled = !uiState.isPreparingFile && !uiState.sending,
                                modifier = Modifier.size(40.dp),
                            ) {
                                if (uiState.isPreparingFile) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Filled.AttachFile,
                                        contentDescription = if (uiState.canAttach) stringResource(R.string.chat_attach_file) else stringResource(R.string.grp_attach_unavailable),
                                        tint = if (uiState.canAttach) Color(0xFF5A6472) else Color(0xFF9AA3AF),
                                    )
                                }
                            }
                            ApuTextAction(
                                label = "GIF",
                                onClick = {
                                    if (uiState.canAttach) {
                                        showGifCatalog = true
                                        viewModel.onGifCatalogOpened()
                                        if (uiState.gifItems.isEmpty() && !uiState.gifLoading) {
                                            viewModel.searchGifs("")
                                        }
                                    } else {
                                        viewModel.onAttachLocked()
                                    }
                                },
                                enabled = !uiState.isPreparingFile && !uiState.sending,
                            )
                        }
                    }
                    ApuBubbleField(
                        value = uiState.draft,
                        onValueChange = { viewModel.onDraftChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { inputFocused = it.isFocused },
                        placeholder = { Text(stringResource(R.string.group_message_placeholder)) },
                        minLines = 1,
                        maxLines = 6,
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Раунд 152: активная «Отправить» - золотая заливка и белый
                // текст: сразу видно, что сообщение можно отправить (раньше
                // менялся только оттенок текста - владелец не замечал).
                // р250: пока идёт пауза медленного режима, кнопка показывает
                // остаток и не отправляет - репозиторий бы всё равно отказал,
                // но человеку понятнее видеть счётчик, а не сообщение об ошибке.
                val slowWaitSeconds = (uiState.slowModeWaitMs + 999L) / 1000L
                val canSend = (uiState.draft.isNotBlank() || uiState.stagedFile != null) &&
                    !uiState.sending && !uiState.isPreparingFile && slowWaitSeconds <= 0L
                ApuTextAction(
                    label = if (slowWaitSeconds > 0L) stringResource(R.string.slow_wait_seconds, slowWaitSeconds) else stringResource(R.string.action_send),
                    onClick = {

                        viewModel.send(uiState.draft)
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                )
                // р250: включённый режим объясняем словами - иначе кажется,
                // что приложение «не пускает» без причины.
                if (uiState.slowModeSeconds > 0) {
                    Text(
                        slowModeHint(uiState.slowModeSeconds, uiState.me?.role),
                        style = MaterialTheme.typography.labelSmall,
                        color = ApuBubbleMutedColor,
                        modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                    )
                }
            }

    }

    }
    }

    // Раунд 143: список заявок поверх экрана (как в привычном мессенджере):
    // поиск-пузырь, прокрутка, «Принять в группу» / «Отклонить».
    if (showRequestsSheet && joinRequests.isNotEmpty()) {
        JoinRequestsSheet(
            requests = joinRequests,
            query = requestQuery,
            onQuery = { requestQuery = it },
            onDecide = { nodeId, approve ->
                viewModel.decideJoinRequest(nodeId, approve)
                if (joinRequests.size <= 1) showRequestsSheet = false
            },
            onDismiss = { showRequestsSheet = false },
        )
    }

    if (showNewTopic) {
        NewTopicDialog(
            onDismiss = { showNewTopic = false },
            onCreate = { name, icon ->
                viewModel.createTopic(name, icon)
                showNewTopic = false
            },
        )
    }

    // Раунд 260: тап по значку темы в шапке - смена имени и значка темы.
    if (showEditTopic && selectedTopic != null) {
        val topic = selectedTopic
        NewTopicDialog(
            title = stringResource(R.string.group_edit_topic),
            confirmLabel = stringResource(R.string.admin_save),
            initialName = topic.name,
            initialIcon = topic.iconEmoji.ifBlank { TopicIconCatalog.DEFAULT },
            onDismiss = { showEditTopic = false },
            onCreate = { name, icon ->
                viewModel.updateTopic(topic.id, name, icon)
                showEditTopic = false
            },
        )
    }

    // Раунд 213: QR с короткой ссылкой-приглашением; без APU страница
    // сервиса предлагает установить приложение и вступить/подписаться.
    if (showQrInvite) {
        GroupQrInviteDialog(
            isChannel = uiState.group?.isChannel ?: false,
            link = qrLink,
            loading = qrLoading,
            onDismiss = { showQrInvite = false },
        )
    }

    if (showNotificationMuteDialog) {
        NotificationMuteDialog(
            targetName = muteTargetName,
            mutedUntilMs = muteUntilMs,
            onSelectUntil = { untilMs ->
                if (muteTopicId != null) {
                    viewModel.setTopicNotificationsMutedUntil(muteTopicId, untilMs)
                } else {
                    viewModel.setGroupNotificationsMutedUntil(untilMs)
                }
                showNotificationMuteDialog = false
            },
            onTurnOn = {
                if (muteTopicId != null) {
                    viewModel.setTopicNotificationsMutedUntil(muteTopicId, 0L)
                } else {
                    viewModel.setGroupNotificationsMutedUntil(0L)
                }
                showNotificationMuteDialog = false
            },
            onDismiss = { showNotificationMuteDialog = false },
        )
    }
}

// =============================================================================
// Левая колонка: значки групп и каналов с непрочитанными
// =============================================================================

@Composable
private fun GroupRail(
    groups: List<GroupSummary>,
    currentGroupId: String,
    onGroupClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
) {
    val storeAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
        .collectAsState()
    LazyColumn(
        // Владелец 2026-10-07: колонка значков — тоже часть общего набора:
        // та же подложка, что у строк и панелей, с золотой нитью по кромке.
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .apuBubbleSurface(color = Color(0xFFF5F7FA).copy(alpha = 0.72f))
            .apuPremiumThread(inset = 10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(groups, key = { it.id }) { group ->
            val selected = group.id == currentGroupId
            Box(
                modifier = Modifier.clickable {
                    if (!selected) {
                        if (group.isChannel) onChannelClick(group.id) else onGroupClick(group.id)
                    }
                },
            ) {
                GroupRailAvatar(
                    groupId = group.id,
                    title = group.title,
                    avatarB64 = storeAvatars["g:" + group.id],
                    selected = selected,
                )
                if (group.unreadCount > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        ApuNotificationBadge(group.unreadCount)
                    }
                }
            }
        }
    }
}

/** Круглый аватар группы: картинка из хранилища либо первая буква названия. */
@Composable
private fun GroupRailAvatar(
    groupId: String,
    title: String,
    avatarB64: String?,
    selected: Boolean,
) {
    // Колонка показывает ВСЕ группы разом, поэтому раскодировать картинки в
    // отрисовке нельзя: десяток аватаров подряд подвешивал главный поток на
    // входе в группу и на возврате назад.
    val bmp = com.vladimir.messenger.ui.components.AvatarBitmaps.rememberAvatar(avatarB64)
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (bmp == null) Modifier.background(Color(0xFFE8EEF5)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                title.take(1).uppercase(),
                color = Color(0xFF1E2430),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// =============================================================================
// Левая колонка внутри открытой темы: значки тем текущей группы
// =============================================================================

@Composable
private fun TopicRail(
    topics: List<TopicSummary>,
    currentTopicId: String?,
    onTopicClick: (String) -> Unit,
) {
    LazyColumn(
        // Та же колонка-подложка, что у значков групп: один стиль на обе.
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .apuBubbleSurface(color = Color(0xFFF5F7FA).copy(alpha = 0.72f))
            .apuPremiumThread(inset = 10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(topics, key = { it.id }) { topic ->
            val selected = topic.id == currentTopicId
            Box(
                modifier = Modifier.clickable {
                    if (!selected) onTopicClick(topic.id)
                },
            ) {
                TopicRailIcon(topic = topic, selected = selected)
                if (topic.unreadCount > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        ApuNotificationBadge(topic.unreadCount)
                    }
                }
            }
        }
    }
}

/** Кружок темы: её эмодзи, открытая тема обведена золотым. */
@Composable
private fun TopicRailIcon(topic: TopicSummary, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
            .clip(CircleShape)
            .background(Color(0xFFE8EEF5)),
        contentAlignment = Alignment.Center,
    ) {
        TopicIconView(
            topic.iconEmoji.ifBlank { TopicIconCatalog.DEFAULT },
            30.dp,
        )
    }
}

// =============================================================================
// Тема в своём пузыре: иконка, название, превью, время и непрочитанные
// =============================================================================

/**
 * Раунд 143: пузырь «N заявок на вступление» - виден только владельцу и
 * админам, живёт над содержимым (не перекрывает его) на главной
 * группы/канала, в темах и в комментариях. Нажатие раскрывает список.
 */
@Composable
private fun JoinRequestsBanner(count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // Владелец 2026-10-07: баннер заявок — в том же премиальном слое.
            .apuPremiumLift(5.dp)
            .apuBubbleSurface(color = Color(0xFFF5F7FA).copy(alpha = 0.94f))
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("👥", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            requestsLabel(count) + " на вступление",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (expanded) "▲" else "▼",
            fontSize = 14.sp,
            color = Color(0xFF5A6472),
        )
    }
}

/**
 * Раунд 143: список заявок снизу поверх экрана: поиск-пузырь и
 * прокручиваемые карточки с решениями.
 */
@Composable
private fun JoinRequestsSheet(
    requests: List<com.vladimir.messenger.data.group.JoinRequestSummary>,
    query: String,
    onQuery: (String) -> Unit,
    onDecide: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Затемнение: нажатие вне списка закрывает его.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onDismiss() },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFFF7F9FC))
                .padding(12.dp),
        ) {
        ApuSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = stringResource(R.string.group_search_requests),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val clean = query.trim()
        val filtered = if (clean.isEmpty()) requests else requests.filter {
            it.displayName.contains(clean, ignoreCase = true) ||
                it.note.contains(clean, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            Text(
                if (requests.isEmpty()) stringResource(R.string.group_no_requests) else "Никого не нашли по «" + clean + "»",
                color = Color(0xFF5A6472),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(filtered, key = { it.nodeId }) { request ->
                    JoinRequestRow(request = request, onDecide = onDecide)
                }
            }
        }
        }
    }
}

/** Карточка одной заявки: кто, когда и кнопки решения. */
@Composable
private fun JoinRequestRow(
    request: com.vladimir.messenger.data.group.JoinRequestSummary,
    onDecide: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EEF5)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    request.displayName.trim().take(1).uppercase().ifBlank { "?" },
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF5A6472),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    request.displayName.ifBlank { request.nodeId.take(8) },
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E2430),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "подал(а) заявку " + topicTimeLabel(request.requestedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5A6472),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ApuPremiumContentButton(
                onClick = { onDecide(request.nodeId, true) },
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                Text(stringResource(R.string.group_accept))
            }
            ApuTextAction(label = stringResource(R.string.admin_reject), onClick = { onDecide(request.nodeId, false) })
        }
    }
}

/** «1 заявка / 2 заявки / 28 заявок». */
private fun requestsLabel(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "$count заявка"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count заявки"
    else -> "$count заявок"
}

/** Раунд 143: «1 сообщение / 3 сообщения / 653 сообщения / 5 сообщений». */
private fun messagesLabel(count: Int): String = when {
    count <= 0 -> "Нет сообщений"
    count % 10 == 1 && count % 100 != 11 -> "$count сообщение"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count сообщения"
    else -> "$count сообщений"
}

@Composable
private fun TopicBubble(topic: TopicSummary, onClick: () -> Unit) {
    ApuBubbleCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        premium = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EEF5)),
                contentAlignment = Alignment.Center,
            ) {
                TopicIconView(
                    topic.iconEmoji.ifBlank { TopicIconCatalog.DEFAULT },
                    30.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        topic.name,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E2430),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (topic.isClosed) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.group_topic_closed),
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFF5A6472),
                        )
                    }
                }
                // Раунд 143: счётчик сообщений под названием темы (как в
                // привычном мессенджере: «653 сообщения»).
                Text(
                    messagesLabel(topic.messageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5A6472),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!topic.lastMessagePreview.isNullOrBlank()) {
                    Text(
                        // Раунд 155: без служебных строк (гифки/стикеры).
                        com.vladimir.messenger.util.ChatPreviews.human(topic.lastMessagePreview)
                            ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A93A2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // Раунд 184 (аудит-5): кэш вместо нового SimpleDateFormat
                    // на каждую перерисовку строки темы.
                    remember(topic.lastMessageAtMs, System.currentTimeMillis() / 3_600_000L) {
                        topicTimeLabel(topic.lastMessageAtMs)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5A6472),
                )
                if (topic.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    ApuNotificationBadge(topic.unreadCount)
                }
            }
        }
    }
}

/** Как в мессенджерах: сегодня — время, неделя — день недели, дальше — дата. */
private fun topicTimeLabel(ms: Long?): String {
    if (ms == null || ms <= 0L) return ""
    val now = System.currentTimeMillis()
    return when {
        isSameDay(ms, now) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        now - ms < 7L * 86_400_000L -> SimpleDateFormat("EEE", Locale("ru")).format(Date(ms))
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ms))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    // Картинки и гифки в темах показываем так же, как в личном чате.
    message: MessageEntity,
    senderName: String,
    /**
     * Автор сообщения — элита: у аватарки объёмное золотое кольцо. Ранг он
     * сообщил сам конвертом APURANK1, из базы его взять неоткуда.
     */
    senderVip: Boolean = false,
    senderAntiCount: Int = 0,
    senderAntiWarning: Boolean = false,
    canModerate: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onOpenAuthorProfile: () -> Unit = {},
    canPin: Boolean,
    onTogglePin: () -> Unit,
    onSaveToFavorites: () -> Unit = {},
    /** Раунд 203: «Поделиться в APU» - переслать сообщение с источником. */
    onShareToApu: () -> Unit = {},
    /** Раунд 203: тап по шапке-источнику пересылки. */
    onOpenForward: ((com.vladimir.messenger.util.ForwardMarker.Ref) -> Unit)? = null,
    reactions: List<com.vladimir.messenger.data.reaction.ReactionSummary> = emptyList(),
    onToggleReaction: (String) -> Unit = {},
    onRemoveReaction: () -> Unit = {},
    /** Файл, приложенный к сообщению (этап 9), и ход его приёма/раздачи. */
    fileCard: FileCardState? = null,
    /** Раунд 130: карточка-ссылка просит VM тихо подтянуть байты гифки. */
    onEnsureGif: (String) -> Unit = {},
    /** Раунд 135: удалить сообщение - у себя или у всех. */
    onDeleteForMe: () -> Unit = {},
    onDeleteForAll: () -> Unit = {},
    /** р250: «Ответить» - цитата этого сообщения встанет над полем ввода. */
    onReply: () -> Unit = {},
    /** р250: опрос, приклеенный к сообщению (посту); null - опроса нет. */
    poll: com.vladimir.messenger.data.group.PollSummary? = null,
    onTogglePollChoice: (Int) -> Unit = {},
    onClosePoll: () -> Unit = {},
    /** р250: тап по цитате - лента прыгает к исходному сообщению. */
    onQuoteClick: (String) -> Unit = {},
) {
    // Долгое нажатие - «В избранное» и «Реакция»: у сообщения темы нет своего
    // меню, а отдельная кнопка у каждого пузыря засорила бы ленту.
    var showMenu by remember { mutableStateOf(false) }
    var showReactions by remember { mutableStateOf(false) }
    // Раунд 212: буфер обмена для «Копировать текст» в меню точек.
    val context = androidx.compose.ui.platform.LocalContext.current
    // Раунд 211: «три точки» на самом пузыре (владелец: и на текстовых тоже).
    // Не рисуем там, где точки уже есть у вложения: файл-карточка (р168)
    // и гифка-ссылка (р210).
    val bubbleHasOwnDots = fileCard != null || remember(message.content) {
        com.vladimir.messenger.data.gif.GifLibrary.isGifRef(
            com.vladimir.messenger.util.ForwardMarker.stripHeader(
                com.vladimir.messenger.util.InlineImage.stripImage(message.content)
            )
        )
    }
    val time = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start,
    ) {
        if (selectionMode) {
            ApuCircleCheckIndicator(
                checked = isSelected,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 6.dp)
                    .clickable { onToggleSelect() },
            )
        }
        // Раунд 211: точки рядом с пузырём - у своего слева, у чужого справа.
        if (!bubbleHasOwnDots && message.isFromMe) {
            ApuMenuDots(
                onClick = { showMenu = true },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(2.dp))
        }
        // Кнопке «Закрепить» справа нужно своё место: пузырь с карточкой
        // файла растягивается на все 300 dp, и на узком экране (лента рядом
        // с колонкой тем) кнопка выдавливалась за край - файл нельзя было
        // закрепить. Вес без заполнения: пузырь занимает не больше остатка.
        Box(modifier = if (canPin) Modifier.weight(1f, fill = false) else Modifier) {
        // Раунд 169: стикер с копией на телефоне парит в чате - пузырь
        // без фона и тени, только картинка, время и реакции.
        val stickerFloat = fileCard != null && fileCard.previewFile != null &&
            GroupFileMarker.isSticker(fileCard.info)
        val messenger = LocalMessengerColors.current
        val bubbleTextColor = if (stickerFloat) MaterialTheme.colorScheme.onBackground
            else if (message.isFromMe) messenger.messageBubbleOwnText else messenger.messageBubbleOtherText
        ApuBubbleCard(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .swipeToReply { onReply() }
                .combinedClickable(
                    // В режиме выбора клик отмечает сообщение; иначе - реакции / меню действий.
                    onClick = { if (selectionMode) onToggleSelect() else showReactions = true },
                    onLongClick = { showMenu = true },
                ),
            backgroundColor = if (message.isFromMe) messenger.messageBubbleOwn else messenger.messageBubbleOther,
            contentColor = bubbleTextColor,
            transparent = stickerFloat,
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (!message.isFromMe) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable { onOpenAuthorProfile() },
                    ) {
                        // Аватарка автора: у элиты — в объёмном золотом кольце.
                        // В группе это единственное место, где видно лицо
                        // человека, поэтому знак элиты стоит именно здесь.
                        val senderAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
                            .collectAsState()
                        PeerAvatar(
                            name = senderName,
                            avatarB64 = senderAvatars[message.senderId],
                            vip = senderVip,
                            size = 24.dp,
                        )
                        Text(
                            senderName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ApuAntiRatingInlineBadge(
                            antiCount = senderAntiCount,
                            isWarning = senderAntiWarning,
                            onClick = onOpenAuthorProfile,
                        )
                    }
                }
                // Раунд 203: шапка-источник пересылки - кликабельная; служебные
                // строки (маркер + старая шапка) из тела убираем.
                val fwdRef = remember(message.content) { com.vladimir.messenger.util.ForwardMarker.parseRef(message.content) }
                val showFwdHeader = remember(message.content) { com.vladimir.messenger.util.ForwardMarker.hasHeader(message.content) }
                if (showFwdHeader) {
                    val fwdLabel = fwdRef?.label ?: com.vladimir.messenger.util.ForwardMarker.plainHeaderLabel(message.content)
                    Row(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = fwdRef != null && onOpenForward != null) {
                                if (fwdRef != null && onOpenForward != null) onOpenForward(fwdRef)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "↩ Переслано из «" + fwdLabel + "»",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline,
                            color = bubbleTextColor,
                        )
                    }
                }
                // р250: цитата ответа - над текстом, как в привычном
                // мессенджере: тап по ней прокручивает ленту к исходному
                // сообщению (если оно в загруженной части ветки).
                val quoteId = message.replyToId
                if (!quoteId.isNullOrBlank()) {
                    QuoteBlock(
                        author = message.replyAuthor,
                        text = message.replyText,
                        color = bubbleTextColor,
                        onClick = { onQuoteClick(quoteId) },
                    )
                }
                // Вложенная картинка едет отдельной служебной строкой внутри
                // текста. Раньше её печатали как есть, и в комментариях под
                // постом вместо снимка тянулись экраны «букв».
                val attachedB64 = remember(message.content) {
                    com.vladimir.messenger.util.InlineImage.extractB64(message.content)
                }
                val bodyText = remember(message.content, fileCard?.info) {
                    val words = com.vladimir.messenger.util.InlineImage.stripImage(message.content)
                    // Раунд 203: маркер и шапку пересылки рисуем отдельно.
                    val clean = com.vladimir.messenger.util.ForwardMarker.stripHeader(words)
                    // Подпись «📎 имя (размер)» - для старых версий; здесь её
                    // заменяет карточка файла.
                    if (fileCard != null) GroupFileMarker.stripCaption(clean, fileCard.info) else clean
                }
                val attachedBitmap = com.vladimir.messenger.ui.components.AvatarBitmaps
                    .rememberAvatar(attachedB64)
                val imageUrl = remember(bodyText) {
                    ImageLinkDetector.directImageUrl(bodyText)
                }
                // Нажатие на картинку - на весь экран с увеличением.
                var showFullImage by remember(message.id) { mutableStateOf(false) }
                if (showFullImage && attachedB64 != null) {
                    com.vladimir.messenger.ui.components.PhotoViewer(
                        photos = listOf(com.vladimir.messenger.ui.components.PhotoSource.Encoded(attachedB64)),
                        onDismiss = { showFullImage = false },
                    )
                }
                when {
                    // Раунд 130: ССЫЛКА на гифку - карточка с анимацией;
                    // байты каждый телефон тихо тянет с хранителей сети.
                    // Раунд 203: гифку узнаём в bodyText - он без маркера
                    // и шапки пересылки.
                    com.vladimir.messenger.data.gif.GifLibrary.isGifRef(bodyText) -> {
                        com.vladimir.messenger.ui.components.GifRefCard(
                            content = bodyText,
                            onEnsure = onEnsureGif,
                            // Раунд 210: точки на гифке - то же меню пузыря
                            // (реакция/избранное/закрепить), что по удержанию.
                            onMenu = { showMenu = true },
                        )
                    }
                    attachedBitmap != null -> {
                        androidx.compose.foundation.Image(
                            bitmap = attachedBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.group_message_image),
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clickable { showFullImage = true },
                        )
                        if (bodyText.isNotBlank()) {
                            Text(bodyText, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    imageUrl != null -> ImagePreview(
                        model = imageUrl,
                        contentDescription = stringResource(R.string.group_message_image),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    )
                    // Пост из одних фотографий: сами фото - в ленте канала, а
                    // здесь, над комментариями, пузырь не должен быть пустым.
                    bodyText.isBlank() &&
                        com.vladimir.messenger.util.InlineImage.photoCount(message.content) > 0 ->
                        Text(
                            "Фото: " + com.vladimir.messenger.util.InlineImage.photoCount(message.content),
                            color = bubbleTextColor.copy(alpha = 0.7f),
                        )
                    bodyText.isBlank() && fileCard != null -> Unit
                    else -> Text(bodyText)
                }
                if (fileCard != null) {
                    // Долгое нажатие на самой картинке = меню пузыря
                    // (реакции/закрепить): раньше область карточки
                    // «проглатывала» жест, и реакцию поставить не выходило.
                    GroupFileCard(
                        state = fileCard,
                        isFromMe = message.isFromMe,
                        onLongPress = { showMenu = true },
                        // Раунд 211: в точках карточки - весь функционал
                        // сообщения, как у текстовых пузырей.
                        messageActions = buildList {
                            add(ApuAction(stringResource(R.string.channel_share_in_apu), Icons.Filled.Send) { onShareToApu() })
                            // р250: ответ - и в меню карточки файла (точки
                            // на вложении ведут в то же меню, что и пузырь).
                            add(ApuAction(stringResource(R.string.group_reply), Icons.AutoMirrored.Filled.Reply) { onReply() })
                            add(ApuAction(stringResource(R.string.channel_add_reaction), Icons.Filled.EmojiEmotions) { showReactions = true })
                            add(ApuAction(stringResource(R.string.channel_to_favorites), Icons.Filled.Star) { onSaveToFavorites() })
                            // Раунд 212 (аудит: в личке «Копировать всё» есть,
                            // тут не было) - текст сообщения в буфер обмена.
                            add(ApuAction(stringResource(R.string.channel_copy_text), Icons.Filled.ContentCopy) {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(context.getString(R.string.group_message_placeholder), message.content)
                                )
                                Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                            })
                            if (!message.isFromMe) {
                                add(ApuAction(stringResource(R.string.channel_profile_reputation), Icons.Filled.Person) { onOpenAuthorProfile() })
                            }
                            add(
                                ApuAction(
                                    if (isSelected) stringResource(R.string.channel_deselect) else stringResource(R.string.channel_select_many),
                                    Icons.Filled.CheckCircle,
                                ) { onToggleSelect() }
                            )
                            if (canPin) {
                                add(
                                    ApuAction(
                                        if (message.isPinned) stringResource(R.string.channel_unpin) else stringResource(R.string.channel_pin),
                                        Icons.Filled.PushPin,
                                    ) { onTogglePin() }
                                )
                            }
                            if (canModerate || message.isFromMe) {
                                add(
                                    ApuAction(
                                        if (canModerate && !message.isFromMe) stringResource(R.string.channel_delete_moderate) else stringResource(R.string.channel_delete_ellipsis),
                                        Icons.Filled.Delete,
                                        destructive = true,
                                    ) { onDeleteForAll() }
                                )
                            }
                            add(ApuAction(stringResource(R.string.group_delete_for_me), Icons.Filled.Delete, destructive = true) { onDeleteForMe() })
                        },
                    )
                }
                // р250: опрос показывается под текстом сообщения (поста) - тем
                // же пузырём, что и сама карточка файла.
                if (poll != null) {
                    ApuPollCard(
                        poll = poll,
                        onToggle = onTogglePollChoice,
                        onClose = onClosePoll,
                        canClose = canModerate || poll.isMine,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = bubbleTextColor.copy(alpha = 0.65f))
                    if (message.isPinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.group_pinned), modifier = Modifier.size(12.dp))
                    }
                }
                com.vladimir.messenger.ui.components.ReactionRow(
                    reactions = reactions,
                    onToggle = { showReactions = true },
                )
            }
        }
        // Раунд 211: меню пузыря - золотыми пузырями (общий стиль точек).
        ApuActionsMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            actions = buildList {
                add(ApuAction(stringResource(R.string.channel_share_in_apu), Icons.Filled.Send) { onShareToApu() })
                // р250: ответ - первым пунктом, как в привычных мессенджерах.
                add(ApuAction(stringResource(R.string.group_reply), Icons.AutoMirrored.Filled.Reply) { onReply() })
                add(ApuAction(stringResource(R.string.channel_add_reaction), Icons.Filled.EmojiEmotions) { showReactions = true })
                add(ApuAction(stringResource(R.string.channel_to_favorites), Icons.Filled.Star) { onSaveToFavorites() })
                // Раунд 212: копирование - как в личном чате («Копировать всё»).
                add(ApuAction(stringResource(R.string.channel_copy_text), Icons.Filled.ContentCopy) {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(context.getString(R.string.group_message_placeholder), message.content)
                    )
                    Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                })
                if (!message.isFromMe) {
                    add(ApuAction(stringResource(R.string.channel_profile_reputation), Icons.Filled.Person) { onOpenAuthorProfile() })
                }
                add(
                    ApuAction(
                        if (isSelected) stringResource(R.string.channel_deselect) else stringResource(R.string.channel_select_many),
                        Icons.Filled.CheckCircle,
                    ) { onToggleSelect() }
                )
                // Закреп и из меню - на случай, если кнопка
                // справа не поместилась или её не заметили.
                if (canPin) {
                    add(
                        ApuAction(
                            if (message.isPinned) stringResource(R.string.channel_unpin) else stringResource(R.string.channel_pin),
                            Icons.Filled.PushPin,
                        ) { onTogglePin() }
                    )
                }
                // Удаление и модерация: доступно автору, администраторам и владельцу.
                if (canModerate || message.isFromMe) {
                    add(
                        ApuAction(
                            if (canModerate && !message.isFromMe) stringResource(R.string.channel_delete_moderate) else stringResource(R.string.channel_delete_ellipsis),
                            Icons.Filled.Delete,
                            destructive = true,
                        ) { onDeleteForAll() }
                    )
                }
                add(ApuAction(stringResource(R.string.group_delete_for_me), Icons.Filled.Delete, destructive = true) { onDeleteForMe() })
            },
        )
        if (showReactions) {
            val mine = reactions.firstOrNull { it.mine }?.emoji
            com.vladimir.messenger.ui.components.ReactionPickerDialog(
                onDismiss = { showReactions = false },
                myEmoji = mine,
                onRemove = {
                    showReactions = false
                    onRemoveReaction()
                },
                onPick = { emoji ->
                    showReactions = false
                    onToggleReaction(emoji)
                },
            )
        }
        }
        if (!bubbleHasOwnDots && !message.isFromMe) {
            Spacer(Modifier.width(2.dp))
            ApuMenuDots(
                onClick = { showMenu = true },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (canPin) {
            IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = if (message.isPinned) stringResource(R.string.channel_unpin) else stringResource(R.string.channel_pin),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun NewTopicDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    // Раунд 260: тот же диалог работает и как «Редактирование темы».
    title: String = stringResource(R.string.group_new_topic),
    confirmLabel: String = stringResource(R.string.chat_create),
    initialName: String = "",
    initialIcon: String = TopicIconCatalog.DEFAULT,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    // Значки тем: по умолчанию эмодзи - сетка с поиском; фирменные живые
    // значки - второй таб.
    var emojiMode by remember { mutableStateOf(true) }
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ApuBubbleField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.group_topic_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Раунд 258: превью значка «подпрыгивает» при смене, как в Telegram.
                val iconPop = remember { androidx.compose.animation.core.Animatable(1f) }
                androidx.compose.runtime.LaunchedEffect(icon) {
                    iconPop.snapTo(1.25f)
                    iconPop.animateTo(
                        1f,
                        androidx.compose.animation.core.spring(
                            dampingRatio = 0.5f,
                            stiffness = 300f,
                        ),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconPop.value
                            scaleY = iconPop.value
                        },
                    ) {
                        TopicIconView(icon, 34.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Значок темы: " + TopicIconCatalog.describe(icon),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF5A6472),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Раунд 258: без упоминаний чужих мессенджеров в интерфейсе.
                    listOf(stringResource(R.string.input_emoji) to true, stringResource(R.string.grp_animated) to false)
                        .forEach { (label, mode) ->
                            val sel = emojiMode == mode
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) Color.White else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (sel) MaterialTheme.colorScheme.primary
                                        else Color(0xFFE8EEF5),
                                    )
                                    .clickable {
                                        if (emojiMode != mode) {
                                            emojiMode = mode
                                            // При переключении - дефолт своего каталога,
                                            // чтобы сетка подсвечивала выбор.
                                            icon = if (mode) TopicEmojiCatalog.EMOJIS.first()
                                            else TopicIconCatalog.DEFAULT
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                }
                if (emojiMode) {
                    TopicEmojiPicker(selected = icon, onPick = { icon = it })
                } else {
                    // Живые значки уже анимированы прямо в сетке выбора; сетка
                    // ленивая, чтобы 105 анимаций не тормозили телефон.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        gridItems(TopicIconCatalog.kinds, key = { it }) { kind ->
                            val selected = kind == icon
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFE8EEF5))
                                    .then(
                                        if (selected) {
                                            Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(12.dp),
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { icon = kind },
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedTopicIcon(kind, Modifier.size(36.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(
                label = confirmLabel,
                onClick = { onCreate(name, icon) },
                enabled = name.isNotBlank(),
            )
        },
        dismissButton = { ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss) },
    )
}

/** р249: подпись индикатора «печатает…» в группе (1, 2 или больше человек). */
private fun groupTypingLabel(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "${names[0]} печатает…"
    2 -> "${names[0]} и ${names[1]} печатают…"
    else -> "${names[0]}, ${names[1]} и ещё ${names.size - 2} печатают…"
}

// =============================================================================
// р250: ответы на сообщения, опросы и медленный режим
// =============================================================================

/**
 * Цитата ответа внутри пузыря: цветная полоса слева, имя автора и текст.
 *
 * Текст цитаты хранится рядом с сообщением (колонки `replyAuthor`/`replyText`),
 * поэтому цитата рисуется даже тогда, когда исходное сообщение удалено или не
 * доехало до этого телефона. Тап прокручивает ленту к исходному сообщению.
 */
@Composable
private fun QuoteBlock(
    author: String,
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                author.ifBlank { stringResource(R.string.group_message_placeholder) },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text.ifBlank { stringResource(R.string.grp_attachment) },
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Цитата над полем ввода: видно, на что отвечаем, и можно отменить ответ. */
@Composable
private fun ReplyStrip(
    author: String,
    text: String,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.reply_to_author, author),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text.ifBlank { stringResource(R.string.grp_attachment) },
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.group_cancel_reply),
                modifier = Modifier.size(16.dp),
                tint = ApuBubbleMutedColor,
            )
        }
    }
}

/**
 * Подпись под полем ввода, когда в группе включён медленный режим: человек
 * должен понимать, почему «Отправить» не нажимается.
 */
private fun slowModeHint(seconds: Int, role: String?): String {
    val label = when (seconds) {
        10 -> "10 секунд"
        30 -> "30 секунд"
        60 -> "1 минуту"
        300 -> "5 минут"
        900 -> "15 минут"
        else -> "$seconds с"
    }
    return if (com.vladimir.messenger.data.group.GroupRole.isAdminOrOwner(role.orEmpty())) {
        "Медленный режим: участники пишут не чаще одного сообщения в $label (администраторы - без паузы)"
    } else {
        "Медленный режим: не чаще одного сообщения в $label"
    }
}
