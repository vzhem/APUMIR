package com.vladimir.messenger.ui.screens.onboarding

// =============================================================================
// ONBOARDINGSCREEN.KT — Экран первого запуска
// =============================================================================
// Три шага:
//   1. EnterName  — пользователь вводит имя
//   2. Generating — анимация генерации ключей
//   3. ShowInvite — QR-код + текстовая ссылка для первого контакта
// =============================================================================

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.vladimir.messenger.util.QrCodeGenerator

@Composable
fun OnboardingScreen(
    onProfileCreated: () -> Unit,
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
                        )
                    OnboardingStep.Generating ->
                        GeneratingStep()
                    OnboardingStep.ShowInvite ->
                        ShowInviteStep(
                            inviteLink  = uiState.createdInviteLink ?: "",
                            fingerprint = uiState.fingerprint ?: "",
                            onFinish    = onProfileCreated,
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
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector        = Icons.Default.Hub,
            contentDescription = null,
            modifier           = Modifier.size(64.dp),
            tint               = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text      = "APU",
            style     = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Две вкладки: новичок и тот, кто уже был зарегистрирован.
        TabRow(selectedTabIndex = if (state.restoreMode) 1 else 0) {
            Tab(
                selected = !state.restoreMode,
                onClick  = { onRestoreModeChanged(false) },
                text     = { Text("Новый профиль") },
            )
            Tab(
                selected = state.restoreMode,
                onClick  = { onRestoreModeChanged(true) },
                text     = { Text("Я уже зарегистрирован") },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = if (state.restoreMode) {
                "Введите никнейм и пароль, которые задавали раньше — вернётся ваш прежний профиль со всеми контактами и рангом."
            } else {
                "Никнейм и пароль понадобятся, чтобы вернуть себя после переустановки. Запомните их."
            },
            style     = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Имя нужно только новичку: при восстановлении оно придёт из профиля.
        if (!state.restoreMode) {
            OutlinedTextField(
                value    = state.displayName,
                onValueChange = onNameChanged,
                label    = { Text("Ваше имя") },
                placeholder = { Text("Имя Фамилия") },
                singleLine = true,
                isError  = state.nameError != null,
                supportingText = {
                    if (state.nameError != null) {
                        Text(state.nameError, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("${state.displayName.length}/50", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        OutlinedTextField(
            value    = state.nickname,
            onValueChange = onNicknameChanged,
            label    = { Text("Никнейм") },
            placeholder = { Text("например anna_k") },
            singleLine = true,
            isError  = state.nicknameError != null,
            prefix   = { Text("@") },
            supportingText = {
                Text(
                    state.nicknameError ?: "Латиница, цифры и подчёркивание, минимум 3 знака",
                    color = if (state.nicknameError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value    = state.password,
            onValueChange = onPasswordChanged,
            label    = { Text("Пароль") },
            singleLine = true,
            isError  = state.passwordError != null,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    state.passwordError
                        ?: if (state.restoreMode) "Тот, что задавали раньше"
                        else "Минимум $MIN_PASSWORD_LENGTH знаков",
                    color = if (state.passwordError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
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
            OutlinedTextField(
                value    = state.passwordRepeat,
                onValueChange = onPasswordRepeatChanged,
                label    = { Text("Подтвердите пароль") },
                singleLine = true,
                isError  = mismatch,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    if (mismatch) {
                        Text("Пароли не совпадают", color = MaterialTheme.colorScheme.error)
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

        Button(
            onClick  = if (state.restoreMode) onRestoreClick else onCreateClick,
            enabled  = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Key, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (state.restoreMode) "Войти" else "Создать профиль",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text  = "Пароль не покидает телефон: ключи запираются прямо здесь. " +
                        "Ни телефонного номера, ни почты - только вы и ваши контакты.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                modifier  = Modifier.size(100.dp),
                strokeWidth = 3.dp,
            )
            Icon(
                imageVector        = Icons.Default.Key,
                contentDescription = null,
                modifier           = Modifier.size(48.dp),
                tint               = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text      = "Генерация ключей",
            style     = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Описание процесса
        val steps = listOf(
            "Создаём ключ подписи...",
            "Создаём ключ обмена...",
            "Готовим ваш узел сети...",
            "Сохраняем в защищённое хранилище...",
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint     = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text      = "Профиль создан!",
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text  = "Поделитесь QR-кодом или ссылкой, чтобы добавить первый контакт",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // QR-код
        qrBitmap?.let { bitmap ->
            Card(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp)),
                elevation = CardDefaults.cardElevation(4.dp),
            ) {
                Image(
                    bitmap             = bitmap.asImageBitmap(),
                    contentDescription = "QR-код для добавления контакта",
                    modifier           = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Текстовая ссылка
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
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
                    text     = inviteLink,
                    style    = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Скопировать",
                    tint = if (copied) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick  = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Text(
                "Начать общение",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}
