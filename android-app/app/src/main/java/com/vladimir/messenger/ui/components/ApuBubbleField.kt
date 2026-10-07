package com.vladimir.messenger.ui.components

// =============================================================================
// APUBUBBLEFIELD.KT — фирменное поле ввода вместо стокового OutlinedTextField
// =============================================================================
// Владелец 2026-10-07: «Доделывай все разделы с новым стилем».
//
// До этого поля ввода были Material-«коробочками»: тонкий контур, серая
// подпись, сиреневая подсветка при фокусе. На фирменной подложке APU это
// выглядело как чужой элемент — особенно в светлых пузырях, где цвет из темы
// в тёмной теме и вовсе делал подпись невидимой (те самые «Название» и
// «Описание», которые пропадали).
//
// Здесь то же поле, но в стиле APU: светлый пузырь с золотой кромкой, подъём и
// блеск ПОД текстом (вводимые буквы остаются тёмными чернилами), золотой
// курсор. Цвета ФИКСИРОВАННЫЕ — поле обязано читаться и в тёмной теме.
//
// Параметры названы как у OutlinedTextField: перевод разделов не требует правок
// на месте вызова, кроме удаления своих цветов и формы.
// =============================================================================

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Цвет подписи поля: тот же фирменный приглушённый, что у пояснений. */
private val ApuFieldLabel = ApuBubbleMutedColor

/** Цвет ошибки в поле: тот же тон, что у опасных действий. */
private val ApuFieldError = Color(0xFFA12D3A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApuBubbleField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val shape = RoundedCornerShape(18.dp)
    val fill = Color(0xFFF9FAFC).copy(alpha = 0.96f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .apuPremiumLift(4.dp, shape)
            .apuBubbleSurface(color = fill, shape = shape)
            .apuPremiumGloss(shape, intensity = 0.35f, topFraction = 0.6f),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            prefix = prefix,
            supportingText = supportingText,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = shape,
            // Цвета — наши и ФИКСИРОВАННЫЕ: поле читается и днём, и ночью,
            // потому что лежит на светлом пузыре, а не на теме телефона.
            // Берём только те имена, что уже проверены сборкой в других полях
            // приложения, — набор параметров не выдумываем.
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedTextColor = ApuBubbleTextColor,
                unfocusedTextColor = ApuBubbleTextColor,
                disabledTextColor = ApuBubbleMutedColor,
                errorTextColor = ApuBubbleTextColor,
                cursorColor = ApuBubbleAccentColor,
                errorCursorColor = ApuFieldError,
                focusedLabelColor = ApuBubbleAccentColor,
                unfocusedLabelColor = ApuFieldLabel,
                errorLabelColor = ApuFieldError,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = ApuFieldError.copy(alpha = 0.7f),
            ),
        )
    }
}
