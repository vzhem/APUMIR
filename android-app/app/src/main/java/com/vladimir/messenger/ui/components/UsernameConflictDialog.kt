package com.vladimir.messenger.ui.components

// =============================================================================
// USERNAMECONFLICTDIALOG.KT
// =============================================================================
// Спор за @имя в рое: если имя оказалось занято пользователем с более ранней
// регистрацией, система снимает наше имя и показывает этот диалог - он не
// закрывается пустым, пока не задано новое имя. Собака - неснимаемый префикс.
// =============================================================================

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.ui.theme.UsernameHolder

@Composable
fun UsernameConflictDialog() {
    val context = LocalContext.current
    var usernameValue by remember { mutableStateOf("") }

    AlertDialog(
        // Диалог обязателен: без имени профиль не участвует в роевом реестре.
        onDismissRequest = { },
        title = { Text("Имя занято") },
        text = {
            Column {
                Text(
                    "Ваше @имя оказалось занято пользователем, который " +
                        "зарегистрировался раньше. Задайте себе новое имя."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = usernameValue,
                    onValueChange = { usernameValue = it },
                    label = { Text("имя") },
                    placeholder = { Text("evzhem") },
                    prefix = { Text("@") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                UsernameHolder.set(context, usernameValue)
                UsernameHolder.clearConflict(context)
            }) { Text("Сохранить") }
        },
    )
}
