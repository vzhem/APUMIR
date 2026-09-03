package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Выделение части текста сообщения.
 *
 * Внутри `SelectionContainer`: человек ведёт пальцем по нужному куску и
 * копирует его штатным меню Android («Копировать», «Поделиться», «Выделить
 * всё»). Своей кнопки копирования тут нет намеренно - копирование целиком уже
 * есть отдельным пунктом в списке действий.
 */
@Composable
fun SelectTextDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выделите нужное") },
        text = {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        },
    )
}
