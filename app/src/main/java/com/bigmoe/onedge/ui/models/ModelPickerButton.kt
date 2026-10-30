package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.ModelManager

/**
 * The chip button in the chat's top bar. Tapping it opens a small list of every registered model: pick one to load
 * it (the usual confirmation with the memory advice and projector choice follows), or turn the loaded one off.
 * "Manage models" still opens the full Models page.
 */
@Composable
fun ModelPickerButton(
    viewModel: ModelViewModel,
    isGenerating: Boolean,
    onManageModels: () -> Unit
) {
    val ui by viewModel.uiState.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    var open by remember { mutableStateOf(false) }

    val models = ui.models.filterNot { ModelManager.isProjectorName(it.name) }
    val loadedPath = (engine as? EngineState.Ready)?.info?.path
    val loading = engine is EngineState.Loading

    Box {
        IconButton(onClick = { viewModel.refresh(); open = true }) {
            Icon(imageVector = Icons.Default.Memory, contentDescription = "Models")
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // As wide as the screen allows, so a long file name (and the quantisation at its end) is always readable.
            modifier = Modifier.widthIn(min = 280.dp, max = (LocalConfiguration.current.screenWidthDp - 16).coerceAtLeast(280).dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Models", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (loading) {
                    Text("Loading a model\u2026", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (isGenerating) {
                    Text("Stop the reply to unload the model.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (models.isEmpty()) {
                Text(
                    "No models yet. Add one from the Models page.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            models.forEach { model ->
                val isLoaded = model.path == loadedPath
                val enabled = !loading && !(isLoaded && isGenerating)
                // Own rows instead of DropdownMenuItem: Material3 caps those at 280 dp, which wrapped long file names
                // however wide the menu was. These take the width of the longest name (the menu is at most the
                // screen wide) and keep each name on one line; a name longer than the screen scrolls sideways.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) {
                            open = false
                            if (isLoaded) viewModel.unload() else viewModel.requestLoad(model.path)
                        }
                        .alpha(if (enabled) 1f else 0.38f)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())) {
                        Text(model.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                        if (model.displayName != model.name) {
                            Text(
                                model.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            ModelManager.formatBytes(model.sizeBytes) + if (isLoaded) " \u2022 Loaded" else "",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                            color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (isLoaded) "Unload" else "Load",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage models\u2026") },
                onClick = { open = false; onManageModels() }
            )
        }
    }
    // Same confirmation step as on the Models page (memory advice, projector choice).
    ui.pendingLoad?.let { pending -> PendingLoadDialog(viewModel, pending) }
}
