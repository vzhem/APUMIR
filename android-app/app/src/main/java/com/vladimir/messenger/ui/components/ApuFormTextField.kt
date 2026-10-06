package com.vladimir.messenger.ui.components

// =============================================================================
// APUFORMTEXTFIELD.KT — фирменное поле ввода для форм поверх обоев
// =============================================================================
// В стандартном OutlinedTextField Material подложка и серо-лиловая обводка
// отличаются от пузырей APU. Формы «Добавить контакт», настройки и мастера
// должны выглядеть частью того же интерфейса: светлая поверхность, золотая
// рамка и читаемый фиксированный текст в любой теме.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val ApuFormFieldShape = RoundedCornerShape(14.dp)
private val ApuFormFieldFill = Color.White.copy(alpha = 0.62f)

/**
 * Поле формы в стиле пузырей APU.
 *
 * Label находится над полем, а не плавает в рамке: так подпись остаётся
 * читаемой при длинном значении и поля не выглядят как стандартный Material
 * блок другого приложения.
 */
@Composable
fun ApuFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = label,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = ApuBubbleMutedColor,
        )
        Spacer(Modifier.height(5.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    color = ApuBubbleMutedColor.copy(alpha = 0.72f),
                )
            },
            leadingIcon = leadingIcon,
            enabled = enabled,
            singleLine = singleLine,
            isError = isError,
            visualTransformation = visualTransformation,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedTextColor = ApuBubbleTextColor,
                unfocusedTextColor = ApuBubbleTextColor,
                disabledTextColor = ApuBubbleMutedColor,
                cursorColor = MaterialTheme.colorScheme.primary,
                errorCursorColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(ApuFormFieldShape)
                .background(ApuFormFieldFill)
                .border(
                    width = 1.dp,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    },
                    shape = ApuFormFieldShape,
                ),
        )
        if (!supportingText.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
