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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (profiles.isEmpty()) {
            Hint("No system prompts yet. Add one, give it a name and a text; then choose it under the chat input.")
        } else {
            Hint("The chat input offers these prompts. The selected one is used by new chats. Changing a prompt makes the engine re-read the conversation once; the model stays loaded.")
        }
        profiles.forEach { profile ->
            // key(): a prompt keeps its text fields while others are added or removed.
            androidx.compose.runtime.key(profile.id) {
                SystemPromptCard(
                    profile = profile,
                    isDefault = profile.id == defaultId,
                    onUpdate = { name, prompt -> viewModel.updateSystemPrompt(profile.id, name, prompt) },
                    onMakeDefault = { viewModel.setDefaultSystemPrompt(profile.id) },
                    onDelete = { viewModel.deleteSystemPrompt(profile.id) }
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
