package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.ui.settings.ChoiceSetting
import com.bigmoe.onedge.ui.settings.Hint

/**
 * How the engine guesses ahead. MTP drafts with the drafter GGUF attached to the loaded model when there is one,
 * otherwise with the model's own MTP head.
 */
internal fun guessLabel(spec: SpecSource): String = when (spec) {
    SpecSource.OFF -> "Off"
    SpecSource.MTP -> "MTP"
    SpecSource.NGRAM -> "N-gram"
}

/** The one per-model MTP setting: which drafter GGUF is attached to this model. */
@Composable
internal fun DrafterRow(
    settings: EngineSettings,
    drafts: List<ModelFile>,
    attachedName: String?,
    onAttachDraft: (String?) -> Unit,
    onPickDraft: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val inUse = settings.spec == SpecSource.MTP
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("MTP drafter", style = MaterialTheme.typography.labelMedium)
            Text(
                when {
                    attachedName == null -> "None attached"
                    inUse -> "$attachedName • in use"
                    else -> "$attachedName • not used while Guess ahead is not MTP"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(if (attachedName == null) "Attach" else "Change") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Choose MTP GGUF file…") }, onClick = { menu = false; onPickDraft() })
                drafts.forEach { d ->
                    DropdownMenuItem(text = { Text(d.name) }, onClick = { menu = false; onAttachDraft(d.path) })
                }
                if (attachedName != null) {
                    DropdownMenuItem(text = { Text("Detach") }, onClick = { menu = false; onAttachDraft(null) })
                }
            }
        }
    }
}
