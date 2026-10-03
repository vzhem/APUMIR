package com.vladimir.messenger.ui.components

// =============================================================================
// APUSEARCHFIELD.KT
// =============================================================================
// Раунд 265: единое фирменное поле поиска. Все списки приложения (чаты,
// контакты, сообщества, каталог, заявки, GIF) ищут через один и тот же
// пузырь: светлая полупрозрачная подложка с золотой рамкой, скругление 18 -
// как пузыри строк. Раньше часть экранов стояла с серым OutlinedTextField
// и выглядела чужеродно.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Светлая подложка пузырей списков APU. */
private val ApuBubbleFill = Color(0xFFF5F7FA).copy(alpha = 0.92f)

/** Тёмный читабельный текст на светлом пузыре. */
private val ApuBubbleText = Color(0xFF1E2430)

/**
 * Поле поиска в фирменном пузыре APU.
 *
 * @param trailing опциональная иконка справа (например «Найти» в GIF-каталоге).
 */
@Composable
fun ApuSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ApuBubbleFill)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(placeholder, color = ApuBubbleText.copy(alpha = 0.45f))
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = ApuBubbleText.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            },
            trailingIcon = trailing,
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = ApuBubbleText,
                unfocusedTextColor = ApuBubbleText,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
