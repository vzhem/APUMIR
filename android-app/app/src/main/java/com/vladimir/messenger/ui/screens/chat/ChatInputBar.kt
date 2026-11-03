package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.ui.components.ApuPremiumSpinner
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.apuBubbleSurface
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.apuPremiumThread
import com.vladimir.messenger.ui.components.apuGoldBrush
import com.vladimir.messenger.ui.components.ApuGold
import com.vladimir.messenger.ui.components.ApuGoldDeep
import com.vladimir.messenger.ui.components.ApuGoldInk

/**
 * Панель ввода личного чата: поле, скрепка, голос, GIF, метка, опрос и отправка.
 *
 * Вынесена из `ChatDetailScreen.kt` при добавлении опросов (р252): экран
 * упёрся в потолок god-файлов, а панель — самостоятельный кусок без состояния
 * переписки. Поведение прежнее: при наборе кнопки уходят над полем, «Отправить»
 * остаётся под ним, `contentReceiver` (вставка картинок) живёт здесь же.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendChoice: (com.vladimir.messenger.ui.components.SendChoice) -> Unit = {},
    onVoiceReady: (android.net.Uri) -> Unit = {},
    onVoiceLocked: () -> Unit = {},   // ранг: голосовые ещё закрыты
    scheduled: List<com.vladimir.messenger.data.schedule.ScheduledSendStore.Item> = emptyList(),
    onCancelScheduled: (String) -> Unit = {},
    replyAuthor: String? = null,
    replyText: String = "",
    onClearReply: () -> Unit = {},
    isSending: Boolean,
    /** р241: переписка с собственным узлом - отправлять здесь нечего. */
    isSelfChat: Boolean = false,
    /**
     * От собеседника приходят невскрываемые конверты (у него прежняя копия
     * нашего ключа). Плашка объясняет это словами и даёт кнопку «отдать ключ».
     */
    keyDesync: Boolean = false,
    onKeyDesyncAction: () -> Unit = {},
    onAttach: () -> Unit = {},
    isPreparingFile: Boolean = false,
    canAttach: Boolean = true,
    onPastedMedia: (android.net.Uri) -> Unit = {},
    onPasteLocked: () -> Unit = {},
    onGifClick: () -> Unit = {},
    /** «Поделиться координатами»: кнопка метки рядом со скрепкой. */
    onLocationClick: () -> Unit = {},
    /** Координаты определяются прямо сейчас - на кнопке крутится спиннер. */
    isLocating: Boolean = false,
    /** р252: опрос в личном чате (ранг 3). null - кнопки нет (сам с собой). */
    onCreatePoll: (() -> Unit)? = null,
    /** Возможность закрыта рангом: кнопка видна, но объясняет причину. */
    canCreatePoll: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Раунд 42: новый BasicTextField(state) - только он проводит картинки со
    // стикер-клавиатуры в contentReceiver. Старый TextField их не принимал, и
    // система показывала тост «Приложение не поддерживает вставку изображений».
    val inputState = rememberTextFieldState(text)
    LaunchedEffect(text) {
        if (inputState.text.toString() != text) {
            inputState.edit { replace(0, length, text) }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { inputState.text.toString() }.collect { onTextChange(it) }
    }
    // Раунд 45: подложка панели следует теме (светлая/тёмная) и полупрозрачна -
    // обои (фирменные или свои) проходят сквозь неё. Белые пузыри скрепки,
    // поля и стрелки читаются на любом фоне.
    // Раунд 266: фирменный пузырь панели ввода - светлая полупрозрачная
    // подложка с золотой рамкой вместо серого «квадрата» на обоях.
    // Раунд 149: imePadding - панель поднимается над клавиатурой.
    // Владелец 2026-10-07: «Доделывай все разделы с новым стилем».
    // Панель ввода — та же премиальная поверхность, что строки списков,
    // «Избранное» и диалоги: подъём, единая подложка, золотая нить по кромке,
    // блеск ПОД текстом (поле и подписи остаются чёткими). Облачка самих
    // сообщений не трогаем — так решил владелец, у них свой фирменный пузырь.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .apuPremiumLift(6.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (!replyAuthor.isNullOrBlank()) {
            com.vladimir.messenger.ui.components.MessageReplyStrip(
                author = replyAuthor,
                text = replyText,
                onClear = onClearReply,
            )
        }
        // Раунд 148: как в темах - при наборе скрепка и GIF уходят НАД
        // полем, «Отправить» - своим пузырём во всю ширину ПОД полем.
        var inputFocused by remember { mutableStateOf(false) }
        Column {
            if (inputFocused) {
                Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(14.dp),
                    ),
            ) {
            IconButton(
                onClick = onAttach,
                enabled = !isPreparingFile && !isSending,
                modifier = Modifier.size(48.dp),
            ) {
                if (isPreparingFile) {
                    ApuPremiumSpinner(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = if (canAttach) {
                            stringResource(R.string.chat_attach_file)
                        } else {
                            stringResource(R.string.chat_attach_rank_hint)
                        },
                        tint = if (canAttach) {
                            Color(0xFF5A6472)
                        } else {
                            Color(0xFF9AA3AF)
                        },
                    )
                }
            }
            }

            // Каталог GIF (v11.74.15): ОТДЕЛЬНАЯ кнопка рядом со скрепкой,
            // те же права, что у вложений. Раньше кнопка была вложена внутрь
            // IconButton скрепки и накладывалась на неё.
                ApuTextAction(
                    label = "GIF",
                    onClick = onGifClick,
                    enabled = !isPreparingFile && !isSending && canAttach,
                )
                IconButton(
                    onClick = onLocationClick,
                    enabled = !isPreparingFile && !isSending,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (isLocating) {
                        ApuPremiumSpinner(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = stringResource(R.string.chat_share_location),
                            tint = Color(0xFF5A6472),
                        )
                    }
                }
                // р252: опрос - кнопка рядом с меткой. До третьего ранга она
                // остаётся на месте и объясняет, чего не хватает.
                onCreatePoll?.let { createPoll ->
                    IconButton(
                        onClick = createPoll,
                        enabled = !isPreparingFile && !isSending,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.BarChart,
                            contentDescription = stringResource(R.string.poll_new_title),
                            tint = if (canCreatePoll) Color(0xFF5A6472) else Color(0xFF9AA3AF),
                        )
                    }
                }
                }
            }

            BasicTextField(
                state     = inputState,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = Color(0xFF1E2430),
                ),
                cursorBrush = SolidColor(Color(0xFF1E2430)),
                decorator   = object : TextFieldDecorator {
                    @Composable
                    override fun Decoration(content: @Composable () -> Unit) {
                        Box(
                            modifier = Modifier
                                .apuBubbleSurface(color = Color.White)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (inputState.text.isEmpty()) {
                                Text(
                                    stringResource(R.string.chat_input_placeholder),
                                    color = Color(0xFF5A6472),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            content()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { inputFocused = it.isFocused }
                    // Раунд 41: стикеры/картинки/гифки с клавиатуры (Gboard и
                    // др.) вставляются прямо в поле. Раньше система писала
                    // «приложение не поддерживает вставку изображений».
                    // Вложения открываются с ранга «Круг друзей» (3) - тем же
                    // правилом, что и кнопка скрепки.
                    .contentReceiver { transferableContent ->
                        if (!transferableContent.hasMediaType(MediaType.Image)) {
                            return@contentReceiver transferableContent
                        }
                        if (!canAttach || isPreparingFile || isSending) {
                            onPasteLocked()
                            // Забираем картинки себе - системный тост не нужен.
                            return@contentReceiver transferableContent.consume { it.uri != null }
                        }
                        transferableContent.consume { item ->
                            if (item.uri != null) {
                                onPastedMedia(item.uri!!)
                                true
                            } else {
                                false
                            }
                        }
                    },
            )

            Spacer(modifier = Modifier.height(6.dp))

            // «Сообщения от него не открываются». Это премиальный стиль из
            // «Логов»: золотая плашка с нитью и блеском под текстом, чёрные
            // чернила (контраст проверен контрактом »4.5), кнопка справа.
            if (keyDesync && !isSelfChat) {
                val noticeShape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .apuPremiumLift(8.dp, noticeShape, ApuGold.copy(alpha = 0.45f))
                        .clip(noticeShape)
                        .background(apuGoldBrush(), noticeShape)
                        .border(1.dp, Color.White.copy(alpha = 0.45f), noticeShape)
                        .apuPremiumThread(shape = noticeShape, inset = 18.dp)
                        .apuPremiumGloss(noticeShape, intensity = 0.8f, topFraction = 0.55f)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_peer_key_stale),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuGoldInk,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ApuGoldDeep)
                            .clickable(onClick = onKeyDesyncAction)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.chat_resend_key),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // р241: переписка с собственным узлом. Такое случается, если в
            // контакты попал свой же адрес (в профиле есть «Мой QR» - его
            // легко отсканировать самому). Объясняем честно: собеседника
            // здесь нет, а устройства одной личности синхронизируются сами.
            if (isSelfChat) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFF3CD))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_own_node),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1E2430),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Раунд 152: активность читаем НАПРЯМУЮ из inputState - раньше
            // она шла через родителя (snapshotFlow -> onTextChange ->
            // recomposition), и кнопка активировалась с задержкой; плюс
            // золотая заливка и белый текст (как в темах) - видно сразу.
            val canSend = inputState.text.isNotBlank() && !isSending && !isSelfChat
            val voice = com.vladimir.messenger.ui.components.rememberVoiceRecorder(onVoiceReady)
            if (voice.recording || voice.processing) {
                com.vladimir.messenger.ui.components.VoiceRecordBar(voice)
            }
            if (scheduled.isNotEmpty()) {
                com.vladimir.messenger.ui.components.ScheduledSendBanner(
                    items = scheduled,
                    onCancel = onCancelScheduled,
                )
            }
            var showSendOptions by remember { mutableStateOf(false) }
            var showVoiceEffectPicker by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Эффект голоса выбирается ДО записи: та же кнопка, что во время записи.
                ApuTextAction(
                    label = if (voice.effect == com.vladimir.messenger.data.voice.VoiceEffect.ORIGINAL) {
                        stringResource(R.string.voice_effect_short)
                    } else {
                        stringResource(voice.effect.labelRes)
                    },
                    onClick = { showVoiceEffectPicker = true },
                    enabled = !isSending && !isSelfChat,
                    icon = Icons.Filled.GraphicEq,
                    modifier = Modifier.weight(1f),
                )
                // Три кнопки делят ширину строки: на узких экранах не вылетают за край.
                ApuTextAction(
                    label = stringResource(R.string.voice_record_short),
                    onClick = { if (canAttach) voice.toggle() else onVoiceLocked() },
                    enabled = !isSending && !isSelfChat,
                    icon = Icons.Filled.Mic,
                    modifier = Modifier.weight(1f),
                )
                ApuTextAction(
                    label = stringResource(R.string.action_send),
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.weight(1.3f),
                )
                ApuTextAction(
                    label = stringResource(R.string.send_more),
                    onClick = { showSendOptions = true },
                    enabled = canSend,
                    modifier = Modifier.weight(1f),
                )
            }
            if (showVoiceEffectPicker) {
                com.vladimir.messenger.ui.components.VoiceEffectPickerDialog(
                    selected = voice.effect,
                    onSelected = voice.selectEffect,
                    onDismiss = { showVoiceEffectPicker = false },
                )
            }
            if (showSendOptions) {
                com.vladimir.messenger.ui.components.SendOptionsDialog(
                    onDismiss = { showSendOptions = false },
                    onPick = { choice ->
                        showSendOptions = false
                        onSendChoice(choice)
                    },
                )
            }
        }
    }
}
