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
import com.bigmoe.onedge.core.SpecSource

/**
 * Guess ahead for this model, stored with it: Off, MTP or n-gram, or the default from the Guess ahead card (null).
 * How many tokens are drafted and the MTP / n-gram fine settings stay on that card for all models.
 */
@Composable
internal fun GuessAheadRow(
    /** The model's own choice, or null when it follows the default. */
    selected: SpecSource?,
    /** The Guess ahead card's mode, used while [selected] is null. */
    default: SpecSource,
    /** The mode the model is loaded with right now, or null when it is not loaded. */
    loadedWith: SpecSource?,
    hasDrafter: Boolean,
    onSelect: (SpecSource?) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val effective = selected ?: default
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Guess ahead", style = MaterialTheme.typography.labelMedium)
                Text(
                    describe(effective, hasDrafter) + if (selected == null) " (default)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(if (selected == null) "Default" else guessLabel(selected)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Default (${guessLabel(default)})")
                                Text("Follows the Guess ahead card above", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { menu = false; onSelect(null) }
                    )
                    listOf(SpecSource.OFF, SpecSource.MTP, SpecSource.NGRAM).forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(guessLabel(s))
                                    Text(describe(s, hasDrafter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { menu = false; onSelect(s) }
                        )
                    }
                }
            }
        }
        if (loadedWith != null && loadedWith != effective) {
            Text(
                "Loaded with ${guessLabel(loadedWith)}. Changes apply the next time this model loads.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun describe(s: SpecSource, hasDrafter: Boolean): String = when (s) {
    SpecSource.OFF -> "Off: one token per pass"
    SpecSource.MTP -> if (hasDrafter) "MTP with the attached drafter" else "MTP with the model's own head (fails to load without one)"
    SpecSource.NGRAM -> "N-gram: drafts from text that already appeared"
}
