package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.OnEdgeApplication
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.RemoteServer

/** Models page: server models behind an OpenAI-compatible API (llama-server on the PC, ...). */
@Composable
fun RemoteServersCard(viewModel: ModelViewModel) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as OnEdgeApplication).container.remoteServerStore }
    val servers by store.servers.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    val loadedPath = (engine as? EngineState.Ready)?.info?.path
    val loading = engine is EngineState.Loading
    var editing by remember { mutableStateOf<RemoteServer?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Server models (OpenAI API)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use a model on another machine, e.g. llama-server on the PC: base URL like http://192.168.1.20:8080/v1. " +
                    "Tools and Termux work as with a local model.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            servers.forEach { s ->
                val isLoaded = s.path == loadedPath
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            s.baseUrl + (s.model.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()) + if (isLoaded) " • Loaded" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { editing = s }) { Text("Edit") }
                    OutlinedButton(
                        enabled = !loading,
                        onClick = { if (isLoaded) viewModel.unload() else viewModel.loadRemote(s.path) }
                    ) { Text(if (isLoaded) "Unload" else "Use") }
                }
            }
            OutlinedButton(onClick = { editing = RemoteServer(name = "PC llama.cpp", baseUrl = "http://") }) { Text("Add server model") }
        }
    }

    editing?.let { current ->
        RemoteServerDialog(
            initial = current,
            isNew = store.byId(current.id) == null,
            onSave = { store.save(it); editing = null },
            onDelete = {
                if (current.path == loadedPath) viewModel.unload()
                store.delete(current.id); editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun RemoteServerDialog(
    initial: RemoteServer,
    isNew: Boolean,
    onSave: (RemoteServer) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var ctx by remember { mutableStateOf(initial.contextSize.toString()) }
    val valid = name.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://")) && url.length > 10

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add server model" else "Edit server model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    url, { url = it.trim() }, label = { Text("Base URL (…/v1)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    key, { key = it.trim() }, label = { Text("API key (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(model, { model = it.trim() }, label = { Text("Model name (optional)") }, singleLine = true)
                OutlinedTextField(
                    ctx, { ctx = it.filter(Char::isDigit) }, label = { Text("Context size (tokens)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The API key stays in the app and in your own OneDrive (sync); it is not part of the settings export.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    initial.copy(
                        name = name.trim(), baseUrl = url, apiKey = key, model = model,
                        contextSize = (ctx.toIntOrNull() ?: 32768).coerceIn(1024, 2_000_000)
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
