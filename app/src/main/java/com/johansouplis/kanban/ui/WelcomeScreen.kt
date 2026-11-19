package com.johansouplis.kanban.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    state: BoardUiState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onRequestPermission: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("Kanban", style = MaterialTheme.typography.headlineMedium)
            Text("Le tableau est partagé entre vos téléphones. Créez-le sur un premier téléphone, puis rejoignez-le avec son code sur l'autre.")
            if (state.legacyHasReminders && !state.hasCalendarPermission) {
                Text(
                    "Vos tâches actuelles seront reprises dans le tableau. Autorisez d'abord l'accès à l'agenda pour garder leurs rappels.",
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onRequestPermission) { Text("Autoriser l'accès à l'agenda") }
            }
            Button(onClick = onCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Créer un tableau")
            }
            HorizontalDivider()
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(6) },
                label = { Text("Code du tableau") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onJoin(code) }, enabled = !state.busy && code.length == 6, modifier = Modifier.fillMaxWidth()) {
                Text("Rejoindre avec un code")
            }
            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
