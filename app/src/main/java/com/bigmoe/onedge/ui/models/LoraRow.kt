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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.LoraChoice
import com.bigmoe.onedge.core.ModelFile
import java.io.File
import java.util.Locale

/** Strengths offered for a LoRA adapter; 1.75 is what AtomicChat recommends for its uncensored LoRA. */
private val LORA_SCALES = listOf(1.00f, 1.25f, 1.50f, 1.75f, 2.00f, 2.25f, 2.50f)

private fun Float.label() = String.format(Locale.US, "%.2f", this)

/**
 * The model's LoRA adapter (e.g. an uncensored fine-tune) and its strength. Applied when the model loads; an adapter
 * made for another model fails to load and the model then loads without it.
 */
@Composable
internal fun LoraRow(
    loras: List<ModelFile>,
    attached: LoraChoice?,
    isLoaded: Boolean,
    onAttach: (String?) -> Unit,
    onPick: () -> Unit,
    onScale: (Float) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var scaleMenu by remember { mutableStateOf(false) }
    val name = attached?.let { File(it.path).name }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("LoRA adapter", style = MaterialTheme.typography.labelMedium)
                Text(
                    name ?: "None",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (attached != null) {
                Box {
                    TextButton(onClick = { scaleMenu = true }) { Text("Strength ${attached.scale.label()}") }
                    DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                        LORA_SCALES.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label() + if (s == 1.0f) " (as trained)" else "") },
                                onClick = { scaleMenu = false; onScale(s) }
                            )
                        }
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(if (attached == null) "Add" else "Change") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Choose LoRA GGUF file…") }, onClick = { menu = false; onPick() })
                    loras.forEach { l ->
                        DropdownMenuItem(text = { Text(l.name) }, onClick = { menu = false; onAttach(l.path) })
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
