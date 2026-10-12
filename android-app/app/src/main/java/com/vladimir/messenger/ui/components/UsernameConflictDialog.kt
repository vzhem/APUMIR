package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

    ApuSettingsDialog(
        // Диалог обязателен: без имени профиль не участвует в роевом реестре.
        onDismissRequest = { },
        title = { Text(stringResource(R.string.ucd_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.username_taken_body)
                )
                Spacer(Modifier.height(12.dp))
                ApuBubbleField(
                    value = usernameValue,
                    onValueChange = { usernameValue = UsernameHolder.sanitize(it) },
                    label = { Text(stringResource(R.string.settings_username_label)) },
                    placeholder = { Text(stringResource(R.string.settings_username_label)) },
                    prefix = { Text("@") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ucd_rules),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.action_save),
                onClick = {
                    UsernameHolder.set(context, usernameValue)
                    UsernameHolder.clearConflict(context)
                },
                enabled = UsernameHolder.isValid(usernameValue),
            )
        },
    )
}
