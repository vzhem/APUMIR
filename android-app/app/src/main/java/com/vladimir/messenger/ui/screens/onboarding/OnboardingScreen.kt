package com.vladimir.messenger.ui.screens.onboarding

// =============================================================================
// ONBOARDINGSCREEN.KT — Экран первого запуска
// =============================================================================
// Три шага:
//   1. EnterName  — пользователь вводит имя
//   2. Generating — анимация генерации ключей
//   3. ShowInvite — QR-код + текстовая ссылка для первого контакта
// =============================================================================

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleCard
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuPremiumIconTile
import com.vladimir.messenger.ui.components.ApuTabBar
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.util.QrCodeGenerator

@Composable
fun OnboardingScreen(
    onProfileCreated: () -> Unit,
    /** Восстановление из файла резервной копии (полный профиль, не только личность). */
    onRestoreFromFile: () -> Unit = {},
    /** Раунд 204: «Вас пригласили?» - друг вставляет ссылку, контакт и ранг сами. */
    onJoinByInvite: (String) -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Переходим дальше когда онбординг завершён
    LaunchedEffect(uiState.step) {
        if (uiState.step == OnboardingStep.ShowInvite && uiState.createdInviteLink != null) {
            // Не переходим сразу — пользователь должен сам нажать "Готово"
        }
    }

    // Показ ошибки через SnackBar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Анимированный переход между шагами
            AnimatedContent(
                targetState   = uiState.step,
                transitionSpec = {
                    fadeIn(tween(400)) togetherWith fadeOut(tween(200))
                },
                label = "onboarding_step"
            ) { step ->
                when (step) {
                    OnboardingStep.EnterName ->
                        EnterNameStep(
                            state                   = uiState,
                            onNameChanged           = viewModel::onDisplayNameChanged,
                            onNicknameChanged       = viewModel::onNicknameChanged,
                            onPasswordChanged       = viewModel::onPasswordChanged,
                            onPasswordRepeatChanged = viewModel::onPasswordRepeatChanged,
                            onRestoreModeChanged    = viewModel::onRestoreModeChanged,
                            onCreateClick           = viewModel::onCreateProfileClicked,
                            onRestoreClick          = viewModel::onRestoreClicked,
                            onRestoreFromFile       = onRestoreFromFile,
                        )
                    OnboardingStep.Generating ->
                        GeneratingStep()
                    OnboardingStep.ShowInvite ->
                        ShowInviteStep(
                            inviteLink  = uiState.createdInviteLink ?: "",
                            fingerprint = uiState.fingerprint ?: "",
                            onFinish    = onProfileCreated,
                            onJoinByInvite = onJoinByInvite,
                        )
                }
            }
        }
    }
}

// =============================================================================
// ШАГ 1: Ввод имени
// =============================================================================
@Composable
private fun EnterNameStep(
    state: OnboardingUiState,
    onNameChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordRepeatChanged: (String) -> Unit,
    onRestoreModeChanged: (Boolean) -> Unit,
    onCreateClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onRestoreFromFile: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ApuPremiumIconTile(
            icon = Icons.Default.Hub,
            contentDescription = "APU",
            size = 64.dp,
            corner = 20.dp,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "APU",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = ApuBubbleTextColor,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Две вкладки: новичок и тот, кто уже был зарегистрирован.
        ApuTabBar(
            titles = listOf(stringResource(R.string.onb_new_profile), stringResource(R.string.onb_already_registered)),
            selectedIndex = if (state.restoreMode) 1 else 0,
            offsetFraction = 0f,
            onSelect = { onRestoreModeChanged(it == 1) },
        )

        Spacer(modifier = Modifier.height(20.dp))

        HintBubble(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (state.restoreMode) {
                    stringResource(R.string.onb_restore_hint)
                } else {
                    stringResource(R.string.onb_keep_credentials)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = ApuBubbleMutedColor,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Имя нужно только новичку: при восстановлении оно придёт из профиля.
        if (!state.restoreMode) {
            ApuBubbleField(
                value    = state.displayName,
                onValueChange = onNameChanged,
                label    = { Text(stringResource(R.string.onb_your_name)) },
                placeholder = { Text(stringResource(R.string.onb_name_placeholder)) },
                singleLine = true,
                isError  = state.nameError != null,
                supportingText = {
                    if (state.nameError != null) {
                        Text(state.nameError, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("${state.displayName.length}/50", color = ApuBubbleMutedColor)
                    }
                },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        ApuBubbleField(
            value    = state.nickname,
            onValueChange = onNicknameChanged,
            label    = { Text(stringResource(R.string.identity_nickname)) },
            placeholder = { Text(stringResource(R.string.onb_nickname_placeholder)) },
            singleLine = true,
            isError  = state.nicknameError != null,
            supportingText = {
                Text(
                    state.nicknameError ?: stringResource(R.string.onb_nickname_rule),
                    color = if (state.nicknameError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        ApuBubbleMutedColor
                    },
                )
            },
            leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        ApuBubbleField(
            value    = state.password,
            onValueChange = onPasswordChanged,
            label    = { Text(stringResource(R.string.identity_password)) },
            singleLine = true,
            isError  = state.passwordError != null,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    state.passwordError
                        ?: if (state.restoreMode) stringResource(R.string.onb_password_old)
                        else stringResource(R.string.onb_pw_min, MIN_PASSWORD_LENGTH),
                    color = if (state.passwordError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        ApuBubbleMutedColor
                    },
                )
            },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction    = if (state.restoreMode) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (state.restoreMode && state.canSubmit) onRestoreClick() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Повтор только при регистрации: опечатку в пароле человек обнаружил
        // бы лишь при восстановлении, когда исправить уже нечем.
        if (!state.restoreMode) {
            Spacer(modifier = Modifier.height(12.dp))
            val mismatch = state.passwordRepeat.isNotEmpty() && state.passwordRepeat != state.password
            ApuBubbleField(
                value    = state.passwordRepeat,
                onValueChange = onPasswordRepeatChanged,
                label    = { Text(stringResource(R.string.onb_confirm_password)) },
                singleLine = true,
                isError  = mismatch,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    if (mismatch) {
                        Text(stringResource(R.string.backup_passwords_mismatch), color = MaterialTheme.colorScheme.error)
                    }
                },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction    = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (state.canSubmit) onCreateClick() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        ApuPremiumContentButton(
            onClick  = if (state.restoreMode) onRestoreClick else onCreateClick,
            style = DiagnosticsActionStyle.PRIMARY,
            enabled  = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Default.Key, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (state.restoreMode) stringResource(R.string.onb_sign_in) else stringResource(R.string.onb_create_profile),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Вход по никнейму возвращает только адрес и имя. Если человек делал
        // полную резервную копию в файл - из неё вернётся всё: чаты, контакты,
        // сообщества, ключи и ранг.
        if (state.restoreMode) {
            Spacer(modifier = Modifier.height(8.dp))
            ApuPremiumContentButton(
                onClick  = onRestoreFromFile,
                style = DiagnosticsActionStyle.QUIET,
                enabled  = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Default.Restore, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.onb_restore_from_file))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        HintBubble(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = ApuBubbleAccentColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onb_privacy_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// =============================================================================
// ШАГ 2: Анимация генерации ключей
// =============================================================================
@Composable
private fun GeneratingStep() {
    val infiniteTransition = rememberInfiniteTransition(label = "generating")
    val rotation by infiniteTransition.animateFloat(
        initialValue   = 0f,
        targetValue    = 360f,
        animationSpec  = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label          = "rotation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Анимированная иконка
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(100.dp),
                color = ApuBubbleAccentColor,
                strokeWidth = 3.dp,
            )
            Icon(
                imageVector        = Icons.Default.Key,
                contentDescription = null,
                modifier           = Modifier.size(48.dp),
                tint               = ApuBubbleAccentColor,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text      = stringResource(R.string.onb_generating),
            style     = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Описание процесса
        val steps = listOf(
            stringResource(R.string.onb_step_sign),
            stringResource(R.string.onb_step_exchange),
            stringResource(R.string.onb_step_node),
            stringResource(R.string.onb_step_store),
        )
        var currentStep by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            steps.forEachIndexed { index, _ ->
                kotlinx.coroutines.delay(400)
                currentStep = index
            }
        }

        AnimatedContent(
            targetState = currentStep,
            label       = "step_text"
        ) { step ->
            Text(
                text  = steps.getOrElse(step) { steps.last() },
                style = MaterialTheme.typography.bodyMedium,
                color = ApuBubbleMutedColor,
            )
        }
    }
}

// =============================================================================
// ШАГ 3: Показ invite-ссылки и QR-кода
// =============================================================================
@Composable
private fun ShowInviteStep(
    inviteLink: String,
    fingerprint: String,
    onFinish: () -> Unit,
    /** Раунд 204: вставить приглашение друга - контакт добавится сам. */
    onJoinByInvite: (String) -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // QR-код из invite-ссылки
    // Тот же генератор, что и в остальном приложении: QR в регистрации должен
    // быть ровно таким же, каким его увидят в контактах и в разделе рангов.
    val qrBitmap = remember(inviteLink) {
        QrCodeGenerator.generateQrCode(inviteLink)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        ApuPremiumIconTile(
            icon = Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.onb_profile_created_cd),
            size = 48.dp,
            corner = 15.dp,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text      = stringResource(R.string.onb_profile_created),
            style     = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Fingerprint ключа
        Text(
            text  = "🔑 $fingerprint",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = ApuBubbleMutedColor,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text  = stringResource(R.string.onb_share_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = ApuBubbleMutedColor,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // QR-код
        qrBitmap?.let { bitmap ->
            ApuBubbleCard(
                modifier = Modifier.size(220.dp),
                backgroundColor = androidx.compose.ui.graphics.Color.White,
                premium = 7.dp,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.onb_qr_cd),
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Текстовая ссылка
        ApuBubbleCard(modifier = Modifier.fillMaxWidth(), premium = 5.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboardManager.setText(AnnotatedString(inviteLink))
                        copied = true
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = inviteLink,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    color = ApuBubbleTextColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = if (copied) ApuBubbleAccentColor else ApuBubbleMutedColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Раунд 204: «Вас пригласили?» - друг прислал сообщение со ссылкой
        // (и APK). Вставка его сюда добавляет контакт сама, и приглашение
        // засчитывается пригласившему в ранг (подписанный токен - tokq/tokr).
        var joinText by remember { mutableStateOf("") }
        val joinValid = com.vladimir.messenger.util.InviteLinkParser.parse(joinText) != null ||
            com.vladimir.messenger.data.group.GroupInviteLinks.parseTarget(joinText) != null
        ApuBubbleCard(modifier = Modifier.fillMaxWidth(), premium = 5.dp) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.onb_invited_question),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ApuBubbleTextColor,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.onb_paste_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                Spacer(modifier = Modifier.height(10.dp))
                ApuBubbleField(
                    value = joinText,
                    onValueChange = { joinText = it },
                    label = { Text(stringResource(R.string.onb_link_or_message)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                ApuPremiumContentButton(
                    onClick = { onJoinByInvite(joinText) },
                    style = DiagnosticsActionStyle.PRIMARY,
                    enabled = joinValid,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(stringResource(R.string.onb_add_inviter))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        ApuPremiumContentButton(
            onClick  = onFinish,
            style = DiagnosticsActionStyle.PRIMARY,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                stringResource(R.string.onb_start_chatting),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}
