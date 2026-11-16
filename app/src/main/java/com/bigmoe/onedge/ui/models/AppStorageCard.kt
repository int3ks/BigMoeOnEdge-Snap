package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.StorageItem

/** What uses the app's own storage (core/AppStorageReport.kt), biggest first, with a delete for what may go. */
@Composable
internal fun AppStorageCard(
    items: List<StorageItem>?,
    scanning: Boolean,
    onScan: () -> Unit,
    onDelete: (StorageItem) -> Unit
) {
    var confirm by remember { mutableStateOf<StorageItem?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("App storage used", style = MaterialTheme.typography.titleMedium)
            Text(
                "What Android counts as this app's data. The model folder (Internal storage/Models) and the archive are not part of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (items != null) {
                Text("Total: ${ModelManager.formatBytes(items.sumOf { it.bytes })}", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onScan, enabled = !scanning) { Text(if (items == null) "Show what uses it" else "Scan again") }
            if (scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            items?.forEach { item ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("${item.title} · ${ModelManager.formatBytes(item.bytes)}", style = MaterialTheme.typography.bodyMedium)
                        Text(item.explanation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.paths.size == 1) {
                            Text(item.paths.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (item.deletable) TextButton(onClick = { confirm = item }) { Text("Delete") }
                }
            }
        }
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${item.title}?") },
            text = { Text("${ModelManager.formatBytes(item.bytes)} are freed." + (item.deleteNote?.let { "\n\n$it" } ?: "")) },
            confirmButton = { TextButton(onClick = { onDelete(item); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } }
        )
    }
}
