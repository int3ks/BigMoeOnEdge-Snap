package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.ArchiveEntry
import com.bigmoe.onedge.core.GgufSet
import com.bigmoe.onedge.core.ModelManager

/**
 * The external archive, the cache it fills and what waits in Downloads (core/ModelArchive.kt): models are fetched from
 * the archive into the app's storage to be used and cleared from there again; downloads go to the model folder or to
 * the archive. A model in the cache is an ordinary card in the list below, which is where "Clear from cache" is.
 */
@Composable
internal fun ModelArchiveCard(
    ui: ModelsUiState,
    onChooseFolder: () -> Unit,
    onUseFolder: (String) -> Unit,
    onTurnOff: () -> Unit,
    onRefresh: () -> Unit,
    onFetch: (String) -> Unit,
    onClearCache: (String) -> Unit,
    onCancel: () -> Unit,
    onDownloadToModels: (GgufSet) -> Unit,
    onDownloadToArchive: (GgufSet) -> Unit
) {
    val idle = ui.importProgress == null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Archive & downloads", style = MaterialTheme.typography.titleMedium)
            val folder = ui.archiveFolder
            val status = when {
                folder == null -> "Keep big models on external storage (a USB drive) and fetch one into the app when you want to " +
                    "use it. Clearing it from the app afterwards keeps what you set on its card."
                !ui.archiveReachable -> "$folder\nNot connected. The models below are what the folder held the last time."
                else -> "$folder\n${ui.archiveEntries.size} model${if (ui.archiveEntries.size == 1) "" else "s"} in the archive."
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onChooseFolder) { Text(if (folder == null) "Choose archive folder" else "Change") }
                TextButton(onClick = onRefresh) { Text("Refresh") }
                if (folder != null) TextButton(onClick = onTurnOff) { Text("Turn off") }
            }
            if (folder == null) ui.archiveSuggestions.forEach { path ->
                TextButton(onClick = { onUseFolder(path) }) { Text("Use $path") }
            }

            val progress = ui.importProgress
            if (progress != null) {
                Text(progress.name, style = MaterialTheme.typography.labelMedium)
                if (progress.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.copiedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${ModelManager.formatBytes(progress.copiedBytes)} / ${ModelManager.formatBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }

            if (ui.archiveEntries.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("In the archive", style = MaterialTheme.typography.titleSmall)
                ui.archiveEntries.forEach { entry -> ArchiveRow(entry, idle, onFetch, onClearCache) }
            }

            if (ui.downloads.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("In Downloads", style = MaterialTheme.typography.titleSmall)
                ui.downloads.forEach { set ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(set.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ModelManager.formatBytes(set.sizeBytes) + when {
                                !set.complete -> " · ${set.files.size} of ${set.shardCount} shards - still downloading?"
                                set.shardCount > 1 -> " · ${set.shardCount} shards"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onDownloadToModels(set) }, enabled = idle && set.complete && ui.modelFolder != null) {
                                Text("Move to model folder")
                            }
                            TextButton(onClick = { onDownloadToArchive(set) }, enabled = idle && set.complete && folder != null) {
                                Text("Move to archive")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(entry: ArchiveEntry, idle: Boolean, onFetch: (String) -> Unit, onClearCache: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
            val state = when {
                entry.cached -> "in the cache"
                !entry.complete -> "incomplete in the archive"
                entry.partial -> "fetch interrupted, continues where it stopped"
                !entry.reachable -> "archive not connected"
                else -> "only in the archive"
            }
            Text(
                "${ModelManager.formatBytes(entry.sizeBytes)} · $state",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.cached) {
            TextButton(onClick = { onClearCache(entry.name) }, enabled = idle) { Text("Clear from cache") }
        } else {
            Button(onClick = { onFetch(entry.name) }, enabled = idle && entry.reachable && entry.complete) {
                Text(if (entry.partial) "Continue" else "Fetch")
            }
        }
    }
}
