package com.bigmoe.onedge.ui.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.bigmoe.onedge.core.FetchTarget
import com.bigmoe.onedge.core.SourceSuggestion
import com.bigmoe.onedge.core.GgufSet
import com.bigmoe.onedge.core.ModelManager

/**
 * The external archive and what waits in Downloads (core/ModelArchive.kt): each archive model can be copied into the
 * model folder (kept), fetched into the app's storage (cache) or used where it lies; a fetched copy is cleared again without losing
 * its settings; downloads go to the model folder or to the archive. A local copy is an ordinary card in the list below,
 * with "Clear copy" on it too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelArchiveCard(
    ui: ModelsUiState,
    /** Opens the folder picker for a new source. */
    onAddFolder: () -> Unit,
    /** A suggested folder (a removable volume's models folder) to add. */
    onAddSuggestion: (SourceSuggestion) -> Unit,
    onRemoveSource: (String) -> Unit,
    onRefresh: () -> Unit,
    onFetch: (String, FetchTarget) -> Unit,
    onUseInPlace: (String) -> Unit,
    onCancel: () -> Unit,
    onDownloadToModels: (GgufSet) -> Unit,
    onDownloadToArchive: (GgufSet) -> Unit
) {
    val idle = ui.importProgress == null
    // Collapsed by default: the list gets long.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Archive & downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!expanded) {
                    Text(
                        "${ui.archiveSources.size} source(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(if (expanded) "  \u25B4" else "  \u25BE", style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) {
            if (ui.archiveSources.isEmpty()) {
                Text(
                    "Keep big models on USB sticks, SSDs or the SD card so you do not download them again, and copy one " +
                        "to the model folder or use it in place when you want it. What you set on its card is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The sources: one line each; a source that is not attached keeps its models listed from the last time.
            ui.archiveSources.forEach { st ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.source.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            st.source.path + " · " + when {
                                !st.reachable -> "not connected, ${st.modelCount} model(s) last time"
                                !st.writable -> "${st.modelCount} model(s), read-only"
                                else -> "${st.modelCount} model(s)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onRemoveSource(st.source.path) }) { Text("Remove") }
                }
            }
            // Free space per place; refreshed with the page (Refresh, and after every copy or move).
            ui.storageSpace.forEach { space ->
                val used = 1f - (space.freeBytes.toFloat() / space.totalBytes).coerceIn(0f, 1f)
                Text(
                    "${space.label}: ${ModelManager.formatBytes(space.freeBytes)} free of ${ModelManager.formatBytes(space.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall
                )
                LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAddFolder) { Text("Add source folder") }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            ui.archiveSuggestions.forEach { sug ->
                TextButton(onClick = { onAddSuggestion(sug) }) { Text("Add ${sug.name}: ${sug.path}") }
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
                        "${ModelManager.formatBytes(progress.copiedBytes)} / ${ModelManager.formatBytes(progress.totalBytes)}" +
                            if (progress.bytesPerSecond > 0) " · ${ModelManager.formatBytes(progress.bytesPerSecond)}/s" else "",
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
                ui.archiveEntries.forEach { entry -> ArchiveRow(entry, idle, ui.modelFolder != null, onFetch, onUseInPlace) }
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
                            TextButton(onClick = { onDownloadToArchive(set) }, enabled = idle && set.complete && ui.archiveSources.any { it.writable }) {
                                Text("Move to archive")
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * One archive model with its three ways in: Copy to Models (a kept copy in the model folder), Fetch to app (a copy
 * in the app's storage, cleared again with Clear app copy) and Use in place (read from the archive, no copy).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArchiveRow(
    entry: ArchiveEntry,
    idle: Boolean,
    hasModelFolder: Boolean,
    onFetch: (String, FetchTarget) -> Unit,
    onUseInPlace: (String) -> Unit
) {
    val usable = entry.reachable && entry.complete
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
        val where = buildList {
            if (FetchTarget.MODEL_FOLDER in entry.copies) add("in the model folder")
            if (entry.inPlace) add("used in place")
        }
        val state = when {
            where.isNotEmpty() -> where.joinToString(" · ")
            !entry.complete -> "incomplete in the archive"
            entry.partial -> "copy interrupted, continues where it stopped"
            !entry.reachable -> "source not connected"
            else -> "only in the archive"
        }
        Text(
            "${ModelManager.formatBytes(entry.sizeBytes)} · $state\non ${entry.sources.joinToString()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // The model folder is where models are kept: its copy is cleared on the model's card, not here.
            val inFolder = FetchTarget.MODEL_FOLDER in entry.copies
            TextButton(
                onClick = { onFetch(entry.name, FetchTarget.MODEL_FOLDER) },
                enabled = idle && usable && hasModelFolder && !inFolder
            ) { Text(if (inFolder) "In Models" else "Copy to Models") }
            TextButton(onClick = { onUseInPlace(entry.name) }, enabled = usable && !entry.inPlace) {
                Text(if (entry.inPlace) "Used in place" else "Use in place")
            }
        }
    }
}
