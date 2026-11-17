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
import com.bigmoe.onedge.core.SamplingPreset

/**
 * Decoding preset for this model, stored with it: one of [SamplingPreset] or the global values from
 * Settings → Decoding (null). Applies to the next message, no reload needed.
 */
@Composable
internal fun DecodingPresetRow(
    /** The model's own preset, or null when it follows Settings → Decoding. */
    selected: SamplingPreset?,
    onSelect: (SamplingPreset?) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Decoding", style = MaterialTheme.typography.labelMedium)
            Text(
                selected?.summary() ?: "Settings → Decoding (global values)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(selected?.label ?: "Global") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Global")
                            Text("Follows Settings → Decoding", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { menu = false; onSelect(null) }
                )
                SamplingPreset.values().forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.label)
                                Text(p.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(p.summary(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { menu = false; onSelect(p) }
                    )
                }
            }
        }
    }
}
