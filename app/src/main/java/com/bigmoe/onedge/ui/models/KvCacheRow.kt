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
import com.bigmoe.onedge.core.KvCacheType

/**
 * The model's KV cache type, used whenever it loads. F16 is llama.cpp's default; Q8_0 roughly halves the cache with
 * almost no quality loss; Q4_0 quarters it and does cost quality. A type the device cannot run for this model is
 * replaced by F16 at load, with a note.
 */
@Composable
internal fun KvCacheRow(
    selected: KvCacheType,
    /** The type the model is loaded with right now, or null when it is not loaded. */
    loadedWith: KvCacheType?,
    onSelect: (KvCacheType) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("KV cache", style = MaterialTheme.typography.labelMedium)
                Text(
                    describe(selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(selected.label) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    KvCacheType.values().forEach { t ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(t.label)
                                    Text(describe(t), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { menu = false; onSelect(t) }
                        )
                    }
                }
            }
        }
        if (loadedWith != null && loadedWith != selected) {
            Text(
                "Loaded with ${loadedWith.label}. Changes apply the next time this model loads.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun describe(t: KvCacheType): String = when (t) {
    KvCacheType.F16 -> "Default, full precision"
    KvCacheType.Q8_0 -> "About half the memory, nearly lossless"
    KvCacheType.Q4_0 -> "About a quarter of the memory, costs quality"
}
