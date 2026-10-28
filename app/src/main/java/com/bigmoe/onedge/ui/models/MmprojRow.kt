package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.ModelFile
import java.io.File

/**
 * The model's vision projector (mmproj): loaded together with the model, so loading never asks for it. A projector
 * that does not match the model fails, and the model then loads text-only with a note.
 */
@Composable
internal fun MmprojRow(
    projectors: List<ModelFile>,
    attached: String?,
    isLoaded: Boolean,
    onAttach: (String?) -> Unit,
    onPick: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Vision projector (mmproj)", style = MaterialTheme.typography.labelMedium)
                Text(
                    attached?.let { File(it).name } ?: "None (text only)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(if (attached == null) "Add" else "Change") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Choose mmproj GGUF file…") }, onClick = { menu = false; onPick() })
                    projectors.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { menu = false; onAttach(p.path) })
                    }
                    if (attached != null) {
                        DropdownMenuItem(text = { Text("Detach") }, onClick = { menu = false; onAttach(null) })
                    }
                }
            }
        }
        if (attached != null && isLoaded) {
            Text(
                "Changes apply the next time this model loads.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
