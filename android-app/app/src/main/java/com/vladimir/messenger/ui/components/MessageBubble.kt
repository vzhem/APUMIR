package com.vladimir.messenger.ui.components

import com.vladimir.messenger.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.produceState
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.data.link.ShortLinks
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.ui.theme.LocalMessengerColors
import com.vladimir.messenger.util.ImageLinkDetector
import java.text.SimpleDateFormat
import java.util.*

private val OwnBubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
private val OtherBubbleShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

private val URL_REGEX = Regex(
    """(https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+)|(www\.[\w\-._~:/?#\[\]@!$&'()*+,;=%]+)"""
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    linkColor: Color = ApuBubbleLinkColor,
    onTap: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    /** Раунд 176: тап по «Добавить контакт» в карточке-приглашении. */
    onContactInvite: ((inviteLink: String) -> Unit)? = null,
    /** Раунд 189: тап по «Вступить»/«Подписаться» в карточке группы/канала. */
    onGroupInvite: ((link: String) -> Unit)? = null,
    /** Раунд 203: тап по шапке-источнику пересылки. */
    onOpenForward: ((com.vladimir.messenger.util.ForwardMarker.Ref) -> Unit)? = null,
    /**
     * Раунд 211: «три точки» сообщения - то же меню, что по удержанию.
     * Владелец: точки должны быть и на текстовых пузырях. null - не рисовать.
     */
    onMenu: (() -> Unit)? = null,
    /** Явно повторить недоставленное исходящее сообщение. */
    onRetry: (() -> Unit)? = null,
) {
    val isOwn = message.isFromMe
    val context = LocalContext.current
    val messenger = LocalMessengerColors.current
    val textColor = if (isOwn) messenger.messageBubbleOwnText else messenger.messageBubbleOtherText

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            // Свайп ловим по всей строке, а не только по пузырю: короткое
            // сообщение из одного знака иначе почти не захватить.
            .swipeToReply(enabled = onReply != null) { onReply?.invoke() },
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
    ) {
        // Раунд 211: «три точки» рядом с пузырём (те же, что у картинок и
        // гифок) - открывают то же меню действий, что и удержание пальца.
        if (onMenu != null && isOwn) {
            ApuMenuDots(
                onClick = { onMenu?.invoke() },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(3.dp))
        }
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = 280.dp)
                .apuBubbleSurface(
                    color = if (isOwn) messenger.messageBubbleOwn else messenger.messageBubbleOther,
                    shape = if (isOwn) OwnBubbleShape else OtherBubbleShape,
                )
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { onLongClick?.invoke() }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                // Раунд 203: шапка-источник пересылки - кликабельная, служебные
                // строки (маркер + старая шапка) из тела убираем.
                val fwdRef = remember(message.content) { com.vladimir.messenger.util.ForwardMarker.parseRef(message.content) }
                val showFwdHeader = remember(message.content) { com.vladimir.messenger.util.ForwardMarker.hasHeader(message.content) }
                val displayContent = remember(message.content) { com.vladimir.messenger.util.ForwardMarker.stripHeader(message.content) }
                if (!message.replyToId.isNullOrBlank()) {
                    MessageQuoteBlock(
                        author = message.replyAuthor,
                        text = message.replyText,
                        color = textColor,
                        onClick = {},
                    )
                }
                if (showFwdHeader) {
                    val fwdLabel = fwdRef?.label ?: com.vladimir.messenger.util.ForwardMarker.plainHeaderLabel(message.content)
                    Row(
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = fwdRef != null && onOpenForward != null) {
                                if (fwdRef != null && onOpenForward != null) onOpenForward(fwdRef)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(R.string.fwd_from_label, fwdLabel),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline,
                            color = textColor,
                        )
                    }
                }
                // Перевод входящего текста на язык приложения (если включён в настройках).
                // Свои сообщения, карточки и картинки не переводим.
                val translateOn by com.vladimir.messenger.data.translate.TranslationSettings.enabled
                    .collectAsStateWithLifecycle()
                val appLang by com.vladimir.messenger.ui.i18n.AppLanguageHolder.language
                    .collectAsStateWithLifecycle()
                var showOriginal by remember(message.id) { mutableStateOf(false) }
                // Ручной перевод одного сообщения кнопкой «Перевести», если авто-перевод выключен.
                var manualRequested by remember(message.id) { mutableStateOf(false) }
                val translation by produceState<com.vladimir.messenger.data.translate.MessageTranslation?>(
                    initialValue = null,
                    message.id, displayContent, translateOn, appLang, manualRequested,
                ) {
                    value = null
                    if ((translateOn || manualRequested) && !message.isFromMe &&
                        com.vladimir.messenger.data.translate.MessageTranslator.isTranslatableText(displayContent)
                    ) {
                        value = com.vladimir.messenger.data.translate.MessageTranslator
                            .translateForeign(displayContent, appLang.code)
                    }
                }
                val shownTranslation = translation
                val shownContent = if (shownTranslation != null && !showOriginal) shownTranslation.text else displayContent
                val annotatedText = remember(shownContent, linkColor) {
                    buildAnnotatedMessageText(shownContent, linkColor)
                }
                // Сообщение из одной ссылки на картинку или гифку показываем
                // картинкой: клавиатура вставляет гифки именно ссылкой, и в чате
                // вместо картинки висел текст.
                val imageUrl = remember(displayContent) {
                    ImageLinkDetector.directImageUrl(displayContent)
                }
                // Раунд 176: приглашение «поделиться контактом» рисуем карточкой:
                // имя и ник человека, ниже золотой пузырь «Добавить контакт» -
                // apu://-ссылка в тексте раньше была некликабельна.
                val inviteCard = remember(displayContent) {
                    com.vladimir.messenger.util.ContactCardSender.parseCard(displayContent)
                }

                // Раунд 189: приглашение в группу/канал - тоже карточкой.
                val groupCard = remember(displayContent) {
                    com.vladimir.messenger.util.GroupInviteCardSender.parseCard(displayContent)
                }

                // Раунд 218: мульти-приглашение (несколько сообществ сразу
                // плюс «Скачать APU») - карточкой со списком и кнопками.
                // Сырые ссылки с адресом сервиса из пузыря больше не видны.
                val multiCard = remember(displayContent) {
                    com.vladimir.messenger.util.GroupInviteCardSender.parseMultiCard(displayContent)
                }

                if (multiCard != null && onGroupInvite != null) {
                    MultiInviteCardView(
                        card = multiCard,
                        textColor = textColor,
                        onOpen = onGroupInvite,
                        onDownload = { url -> openInstaller(context, url) },
                    )
                } else if (inviteCard != null && onContactInvite != null) {
                    ContactInviteCardView(
                        card = inviteCard,
                        textColor = textColor,
                        onAdd = { onContactInvite(inviteCard.inviteLink) },
                    )
                } else if (groupCard != null && onGroupInvite != null) {
                    GroupInviteCardView(
                        card = groupCard,
                        textColor = textColor,
                        onJoin = { onGroupInvite(groupCard.link) },
                        onDownload = { url -> openInstaller(context, url) },
                    )
                } else if (imageUrl != null) {
                    ImagePreview(
                        model = imageUrl,
                        contentDescription = stringResource(R.string.group_message_image),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else if (isSelected) {
                    // Режим выделения текста
                    SelectionContainer {
                        Text(
                            text = annotatedText,
                            style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                        )
                    }
                } else {
                    // Обычный режим. Раньше здесь стоял ClickableText, и он
                    // ПОГЛОЩАЛ нажатие: до пузыря оно не доходило, сообщение не
                    // выделялось, а значит не появлялись ни выделение текста,
                    // ни окно с «Копировать» и «В избранное». Ссылку по-прежнему
                    // открываем по нажатию на неё, но всё остальное нажатие
                    // теперь честно передаём пузырю.
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                        onTextLayout = { layout = it },
                        modifier = Modifier.pointerInput(annotatedText) {
                            detectTapGestures(
                                onLongPress = { onLongClick?.invoke() },
                                onTap = { position ->
                                    val offset = layout?.getOffsetForPosition(position)
                                    val url = offset?.let {
                                        annotatedText.getStringAnnotations("URL", it, it)
                                            .firstOrNull()
                                            ?.item
                                    }
                                    if (url == null) {
                                        // Нажали мимо ссылки - обычное нажатие
                                        // по сообщению: выделить и показать действия.
                                        onTap()
                                    } else {
                                        try {
                                            val uri = if (url.startsWith("http")) Uri.parse(url)
                                            else Uri.parse("https://" + url)
                                            val open = Intent(Intent.ACTION_VIEW, uri)
                                            // Пересланная ссылка на канал или пост
                                            // ведёт на наш же сервис. Из своего чата
                                            // открываем её сразу в APU, а не через
                                            // браузер с вопросом «чем открыть».
                                            if (GroupInviteLinks.parseTarget(url) != null ||
                                                ShortLinks.isShortLink(url)
                                            ) {
                                                open.setPackage(context.packageName)
                                            }
                                            context.startActivity(open)
                                            android.util.Log.i("MessageBubble", "Opening URL: " + url)
                                        } catch (e: Exception) {
                                            android.util.Log.e("MessageBubble", "Failed to open URL: " + url, e)
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
                // Карточка первой ссылки под текстом (как в премиальных мессенджерах).
                // Приглашения и картинки уже показаны своими карточками - их не дублируем.
                val firstLink = remember(displayContent) { URL_REGEX.find(displayContent)?.value }
                if (firstLink != null && imageUrl == null && multiCard == null &&
                    inviteCard == null && groupCard == null && !isSelected
                ) {
                    ApuLinkCard(url = firstLink, textColor = textColor)
                }
                // Переключатель оригинала: виден только когда перевод есть.
                if (translation != null) {
                    Text(
                        text = if (showOriginal) stringResource(R.string.chat_show_translation)
                               else stringResource(R.string.chat_show_original),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { showOriginal = !showOriginal },
                    )
                }
                // Кнопка «Перевести» для входящего текста, когда авто-перевод выключен.
                if (translation == null && !translateOn && !manualRequested && !message.isFromMe &&
                    com.vladimir.messenger.data.translate.MessageTranslator.isTranslatableText(displayContent)
                ) {
                    Text(
                        text = stringResource(R.string.chat_translate_now),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { manualRequested = true },
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    val yesterdayLabel = stringResource(R.string.msg_yesterday)
                    Text(
                        // Раунд 184 (аудит-5): SimpleDateFormat+Calendar создавались
                        // заново на каждую перерисовку каждого пузыря. remember
                        // кэширует результат; часовой маркер - «Вчера» обновится.
                        text = remember(message.timestamp, yesterdayLabel, System.currentTimeMillis() / 3_600_000L) {
                            formatMessageTime(message.timestamp, yesterdayLabel)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOwn) messenger.messageBubbleOwnText.copy(alpha = 0.7f)
                                else messenger.messageBubbleOtherText.copy(alpha = 0.6f),
                    )
                    if (isOwn) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status, tint = messenger.messageBubbleOwnText)
                    }
                }
                if (
                    isOwn && onRetry != null && message.status in setOf(
                        MessageStatus.PENDING,
                        MessageStatus.QUEUED_OFFLINE,
                        MessageStatus.SENT,
                        MessageStatus.FAILED,
                        MessageStatus.LOCAL_FILE,
                        MessageStatus.FILE_EXPIRED,
                    )
                ) {
                    ApuPremiumChip(
                        label = stringResource(R.string.ft_retry),
                        selected = true,
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        if (onMenu != null && !isOwn) {
            Spacer(Modifier.width(3.dp))
            ApuMenuDots(
                onClick = { onMenu?.invoke() },
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

private fun buildAnnotatedMessageText(text: String, linkColor: Color): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        URL_REGEX.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            addStyle(
                style = SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
                start = start,
                end = end,
            )
            addStringAnnotation(
                tag = "URL",
                annotation = text.substring(start, end),
                start = start,
                end = end,
            )
        }
    }
}

/** Синий «прочитано»: заметен и на золоте своего пузыря, и на светлом фоне. */
private val ReadTickColor = Color(0xFF2E86DE)

@Composable
private fun MessageStatusIcon(status: MessageStatus, tint: Color) {
    // tint — цвет текста пузыря своих сообщений: галочки читаются и на золоте, и на синем.
    val (icon, iconTint) = when (status) {
        MessageStatus.PENDING        -> Pair(Icons.Default.Schedule, tint.copy(alpha = 0.7f))
        MessageStatus.QUEUED_OFFLINE -> Pair(Icons.Default.Schedule, tint.copy(alpha = 0.5f))
        MessageStatus.SENT           -> Pair(Icons.Default.Done, tint.copy(alpha = 0.7f))
        MessageStatus.DELIVERED      -> Pair(Icons.Default.DoneAll, tint)
        // Прочитано - синие галочки. Раньше READ рисовался тем же цветом, что
        // и DELIVERED, и отличить прочитанное от доставленного было нельзя.
        // Цвет задан явно: он должен читаться и на золотом пузыре, и на белом.
        MessageStatus.READ           -> Pair(Icons.Default.DoneAll, ReadTickColor)
        MessageStatus.FAILED         -> Pair(Icons.Default.Error, Color.Red.copy(alpha = 0.8f))
        MessageStatus.LOCAL_FILE     -> Pair(Icons.Default.Schedule, tint.copy(alpha = 0.6f))
        MessageStatus.FILE_EXPIRED   -> Pair(Icons.Default.Error, Color.Red.copy(alpha = 0.8f))
    }
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = iconTint,
        modifier = Modifier.size(14.dp),
    )
}

private fun formatMessageTime(timestamp: Long, yesterdayLabel: String): String {
    val date = Date(timestamp)
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }
    val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }
    return when {
        timestamp > today.timeInMillis -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        timestamp > yesterday.timeInMillis -> yesterdayLabel
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(date)
    }
}


/**
 * Раунд 176: карточка контакта внутри сообщения-приглашения:
 * имя, ник (если известен) и золотая кнопка «Добавить контакт».
 */
@Composable
private fun ContactInviteCardView(
    card: com.vladimir.messenger.util.ContactInviteCard,
    textColor: Color,
    onAdd: () -> Unit,
) {
    Column {
        Text(
            text = card.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
        if (!card.nickname.isNullOrBlank()) {
            Text(
                text = "@" + card.nickname,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(8.dp))
        ApuPremiumContentButton(
            onClick = onAdd,
            style = DiagnosticsActionStyle.PRIMARY,
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_add_contact))
        }
    }
}


/**
 * Раунд 189: карточка приглашения в группу/канал: название жирным,
 * описание ниже, золотой пузырь «Вступить»/«Подписаться».
 */
@Composable
private fun GroupInviteCardView(
    card: com.vladimir.messenger.util.GroupInviteCard,
    textColor: Color,
    onJoin: () -> Unit,
    /** Раунд 218: кнопка «Скачать APU», если в тексте есть установочная ссылка. */
    onDownload: (String) -> Unit = {},
) {
    Column {
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
        if (card.about.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = card.about,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.height(8.dp))
        ApuPremiumContentButton(
            onClick = onJoin,
            style = DiagnosticsActionStyle.PRIMARY,
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (card.isChannel) stringResource(R.string.groups_subscribe) else stringResource(R.string.groups_join_btn))
        }
        card.apkLink?.let { apk ->
            Spacer(Modifier.height(8.dp))
            ApuPremiumContentButton(
                onClick = { onDownload(apk) },
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.mb_download_apu))
            }
        }
    }
}

/**
 * Раунд 218: карточка мульти-приглашения: заголовок, по блоку на каждое
 * сообщество с кнопкой «Вступить»/«Подписаться», ниже кнопка «Скачать APU».
 * Ссылки в сыром тексте спрятаны: владелец просил не показывать адрес
 * сервиса (и GitHub) в видимой части сообщения.
 */
@Composable
private fun MultiInviteCardView(
    card: com.vladimir.messenger.util.MultiInviteCard,
    textColor: Color,
    onOpen: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.mb_invite_communities),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
        Spacer(Modifier.height(8.dp))
        card.items.forEach { item ->
            Text(
                text = item.title.ifBlank { if (item.isChannel) stringResource(R.string.contacts_channel) else stringResource(R.string.contacts_group) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
            Text(
                text = if (item.isChannel) stringResource(R.string.contacts_channel) else stringResource(R.string.contacts_group),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            ApuPremiumContentButton(
                onClick = { onOpen(item.link) },
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (item.isChannel) stringResource(R.string.groups_subscribe) else stringResource(R.string.groups_join_btn))
            }
            Spacer(Modifier.height(8.dp))
        }
        card.apkLink?.let { apk ->
            ApuPremiumContentButton(
                onClick = { onDownload(apk) },
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.mb_download_apu))
            }
        }
    }
}

/** Кнопка «Скачать APU»: это не приглашение (оно идёт в onOpen), а файл - в браузер. */
private fun openInstaller(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        android.util.Log.i("MessageBubble", "Opening installer URL: " + url)
    } catch (e: Exception) {
        android.util.Log.e("MessageBubble", "Failed to open installer URL: " + url, e)
    }
}
