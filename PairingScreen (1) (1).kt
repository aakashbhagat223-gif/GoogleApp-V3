package com.glassremote.ui.pairing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Functional placeholder UI so pairing can be tested end to end. Glass styling comes next. */
@Composable
fun PairingScreen(vm: PairingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Pair your TV", style = MaterialTheme.typography.headlineMedium)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            when (state.phase) {
                PairingPhase.Idle -> {
                    if (state.devices.isEmpty()) Text("Searching for Google TVs on your Wi-Fi…")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.devices, key = { it.id }) { d ->
                            ListItem(
                                headlineContent = { Text(d.name) },
                                supportingContent = { Text(d.host) },
                                modifier = Modifier.clickable { vm.onIntent(PairingIntent.Select(d)) },
                            )
                        }
                    }
                }
                PairingPhase.Connecting -> Text("Connecting to ${state.selected?.name}…")
                PairingPhase.AwaitingCode, PairingPhase.Verifying -> {
                    Text("Enter the 6-character code shown on your TV")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6) },
                        singleLine = true,
                        label = { Text("Code") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { vm.onIntent(PairingIntent.SubmitCode(code)) },
                            enabled = code.length == 6 && state.phase == PairingPhase.AwaitingCode,
                        ) { Text("Pair") }
                        TextButton(onClick = { vm.onIntent(PairingIntent.Cancel) }) { Text("Cancel") }
                    }
                }
                PairingPhase.Paired -> Text("Paired and connected to ${state.selected?.name}.")
            }
        }
    }
}
