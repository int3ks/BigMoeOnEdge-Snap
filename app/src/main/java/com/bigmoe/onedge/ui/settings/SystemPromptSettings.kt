package com.bigmoe.onedge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.SystemPromptProfile
import kotlinx.coroutines.delay

/**
 * Settings → Chat: the named system prompts. Each chat picks one of them under its input bar; the one marked here is
 * what a new chat starts with. Switching a chat's prompt never reloads the model.
 */
@Composable
internal fun SystemPromptsSection(settings: EngineSettings, viewModel: SettingsViewModel) {
    val profiles = settings.systemPrompts
    val defaultId = SystemPromptProfile.pick(profiles, settings.defaultSystemPromptId)?.id
    // One prompt at a time: the picker above chooses which one is shown for editing.
    var selectedId by remember { mutableStateOf(defaultId) }
    var lastCount by remember { mutableStateOf(profiles.size) }
    LaunchedEffect(profiles.size) {
        if (profiles.size > lastCount) selectedId = profiles.lastOrNull()?.id // a new prompt: show it
        lastCount = profiles.size
    }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull { it.id == defaultId } ?: profiles.firstOrNull()
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (profiles.isEmpty()) {
            Hint("No system prompts yet. Add one, give it a name and a text; then choose it under the chat input.")
        } else {
            Hint("The chat input offers these prompts; \u2605 = new chats start with it. Changing a prompt makes the engine re-read the conversation once; the model stays loaded.")
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (selected?.name ?: "").ifBlank { "(no name)" } + if (selected?.id == defaultId) "  \u2605" else "",
                        modifier = Modifier.weight(1f)
                    )
                    Text("\u25BE")
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    profiles.forEach { p ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(p.name.ifBlank { "(no name)" } + if (p.id == defaultId) "  \u2605" else "") },
                            onClick = { selectedId = p.id; menuOpen = false }
                        )
                    }
                }
            }
        }
        selected?.let { profile ->
            // key(): switching prompts gives the text fields the other prompt's text.
            androidx.compose.runtime.key(profile.id) {
                SystemPromptCard(
                    profile = profile,
                    isDefault = profile.id == defaultId,
                    onUpdate = { name, prompt -> viewModel.updateSystemPrompt(profile.id, name, prompt) },
                    onMakeDefault = { viewModel.setDefaultSystemPrompt(profile.id) },
                    onDelete = { viewModel.deleteSystemPrompt(profile.id); selectedId = defaultId }
                )
            }
        }
        Button(onClick = { viewModel.addSystemPrompt() }) { Text("+ New system prompt") }
    }
}

@Composable
private fun SystemPromptCard(
    profile: SystemPromptProfile,
    isDefault: Boolean,
    onUpdate: (name: String, prompt: String) -> Unit,
    onMakeDefault: () -> Unit,
    onDelete: () -> Unit
) {
    // Text fields keep their own state, seeded once, and write back debounced (like the other settings fields).
    var name by remember { mutableStateOf(profile.name) }
    var prompt by remember { mutableStateOf(profile.prompt) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(name, prompt) {
        if (name != profile.name || prompt != profile.prompt) {
            delay(DEBOUNCE_MS)
            onUpdate(name, prompt)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("System prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isDefault, onClick = onMakeDefault)
                Text(
                    "Start new chats with this prompt",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (confirmDelete) {
                    TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                } else {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            }
        }
    }
}
